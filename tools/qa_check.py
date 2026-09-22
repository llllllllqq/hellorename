#!/usr/bin/env python3
"""
hellorename 出厂前 APK 体检。

用法:
    python3 tools/qa_check.py app/build/outputs/apk/release/app-release.apk \
        [--expect-cert-sha256 <hex>] [--expect-version-name 1.0.1] \
        [--allow-debug-cert] [--summary-file "$GITHUB_STEP_SUMMARY"]

需要 Android build-tools 里的 aapt2 / apksigner（自动从 $ANDROID_HOME 找）。
找不到工具时会跳过依赖工具的项目并给出 WARN，仍会做纯 zip 层面的检查。

退出码: 0 = 全部通过（允许 WARN）；1 = 有 FAIL。
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import subprocess
import sys
import zipfile

EXPECTED_PACKAGE = "moe.hellorename"
EXPECTED_MIN_SDK = 24
EXPECTED_TARGET_SDK = 34
EXPECTED_ACTIVITY = "moe.hellorename.MainActivity"   # namespace 决定，不随 applicationIdSuffix 变
EXPECTED_PROVIDER = "androidx.core.content.FileProvider"
# androidx.core 会用 App 自己的 applicationId 声明一个 signature 级权限用于
# ContextCompat.registerReceiver(RECEIVER_NOT_EXPORTED)；它不向任何外部应用授权，
# 也不会出现在安装界面的权限列表里，因此是有意保留的。
SELF_PERMISSION_SUFFIX = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
FORBIDDEN_ENTRIES = re.compile(
    r"(^|/)(keystore\.properties|local\.properties)$|\.(jks|keystore|p12|pepk)$"
)
REQUIRED_ENTRIES = ("AndroidManifest.xml", "classes.dex", "resources.arsc")

PASS, FAIL, WARN, INFO = "PASS", "FAIL", "WARN", "INFO"
results: list[tuple[str, str, str]] = []


def add(status: str, title: str, detail: str = "") -> None:
    results.append((status, title, detail))


def check(condition: bool, title: str, detail: str = "", warn_only: bool = False) -> bool:
    if condition:
        add(PASS, title)
    else:
        add(WARN if warn_only else FAIL, title, detail)
    return condition


# --------------------------------------------------------------------------- tools


def find_build_tools() -> str | None:
    candidates = []
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(env)
        if sdk and os.path.isdir(os.path.join(sdk, "build-tools")):
            candidates.append(os.path.join(sdk, "build-tools"))
    for root in candidates:
        for version in sorted(os.listdir(root), reverse=True):
            path = os.path.join(root, version)
            if os.path.isfile(os.path.join(path, "aapt2")):
                return path
    # 退一步：PATH 里直接有
    if shutil_which("aapt2"):
        return ""
    return None


def shutil_which(name: str) -> str | None:
    for directory in os.environ.get("PATH", "").split(os.pathsep):
        path = os.path.join(directory, name)
        if os.path.isfile(path) and os.access(path, os.X_OK):
            return path
    return None


def tool(build_tools: str | None, name: str) -> str | None:
    if build_tools is None:
        return None
    if build_tools == "":
        return shutil_which(name)
    path = os.path.join(build_tools, name)
    return path if os.path.isfile(path) else None


def run(cmd: list[str]) -> tuple[int, str, str]:
    proc = subprocess.run(cmd, capture_output=True, text=True)
    return proc.returncode, proc.stdout, proc.stderr


# --------------------------------------------------------------------------- zip checks


def zip_checks(apk: str) -> None:
    with zipfile.ZipFile(apk) as zf:
        names = zf.namelist()
        bad = [n for n in names if FORBIDDEN_ENTRIES.search(n)]
        check(not bad, "APK 内不含密钥/本地配置文件", ", ".join(bad))

        missing = [n for n in REQUIRED_ENTRIES if n not in names]
        check(not missing, "APK 结构完整 (manifest/dex/arsc)", "缺少: " + ", ".join(missing))

        dex = [n for n in names if re.fullmatch(r"classes\d*\.dex", n)]
        add(INFO, "dex 文件", f"{len(dex)} 个: {', '.join(sorted(dex))}")

        natives = [n for n in names if n.startswith("lib/")]
        check(not natives, "无 native 库（纯 Java/Kotlin）", str(natives[:5]), warn_only=True)

        entries = len(names)
        add(INFO, "APK 条目数", str(entries))


def manifest_tree(aapt2: str, apk: str) -> dict | None:
    code, out, err = run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk])
    if code != 0:
        add(FAIL, "aapt2 dump xmltree", (err or out).strip()[:400])
        return None

    root = {"tag": None, "attrs": {}, "children": []}
    stack: list[tuple[int, dict]] = [(-1, root)]
    for line in out.splitlines():
        stripped = line.strip()
        indent = len(line) - len(line.lstrip())
        if stripped.startswith("E: "):
            tag = stripped[3:].split(" ")[0]
            node = {"tag": tag, "attrs": {}, "children": []}
            while stack and stack[-1][0] >= indent:
                stack.pop()
            stack[-1][1]["children"].append(node)
            stack.append((indent, node))
        elif stripped.startswith("A: ") and "=" in stripped:
            raw_name, value = stripped[3:].split("=", 1)
            name = raw_name.split("(")[0].split(":")[-1]
            stack[-1][1]["attrs"][name] = value.strip()
    return root["children"][0] if root["children"] else None


def attr_bool(value: str | None) -> bool | None:
    if value is None:
        return None
    text = value.strip()
    if text.startswith("true") or text.endswith("0xffffffff") or text.endswith("0xffffffffffffffff"):
        return True
    if text.startswith("false") or text.endswith("0x0"):
        return False
    return None


def attr_enum_is(value: str | None, expected: int) -> bool:
    """aapt2 可能输出 `1`、`0x1` 或 `(type 0x10)0x1`，缺省值则完全不带该属性。"""
    if value is None:
        return True
    text = value.strip()
    if text == str(expected):
        return True
    return bool(re.search(rf"0x{expected:x}$", text))


def attr_int(value: str | None) -> int | None:
    """解析 aapt2 输出的整数属性，如 `(type 0x10)0x00000002` / `2`。"""
    if value is None:
        return None
    text = value.strip()
    hex_match = re.search(r"0x([0-9a-fA-F]+)$", text)
    if hex_match:
        return int(hex_match.group(1), 16)
    if text.isdigit():
        return int(text)
    return None


def attr_str(value: str | None) -> str:
    if value is None:
        return ""
    match = re.match(r'"((?:[^"\\]|\\.)*)"', value.strip())
    return match.group(1) if match else value.strip()


def find_child(node: dict, tag: str) -> dict | None:
    for child in node.get("children", []):
        if child["tag"] == tag:
            return child
    return None


def find_children(node: dict, tag: str) -> list[dict]:
    return [c for c in node.get("children", []) if c["tag"] == tag]


def manifest_checks(aapt2: str, apk: str, expected_authority: str) -> None:
    tree = manifest_tree(aapt2, apk)
    if tree is None:
        return

    app = find_child(tree, "application")
    if not check(app is not None, "manifest 里有 <application>"):
        return

    check(
        attr_bool(app["attrs"].get("debuggable")) is not True,
        "release 包不可调试 (debuggable=false)",
        f"debuggable={app['attrs'].get('debuggable')}",
    )

    # ---- 权限 ----
    used = [attr_str(n["attrs"].get("name")) for n in find_children(tree, "uses-permission")]
    unexpected = [name for name in used if not name.endswith(SELF_PERMISSION_SUFFIX)]
    check(not unexpected, "没有多余的权限申请", ", ".join(unexpected))
    if used:
        add(INFO, "声明的权限", ", ".join(used))
    declared = {attr_str(n["attrs"].get("name")): n for n in find_children(tree, "permission")}
    for name in used:
        if name.endswith(SELF_PERMISSION_SUFFIX):
            node = declared.get(name)
            level = attr_int(node["attrs"].get("protectionLevel")) if node else None
            # protectionLevel 的 signature 位 = 0x2
            check(
                level is not None and (level & 0x2) == 0x2,
                "自用权限 protectionLevel=signature（不对外授予）",
                f"0x{level:08x}" if level is not None else "未解析到",
            )

    # ---- activity ----
    activity = None
    for node in find_children(app, "activity"):
        if attr_str(node["attrs"].get("name")) == EXPECTED_ACTIVITY:
            activity = node
            break
    if check(activity is not None, f"存在 activity {EXPECTED_ACTIVITY}"):
        assert activity is not None
        check(
            attr_bool(activity["attrs"].get("exported")) is True,
            "MainActivity exported=true（要能被系统分享面板拉起）",
        )
        launch_mode = activity["attrs"].get("launchMode")
        check(
            attr_enum_is(launch_mode, 1),
            "MainActivity launchMode=singleTop",
            str(launch_mode),
        )
        actions = set()
        categories = set()
        for intent_filter in find_children(activity, "intent-filter"):
            for action in find_children(intent_filter, "action"):
                actions.add(attr_str(action["attrs"].get("name")))
            for category in find_children(intent_filter, "category"):
                categories.add(attr_str(category["attrs"].get("name")))
        for action in ("android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE"):
            check(action in actions, f"注册了 {action.split('.')[-1]} 分享接收")
        check("android.intent.action.MAIN" in actions, "注册了 MAIN（可从启动器打开）")
        check("android.intent.category.LAUNCHER" in categories, "注册了 LAUNCHER")

    # ---- provider ----
    provider = None
    for node in find_children(app, "provider"):
        if attr_str(node["attrs"].get("authorities")) == expected_authority:
            provider = node
            break
    if check(provider is not None, f"存在 FileProvider({expected_authority})"):
        assert provider is not None
        check(
            attr_str(provider["attrs"].get("name")) == EXPECTED_PROVIDER,
            f"provider 是 {EXPECTED_PROVIDER}",
        )
        check(
            attr_bool(provider["attrs"].get("exported")) is False,
            "FileProvider exported=false（不能被任意应用访问）",
            f"exported={provider['attrs'].get('exported')}",
        )
        check(
            attr_bool(provider["attrs"].get("grantUriPermissions")) is True,
            "FileProvider grantUriPermissions=true（否则分享出去对方读不到）",
        )
        meta = find_children(provider, "meta-data")
        check(
            any(attr_str(m["attrs"].get("name")) == "android.support.FILE_PROVIDER_PATHS" for m in meta),
            "FileProvider 配置了 FILE_PROVIDER_PATHS",
        )


# --------------------------------------------------------------------------- badging


def badging_value(out: str, *keys: str) -> str | None:
    """从 aapt2 dump badging 的输出里取一行形如 `key:'value'` 的值
    （按行首匹配，避免 minSdkVersion 误匹配到 targetSdkVersion）。"""
    for line in out.splitlines():
        stripped = line.strip()
        for key in keys:
            prefix = f"{key}:'"
            if stripped.startswith(prefix):
                return stripped[len(prefix):].rstrip("'")
    return None


def badging_checks(
    aapt2: str,
    apk: str,
    expect_version_name: str | None,
    expect_package: str,
) -> None:
    code, out, err = run([aapt2, "dump", "badging", apk])
    if code != 0:
        add(FAIL, "aapt2 dump badging", (err or out).strip()[:400])
        return

    package = re.search(r"package: name='([^']+)' versionCode='([^']*)' versionName='([^']*)'", out)
    if check(package is not None, "能解析 badging 的 package 行"):
        assert package is not None
        check(package.group(1) == expect_package, f"包名 = {expect_package}", package.group(1))
        add(INFO, "versionCode / versionName", f"{package.group(2)} / {package.group(3)}")
        if expect_version_name:
            check(
                package.group(3) == expect_version_name,
                f"versionName 与 tag 一致 ({expect_version_name})",
                package.group(3),
            )

    min_sdk = badging_value(out, "minSdkVersion", "sdkVersion")
    target_sdk = badging_value(out, "targetSdkVersion")
    add(INFO, "badging sdk 行", f"minSdk={min_sdk} targetSdk={target_sdk}")
    if min_sdk is not None:
        check(int(min_sdk) == EXPECTED_MIN_SDK, f"minSdkVersion = {EXPECTED_MIN_SDK}", min_sdk)
    else:
        # 检查静默跳过比检查失败更危险
        add(WARN, "未能从 badging 解析 minSdkVersion", "n/a")
    if target_sdk is not None:
        check(
            int(target_sdk) == EXPECTED_TARGET_SDK,
            f"targetSdkVersion = {EXPECTED_TARGET_SDK}",
            target_sdk,
        )
    else:
        add(WARN, "未能从 badging 解析 targetSdkVersion", "n/a")

    permissions = re.findall(r"uses-permission: name='([^']+)'", out)
    unexpected = [name for name in permissions if not name.endswith(SELF_PERMISSION_SUFFIX)]
    check(
        not unexpected,
        "没有多余的权限申请（如 INTERNET / 存储 等）",
        "实际声明了: " + ", ".join(unexpected),
    )

    check("application-debuggable" not in out, "badging 里没有 application-debuggable")
    check("uses-feature:'android.hardware" not in out, "没有强制硬件特性", warn_only=True)
    label = re.search(r"application-label:'([^']*)'", out)
    add(INFO, "应用名", label.group(1) if label else "?")


# --------------------------------------------------------------------------- signature


def signature_checks(
    apksigner: str,
    apk: str,
    expect_cert: str | None,
    allow_debug_cert: bool,
) -> None:
    code, out, err = run([apksigner, "verify", "--verbose", "--print-certs", apk])
    text = out + err
    check(code == 0, "apksigner verify 通过", text.strip()[:400])

    schemes = dict(re.findall(r"Verified using v(\d) scheme[^:]*: (true|false)", text))
    check(
        schemes.get("2") == "true",
        "APK Signature Scheme v2 校验通过（minSdk 24 必需）",
        f"v2={schemes.get('2')}",
    )
    check(
        schemes.get("3") == "true",
        "APK Signature Scheme v3 校验通过（支持未来换签名密钥）",
        f"v3={schemes.get('3')}",
    )
    for version in ("1", "4"):
        if version in schemes:
            add(INFO, f"Signature Scheme v{version}", schemes[version])

    digests = re.findall(r"certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", text)
    if check(bool(digests), "能读到签名证书 SHA-256"):
        digest = digests[0].replace(":", "").lower()
        add(INFO, "签名证书 SHA-256", digest)
        if expect_cert:
            check(
                digest == expect_cert.replace(":", "").lower(),
                "签名证书与发布密钥一致（不是 debug 签名）",
                f"实际 {digest} / 期望 {expect_cert}",
            )
        dn = re.search(r"certificate DN:\s*(.+)", text)
        if dn:
            is_debug = "Android Debug" in dn.group(1)
            check(
                not is_debug or allow_debug_cert,
                "证书不是 Android Debug 证书",
                dn.group(1).strip() + "（可用 --allow-debug-cert 放宽）",
            )


# --------------------------------------------------------------------------- report


def report(apk: str, summary_file: str | None) -> int:
    failures = [r for r in results if r[0] == FAIL]
    warnings = [r for r in results if r[0] == WARN]
    passed = [r for r in results if r[0] == PASS]
    infos = [r for r in results if r[0] == INFO]
    icon = {PASS: "✅", FAIL: "❌", WARN: "⚠️", INFO: "ℹ️"}

    lines = ["", "=" * 72, f"APK QA 报告: {os.path.basename(apk)}", "=" * 72]
    for status, title, detail in results:
        suffix = f" — {detail}" if detail else ""
        lines.append(f"{icon[status]} [{status}] {title}{suffix}")
    lines.append("-" * 72)
    lines.append(
        f"合计: {len(passed)} PASS / {len(failures)} FAIL / {len(warnings)} WARN / {len(infos)} INFO",
    )
    lines.append("=" * 72)
    text = "\n".join(lines)
    print(text)

    if summary_file:
        md = ["", "### APK QA 报告", "", "| | 检查项 | 说明 |", "| --- | --- | --- |"]
        for status, title, detail in results:
            md.append(f"| {icon[status]} | {title} | {detail} |")
        md.append(f"\n**FAIL: {len(failures)} / WARN: {len(warnings)}**")
        try:
            with open(summary_file, "a", encoding="utf-8") as handle:
                handle.write("\n".join(md) + "\n")
        except OSError as exc:
            print(f"写入 summary 失败: {exc}")

    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("--expect-package", default=EXPECTED_PACKAGE)
    parser.add_argument("--expect-cert-sha256", default=None)
    parser.add_argument("--expect-version-name", default=None)
    parser.add_argument("--allow-debug-cert", action="store_true")
    parser.add_argument("--summary-file", default=None)
    args = parser.parse_args()

    if not os.path.isfile(args.apk):
        print(f"找不到 APK: {args.apk}")
        return 1

    size = os.path.getsize(args.apk)
    with open(args.apk, "rb") as handle:
        sha = hashlib.sha256(handle.read()).hexdigest()
    add(INFO, "APK 大小", f"{size / 1048576:.2f} MiB")
    add(INFO, "APK SHA-256", sha)

    build_tools = find_build_tools()
    if build_tools is None:
        add(WARN, "未找到 Android build-tools", "跳过 aapt2 / apksigner 相关检查")
    else:
        add(INFO, "build-tools", build_tools or "(PATH)")

    zip_checks(args.apk)

    expected_authority = f"{args.expect_package}.fileprovider"
    aapt2 = tool(build_tools, "aapt2")
    if aapt2:
        badging_checks(aapt2, args.apk, args.expect_version_name, args.expect_package)
        manifest_checks(aapt2, args.apk, expected_authority)
    else:
        add(WARN, "未找到 aapt2", "跳过 manifest / badging 检查")

    apksigner = tool(build_tools, "apksigner")
    if apksigner:
        signature_checks(apksigner, args.apk, args.expect_cert_sha256, args.allow_debug_cert)
    else:
        add(WARN, "未找到 apksigner", "跳过签名检查")

    return report(args.apk, args.summary_file)


if __name__ == "__main__":
    sys.exit(main())

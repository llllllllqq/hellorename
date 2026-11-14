# AGENTS.md

给在本仓库里干活的 agent（以及未来接手的人）。这里只写**本项目特有**的规则与操作方式，通用 Android 知识不展开。

一句话：**接收系统分享 → 改名 → 再分享/保存的零权限小工具**。任何改动都不能破坏「零权限 + 不改源文件 + 不联网」。

## 1. 项目事实

- 包名 `moe.hellorename`（`main` 正式版；QA 构建会加 `.debug` 后缀，避免和正式包冲突）；应用名 `HelloRename`，中文 `改名助手`。另有长期存在的 `beta` 分支，包名统一带 `.beta` 后缀，见 §1.1。
- 平台：`minSdk 24`、`compileSdk 36`、`targetSdk 36`；AGP **9.4.1**、Gradle **9.6.0**、Kotlin **2.4.21**、JDK **17**。
- 正式版单 Activity（`MainActivity`，`launchMode=singleTop`）；beta 分支多一个无界面的 `ShareActivity` 做文件入口转接。没有自定义 View、没有 Compose。
- 入口有四类：`MAIN/LAUNCHER`、`SEND`、`SEND_MULTIPLE`、`VIEW`（“打开方式”，与单文件分享共用 `loadSource`）；模式状态机是 `ShareMode.FILE / MULTI / TEXT / NONE`。形状随分支：`main` 的 VIEW 是三条 filter（显式 `content` scheme + `*/*`、只写 `*/*`、只写 `content` 兜底 null type）、SEND/SEND_MULTIPLE 是两条 `*/*`，都挂在 `MainActivity` 上；`beta` 从 2.4.0-beta.3 起入口面照搬 SaveCopy（VIEW 一条 7 类 MIME + `content` scheme；SEND/SEND_MULTIPLE 合并一条 7 类 MIME、不带 scheme），并且三条 filter 都挂在独立的 `ShareActivity` 上（`exported` + `noHistory` + `excludeFromRecents` + 透明主题，转发给 `MainActivity` 后 finish），`MainActivity` 只留 `MAIN/LAUNCHER`。都不能放行 `http(s)`。SEND 的 filter 绝不能带 scheme。
- 目录：`app/src/main/java/moe/hellorename/`（业务代码）、`app/src/debug/`（仅测试用 provider）、`tools/`（体检脚本）、`.github/workflows/`（QA / 模拟器 / 发布）。

### 1.1 beta 分支（长期存在）

- 用途：在真机上做 manifest / 入口类对照实验（2.4.0-beta 系列就是把 VIEW 改成 SaveCopy 形状），实验结论只影响 beta，不动正式版。
- 包名：`appVersionName` 旁的 `appIdSuffix` 默认值就是 `.beta`，release / debug / instrumentation 全是 `moe.hellorename.beta`；`emulator_ui_check.sh` 的 `APP_ID` 同步为 `.beta`。不要再给 QA 传 `-PapplicationIdSuffix=.debug`。
- 签名：`build.yml` 用同一套 Secrets，证书 SHA-256 与正式版一致（`3682cce6…cb6ccaca`）。
- 版本：`versionName` 形如 `2.4.0-beta.3`，tag 形如 `v2.4.0-beta.3`；`build.yml` 对带 `-` 的 tag 自动加 `--prerelease`。
- 门禁：`tools/qa_check.py --view-shape savecopy` 断言入口在 `ShareActivity`（`exported/noHistory/excludeFromRecents` 均为 true）、`MainActivity` 不再承载文件入口，且 VIEW 与 SEND/SEND_MULTIPLE 都只有 SaveCopy 那条 filter（7 类 MIME；VIEW 带 `content` scheme，SEND 不带 scheme；无 `*/*`、无 null type 兜底）；正式版继续用默认 `wildcard`。
- 合并方向：`main` → `beta` 可以；`beta` 的包名 / 实验 manifest / `qa_check` profile 不要带回 `main`。合入 `main` 新功能时保留 beta 的 `appIdSuffix`、`viewIntentFilter_resolvesContentUris_andIgnoresWebLinks` 断言和 workflow 里的 `--view-shape savecopy`。

## 2. 硬约束（违反就不要提交）

1. **release 零权限**。APK 里只允许出现 androidx 自用的 `moe.hellorename.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（signature 级、不对外授权）。禁止 `INTERNET`、`READ/WRITE_EXTERNAL_STORAGE`、`MANAGE_EXTERNAL_STORAGE`、`SYSTEM_ALERT_WINDOW` 等任何权限；`qa_check.py` 会因此直接失败。
2. **不联网**。不要新增网络代码、上报、崩溃收集。
3. **不改源文件**。收到分享先复制到 `cacheDir/shared/<session>/`，之后改名/分享/保存都只碰缓存。
4. **不混淆、不 shrink**。`optimization { enable = false }` 是刻意保留的（AGP 9 新 DSL），别顺手打开。
5. **AGP 9 内置 Kotlin**：不要再 `apply("org.jetbrains.kotlin.android")`；KGP 版本固定在根 `build.gradle.kts` 的 buildscript classpath（2.4.21）。
6. **不加依赖**，除非确实必要且不引入权限/网络；优先用框架 API。
7. `app/src/debug/AndroidManifest.xml` 里的 `MANAGE_DOCUMENTS` 只属于 debug 测试 provider，绝不能出现在 main manifest。

## 3. 环境与缓存（DSH 沙箱）

- 所有工具缓存都放**仓库内**、已被 ignore 的目录：
  - `.android-user/`：`android` CLI 本体 + 官方文档知识库（约 290MB）；
  - `.tooling/`：其它工具缓存（如 gh）；
  - `.verify/`：临时分析产物、下载的 APK 与源码。
- **禁止把工具缓存放 `/tmp`**。本环境的 `/tmp` 是每次命令独立的，缓存不会保留：`android docs` 会反复重下约 290MB 知识库，纯烧流量。需要可写 HOME 的命令，用：
  ```bash
  HOME=$PWD/.android-user android docs search "..."
  ```
  只对该条命令生效，不要 `export HOME`。
- `gh` 通常可直接用；若遇到 `~/.cache` 只读：`XDG_CACHE_HOME=$PWD/.tooling/gh-cache gh ...`。
- **不在本地跑 Gradle**：不在本机下载 Gradle/依赖、不本地产包。构建、测试、发布全部走 GitHub Actions。
- 读文件和搜索优先用 agent 运行时的 `read` / `grep` / `glob` 工具（输出带行号、按文件分组、超长会落盘、遵守忽略规则）；这只是运行时约定，**不是仓库限制**——`rg`（15.2.0）、`find`、`cat` 本机都可用，人类或其他 agent 随便用。删除、移动文件前先确认绝对路径。

## 4. 查 Android 官方文档（本项目的规定动作）

**不确定就查，不准凭印象。** 平台行为、API 语义、权限模型必须先落到文档或框架源码，再下结论。

### 4.1 用 `android` CLI 的本地知识库

```bash
# 搜索：先拿到 kb:// URL
HOME=$PWD/.android-user android docs search "ACTION_OPEN_DOCUMENT_TREE directory access"

# 取全文：URL 必须是 search 结果里的 kb://...
HOME=$PWD/.android-user android docs fetch \
  kb://android/training/data-storage/shared/documents-files > .verify/saf.md
```

- 知识库只接受 `kb://` 地址；`kb://android/reference/...` 这类 API 参考页常常取不到（fetch 输出为空）。
- 参考页 / 框架源码用 `curl` 落到 `.verify/` 再读：
  ```bash
  curl -sSL "https://developer.android.com/reference/android/content/Intent" -o .verify/intent.html
  curl -sS "https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/ContentProvider.java?format=TEXT" \
    | base64 -d > .verify/ContentProvider.java
  ```
- 常用入口：SAF（`training/data-storage/shared/documents-files`）、Intent flags、`DocumentsProvider`、`Send simple data to other apps`、`grant-uri-permission`。

### 4.2 结论的写法

- 引用必须能在回答里点开（markdown 链接），并区分四类证据：**官方文档事实 / AOSP 框架源码事实 / 真机取证 / 推断**。
- OEM 行为（MIUI / HyperOS / 各家 ROM）官方文档不会写：只能标成“推断”，并给出取证命令（`adb logcat`、`adb shell dumpsys activity activities`），不得写成结论。
- 文档与厂商实现冲突时以文档为准，同时说明“实际设备可能不同”。

## 5. 构建、测试与门禁

- push 到 `main` / `beta` 或开 PR → `QA`：`gradle test` → `lintRelease`（`tools/lint_summary.py` 门禁）→ `assembleRelease`（包名随分支：`main` 是 `.debug`，`beta` 自带 `.beta`）→ `tools/qa_check.py`；并行跑 `emulator-ui.yml`。
- push `v*` tag → `Release APK`：先 `needs: emulator-verify`（API 36 模拟器跑 `connectedDebugAndroidTest` + 浅/深色截图），再校验签名 Secrets、构建、体检、发布。
- 本地不产包，验证靠 CI：

  ```bash
  gh run list --limit 5
  gh run watch <run-id> --exit-status
  gh run view <run-id> --log | grep -E "构建版本|versionCode|PASS|FAIL"
  ```

- 测试计数：JVM **88** 条、API 36 instrumentation **22** 条（beta 分支；`main` 仍是 20 条）。增删测试后同步 README 与 CHANGELOG 里的数字。
- **同 UID 陷阱**：`app/src/debug` 的 `TestFolderProvider` 和被测 App 同 UID，`ContentProvider.enforceReadPermissionInner()` 对同 UID 直接放行。所以 instrumentation 绿**不代表**跨应用 URI 授权在真机成立（文件夹分享就是这样：跨应用只给单 URI 授权时必然失败）。涉及跨应用授权的行为，必须真机取证后再下结论。
- `qa_check.py` 的期望值：包名、versionName、`minSdk 24`、`targetSdk 36`、`MainActivity` exported、`FileProvider` 配置、v2/v3 签名、证书 SHA-256、无残留密钥文件。正式版四个 action 都在 `MainActivity`；beta 分支的文件入口在 `ShareActivity`（`MainActivity` 只留 `MAIN/LAUNCHER`）。beta 另传 `--expect-package moe.hellorename.beta --view-shape savecopy`。

## 6. 发布流程

**只有用户明确说“发版 / 发布 / 打个 tag”才做。** 用户说“只分析”就只分析，不动代码、不提交。

1. 改版本号（三处必须一致）：
   - `app/build.gradle.kts` 的 `appVersionName` / `appVersionCode` 默认值；
   - `.github/workflows/build.yml` 里 `CODE` 的 floor 和那行注释（覆盖安装的最低 versionCode）；
   - `CHANGELOG.md` 新增 `## [x.y.z] - YYYY-MM-DD`（`Unreleased` 提升）。
2. 提交、push `main`，等 QA 绿。
3. 打 tag 并触发发布：
   ```bash
   git tag -a v2.0.0 -m "2.0.0"
   git push origin v2.0.0
   ```
   `versionName` 由 tag 去掉 `v` 得到，`versionCode` 用 CI run number（不低于 floor）。**tag 打错 = 版本号错**，发布前先看 `git log`。
4. 等 Release workflow 绿（模拟器门禁不通过不会发布）。
5. 校验产物（下载到 `.verify/`，不要留在仓库里）：
   ```bash
   gh release download v2.0.0 -D .verify/v2.0.0
   (cd .verify/v2.0.0 && sha256sum -c SHA256SUMS.txt)
   $HOME/Android/Sdk/build-tools/36.0.0/aapt2 dump badging .verify/v2.0.0/*.apk \
     | grep -E "^package|targetSdk|uses-permission"
   $HOME/Android/Sdk/build-tools/36.0.0/apksigner verify --print-certs .verify/v2.0.0/*.apk
   ```
   期望：`versionCode` / `versionName` 与 tag 一致、`targetSdk 36`、只有 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`、证书 SHA-256 为 `3682cce6…cb6ccaca`（发布密钥，不是 debug 证书）。
6. Release notes 由 `tools/changelog_notes.py` 按 tag 从 `CHANGELOG.md` 抽取；段落标题必须精确匹配，否则会退化成“见 CHANGELOG.md”。

**release ≠ tag**：用户说“删掉 1.6.0 的 release”只删 Release（`gh release delete v1.6.0 --yes`），tag 要保留；删 tag、重写已发布历史必须先问。

## 7. 代码与 UI 约定

- 注释用中文，说明“为什么”而不是“做了什么”。
- 所有文案进 `values/strings.xml`（默认英文）和 `values-zh/strings.xml`，key 一一对应；禁止硬编码中文。
- 主题只跟随系统深浅色：白底黑字 / 黑底白字。不加自定义配色、不加开关；系统 ActionBar 必须关闭，全屏只有一个 56dp 顶栏（CI 有门禁）。
- 发送 / 保存成功后 Toast + 自动 `finish()`；chooser 用 `EXTRA_EXCLUDE_COMPONENTS` 排除自己，避免自循环。
- 纯文本/未知模式：“发送”原样转发来源 `Intent`，**不重新编码**正文；保存固定 UTF-8 无 BOM，换行原样保留（未知转储用 LF）。
- 保存 MIME 走 `chooseSaveMime` / `mimeForSaving`：当来源 MIME 的规范扩展名和实际扩展名冲突时按扩展名推断，避免 MediaStore 把 `.ini` 存成 `.ini.txt`。
- 文件名只做清洗（非法字符、bidi、C0/C1、240 UTF-8 字节截断），不改用户输入；重名交给系统。
- 多文件只能保存，不能改名/发送；文件夹分享不展开，按纯文本处理（保存/转发的就是它的 URI/路径），见 README「已知限制」。
- 缓存清理绝不能碰当前正在用的会话（`CacheState` + 交付标记），别为了“干净”扩大删除范围。

## 8. Agent 行为准则

- 先读 `README.md`（含已知限制）和 `CHANGELOG.md`，再改行为；改完同步文档与测试计数。
- 结论要有证据：贴 run id、关键日志、命令输出。不允许没有验证就说“应该没问题”。
- 不确定的 API/平台行为 → 按第 4 节查文档；查不到 → 明说“不确定”，并给可执行的取证步骤。
- 提交信息用中文 + 前缀：`fix:` / `feat:` / `test:` / `docs:` / `release:`；一次提交只做一件事。
- 不提交 APK、`*.jks`、`keystore.properties`、`local.properties`、`.verify/` 里的分析产物；`.android-user/`、`.tooling/`、`.verify/` 已在 ignore 列表里，别改。
- 不 force push、不重写已发布的 tag、不动 `main` 历史。
- 用户说“只分析 / 先别改”时：只读代码、查文档、给结论和取证命令，不动代码不提交。

## 9. 常用命令

```bash
# 文档
HOME=$PWD/.android-user android docs search "share directory SAF permission"
HOME=$PWD/.android-user android docs fetch kb://android/training/data-storage/shared/documents-files > .verify/saf.md

# CI
gh run list --limit 5
gh run watch <run-id> --exit-status
gh run view <run-id> --log | grep -E "PASS|FAIL|versionCode"

# 本地静态检查（不下载依赖）
python3 -m py_compile tools/*.py

# 发布产物校验
gh release view v2.0.0
gh release download v2.0.0 -D .verify/v2.0.0
(cd .verify/v2.0.0 && sha256sum -c SHA256SUMS.txt)
$HOME/Android/Sdk/build-tools/36.0.0/aapt2 dump badging .verify/v2.0.0/*.apk | head -5
```

## 10. 高频坑（都踩过）

- MediaStore 的 `text/plain` 只映射到 `txt`：保存时必须显式给 MIME，否则 `.ini` → `.ini.txt`。
- 文件夹分享没有平台契约：`ACTION_SEND` 默认只授予**那一个 URI**；没有 `FLAG_GRANT_PREFIX_URI_PERMISSION` 就列不出子项（`SecurityException`）。所以 2.1.0 起不再展开文件夹，统一按纯文本处理；同 UID 的 instrumentation 测不出跨应用授权差异。
- instrumentation 跑完会卸载被测 App：`emulator_ui_check.sh` 里先重装 debug APK 再截图。
- QA 构建的包名随分支：`main` 是 `moe.hellorename.debug`，`beta` 是 `moe.hellorename.beta`；`qa_check.py` 必须带 `--expect-package`，否则误报。
- 文本模式不要把正文和链接拼在一起，也不要把 CRLF 改成 LF（用户会拿它做逐字节对比）。
- 正式版：`ACTION_VIEW` 的 `*/*` filter 匹配不上 MIME 为 null 的 Intent（`IntentFilter.findMimeType(null)` 直接 false），要单独一条只声明 `content` scheme 的 filter 兜底；同一个 `<intent-filter>` 里多个 `<data>` 是“与”关系，不能合并成一条。beta 分支故意没有这条兜底（SaveCopy 形状），instrumentation 断言的是“无 MIME 不解析”——要改回去先确认不是在跑对照实验。
- beta 分支的文件入口必须在 `ShareActivity` 上，不能挪回 `MainActivity`：HyperOS/MIUI 的分享面板只展示“专用文件处理组件”（SaveCopy 的 `SaveActivity` 形态），挂在桌面 Activity 上系统能解析到但面板不列出。`fileEntries_resolveToShareActivity` 守着这条。
- 只声明 MIME 的 filter 只会匹配 `content:` / `file:`（框架的 Data test 规则 4），`http(s)` 不会被接管；`file://` 能进来但零权限读不了，按“源文件不可用”报错即可。

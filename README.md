# hellorename

**分享 → 改名 → 再分享。** 一个极简的 Android 小工具：接收其他 App 分享过来的文件，改好名字后，再把文件分享给别的 App。

> 中文系统下，应用名显示为 **改名助手**（英文/其他语系显示 `hellorename`）。

[![QA](https://github.com/llllllllqq/hellorename/actions/workflows/qa.yml/badge.svg)](https://github.com/llllllllqq/hellorename/actions/workflows/qa.yml)
[![Release APK](https://github.com/llllllllqq/hellorename/actions/workflows/build.yml/badge.svg)](https://github.com/llllllllqq/hellorename/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![minSdk](https://img.shields.io/badge/minSdk-24-brightgreen.svg)](#)

---

## 它解决什么问题

Android 系统分享面板本身没有“改名后再分享”的能力：接收方拿到的 `content://` URI 是**临时且不可转发**的授权，而且没有任何 API 能改别人的文件名（改名本质上是**换个名字重新提供同一份数据**）。

hellorename 就是干这件事的：

1. 出现在系统分享面板里，接收任意类型的文件（`ACTION_SEND` / `ACTION_SEND_MULTIPLE`）。
2. 上半部分显示**旧文件名.扩展名**（可长按复制）。
3. 下半部分是一个**自动换行的大输入框**，直接改新文件名。
4. **扩展名默认锁定**（用 `TextInputLayout` 的 suffix 展示，光标进不去）；勾选最底部的“允许修改扩展名”后才能一起改。
5. 输入框右边一个**大发送按钮**，把改名后的文件再次丢进系统分享面板。

## 设计取舍（为什么这么实现）

| 决策 | 原因 |
| --- | --- |
| 标准前台 Activity，**不用悬浮窗、不占通知、不申请任何系统权限** | 悬浮窗在国产 ROM 上要额外授权、易被拦截；`SYSTEM_ALERT_WINDOW` 权限一旦被回收功能就废了。前台 Activity 走系统分享入口，行为最可预期。 |
| 收到文件后**立刻**复制到 `cacheDir/shared/<会话>/_incoming.<ext>` | 源 URI 的读授权是**一次性的、不可传递**的，且源 App 随时可能被系统杀掉。越早复制越可靠，之后改名、分享都不再依赖源 App。 |
| 改名时只是**同目录 rename**，不再复制第二份 | 一份数据从头到尾只存在一份：`_incoming.png` → `我的新名字.png` 是同一文件系统内的瞬时操作，零字节拷贝。 |
| 用自己的 **FileProvider** 对外提供文件 | `cacheDir/shared/<会话>/<新文件名>` 的**真实磁盘文件名就是对外报出的 `DISPLAY_NAME`**，不需要写自定义 ContentProvider 去伪造名字。 |
| 扩展名用 `suffixText` 而不是 TextWatcher 硬顶 | 输入框里始终只有“基础名”，用户可以在名字里随便用点号（`my.file.v2` 不会被误切），扩展名视觉上灰着、改不动，勾选后自动拼回输入框变成可编辑。 |
| 复制优先走 `FileChannel.transferTo`，失败退回流复制 | 大部分 Provider 给的是普通 fd，零拷贝最快；少数 Provider 给的是 pipe，会退回 64 KiB 缓冲的流复制，兼容性兜底。 |

### 会不会中途多留一份副本？

会，**只留这一份**：源文件在对方 App 里（或对方进程的内存/管道里），你不可能原地改名，也不可能把临时授权转给下一个 App，所以复制到自己的缓存是唯一稳妥的做法。

理论上还有“不复制”的方案——写一个自定义 `ContentProvider`，`query()` 报新名字、`openFile()` 现场把源 URI 的数据流转发出去。但它依赖**源 App 的进程一直活着、授权一直有效**，在国产 ROM 的后台清理下非常容易失败；而且分享面板弹出后你的 Activity 可能已经退栈，失败就发生在用户看不到的地方。所以本项目不做这个方案。

缓存文件放在 `cacheDir/shared/`，系统可以回收；App 每次启动还会顺手清理 1 小时前的旧会话目录。

## 下载

最新的 APK 在 [Releases](https://github.com/llllllllqq/hellorename/releases/latest) 页面（release 变体，已用项目自己的密钥签名，可直接安装）。

版本号约定：`versionName` 来自 tag（去掉 `v`），`versionCode` 用 CI 的 run number（单调递增，可直接覆盖安装升级）。

## 使用

```
任意 App → 分享 → hellorename → 改名字 → 发送 → 系统分享面板 → 目标 App
```

- 从启动器直接打开时，可以点“选择一个文件”（走 SAF），方便自测。
- 支持多文件分享，但当前版本只处理第 1 个（界面会提示收到几个）。
- 只收到纯文本（`EXTRA_TEXT`）而不是文件时会明确提示，不会假装成功。

## 构建

### 云端（推荐，本仓库就是这么发的包）

- 推到 `main` / 开 PR → `QA` workflow：单元测试 → Lint 门禁 → 构建 release APK → APK 出厂体检。QA 产物带 `.debug` 包名后缀（`moe.hellorename.debug`），可以和正式包同时装在手机上。
- 打 `v*` tag → `Release APK` workflow：要求签名 Secrets → 构建 → 体检 → 创建 Release（附 APK 与 `SHA256SUMS.txt`，说明自动取自 `CHANGELOG.md`）。

### 出厂前自动体检（`tools/qa_check.py`）

每次 CI 都会对产出的 APK 做一遍硬性检查，任何一项失败就不发版：

- 包名 `moe.hellorename`、版本号与 tag 一致、`minSdk 24`、`targetSdk 34`
- 除了 androidx 自用的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（`signature` 级、不向外部应用授权）外**没有任何权限**，一旦多出 `INTERNET`/存储之类就失败
- `application-debuggable` 不存在
- `MainActivity` `exported=true`，注册了 `MAIN/LAUNCHER`、`SEND`、`SEND_MULTIPLE`，`launchMode=singleTop`
- `FileProvider` `exported=false` + `grantUriPermissions=true` + authority 与 `file_paths.xml` 到位
- APK Signature v2 / v3 校验通过，且证书 SHA-256 等于发布密钥（不是 Android Debug 证书）
- APK 内不得残留 `*.jks / keystore.properties / local.properties`

本地也能跑：

```bash
python3 tools/qa_check.py app/build/outputs/apk/release/app-release.apk \
  --expect-cert-sha256 3682cce6d8fbf859b9b0a90ef8818acc747c82bb5f9d97418a4e6749cb6ccaca
```

（没装 build-tools 时会跳过依赖 `aapt2`/`apksigner` 的检查并给出 WARN。）

### 签名

签名密钥**不在仓库里**，通过 GitHub Actions Secrets 注入：

| Secret | 内容 |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | 密钥库文件的 base64（单行） |
| `SIGNING_STORE_PASSWORD` | 密钥库口令 |
| `SIGNING_KEY_ALIAS` | key alias |
| `SIGNING_KEY_PASSWORD` | key 口令 |

生成密钥库：

```bash
keytool -genkeypair -v -keystore hellorename-release.jks -alias hellorename \
  -keyalg RSA -keysize 4096 -validity 10950 -storetype PKCS12 \
  -storepass '<PASS>' -keypass '<PASS>' \
  -dname "CN=hellorename, OU=Android, O=hellorename, C=CN"
base64 -w0 hellorename-release.jks   # 粘到 SIGNING_KEYSTORE_BASE64
```

未配置这些 Secrets 时，release 会自动退回 debug 签名，CI 依旧能出包（仅供自测）。

### 本地

```bash
cp keystore.properties.example keystore.properties   # 填自己的路径与口令，已被 gitignore
gradle assembleRelease                               # 或 ./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

环境：JDK 17、Gradle 8.9、AGP 8.5.2、Kotlin 2.0.21、compileSdk 34、minSdk 24。单元测试：

```bash
gradle test          # FileNameUtils 的纯逻辑测试
```

## 隐私

- 没有网络代码，也没有申请任何系统权限（`INTERNET`、存储、悬浮窗等一概没有）。APK 里只剩 androidx 自带的 `moe.hellorename.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，它是 `signature` 级的应用内自用权限，不会授予外部应用，也不出现在安装权限列表里。
- 文件只在本地缓存目录里复制、改名；除了你自己选的目标 App，不会发给任何第三方。

## 已知限制

- 改扩展名会同时改变对外声明的 MIME type，部分 App 会因此拒收（界面有提示）。
- 多文件分享只处理第一个。
- 文件名中的 `/ : * ? " < > |` 等非法字符会被替换成 `_`，结尾的点会被去掉（避免 `../` 之类的路径穿越）。
- 文案默认英文，中文放在 `values-zh`（所有中文语系都会命中）。
- 分享面板弹出后你没法知道对方何时读完文件，所以临时文件靠“下次启动清理 1 小时前的会话”回收。

## License

[MIT](LICENSE)

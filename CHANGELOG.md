# Changelog

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)；每个 `v*` tag 会由 GitHub Actions 自动构建并发布 APK。

## [1.1.0] - 2026-09-24

### Added
- **「保存到下载」按钮**，与「发送」并排在输入框下方：把改名后的文件一键存进系统下载目录。
  - Android 10+ 走 `MediaStore`（零权限、无弹窗、重名由系统自动编号）；Android 7–9 走 SAF `ACTION_CREATE_DOCUMENT`（零权限，26+ 会尝试把选择器定位到下载目录）。
  - 与「发送」共用同一套改名逻辑（`prepareFinalFile()`），保证保存的就是输入框里的名字，且保存过的路径同样登记为“已交付”，后续改名不会再 rename 破坏对方正在读的文件。
- **底部警示语**：提醒用户发送后在分享面板选择其它应用、勿再选本应用，避免无限循环。
- 发送 / 保存成功后 **Toast 提示并自动关闭主界面**（失败不关闭，可重试）。

### Fixed
- **复制进度文案卡在「100%」不显示「复制完成」**：被取消的复制会话（重新分享、复制中连点发送）里 IO 循环仍会继续吐进度回调，把新会话的最终状态覆盖掉。现在 `startCopy` 先取消上一次复制并递增会话号，旧会话/已出结果会话的迟到回调一律丢弃；复制循环同时支持协作式取消，不再产生僵尸 IO。

### Changed
- 「发送」按钮从输入框右侧移入输入框下方的按钮行，与「保存到下载」并排；无文件时两按钮均禁用。

## [1.0.2] - 2026-09-24

### Changed
- **未勾选「允许修改扩展名」时彻底隐藏扩展名**：输入框里只显示基础名，连 `TextInputLayout` 的灰色 suffix 也不再展示；保留的扩展名改由输入框下方的提示行写明（如 `扩展名锁定为 .png`），中英文文案同步。
- README 功能清单与设计取舍表同步该行为。

## [1.0.1] - 2026-09-22

投产前审计版本。

### Fixed
- **静默截断**：`FileChannel.transferTo` 允许少传甚至返回 0，原先只看“传过字节”就判定成功。现在用 `statSize` 校验字节数，对不上就丢弃整份改走 64 KiB 流复制，避免大文件被悄悄截断。
- **已分享的 URI 被改名弄失效**：分享面板弹出后目标应用可能还在异步读取。现在记录已交付出去的路径，重复改名改为复制而不是 rename，不再打破对方正在读的 URI。
- 文件名显示与解析不一致（display name 为空时回退值不同步）。

### Added
- 复制进度反馈：能拿到大小时显示百分比，拿不到时按 MB 显示已复制量。
- `FileNameUtils` 纯逻辑模块 + 27 个单元测试（非法字符、路径穿越 `../`、超长名、扩展名规范化、点开头文件等）。
- `tools/qa_check.py`：APK 出厂体检（包名/版本、minSdk 24、targetSdk 34、**无多余权限**（仅 androidx 自用 signature 级）、非 debuggable、FileProvider 不导出但可授权、MAIN/SEND/SEND_MULTIPLE 注册、签名方案与证书指纹、APK 内不得有密钥文件）。
- `tools/lint_summary.py`：Android Lint 报告摘要，Error/Fatal 作为质量门禁。
- `tools/changelog_notes.py`：自动用本文件生成 Release notes。
- QA workflow：单元测试 → lint 门禁 → 构建 release APK → APK 体检。
- 版本号约定：`versionName` 取 tag（去掉 `v`），`versionCode` 取 CI 的 `GITHUB_RUN_NUMBER`（单调递增）。

### Changed
- 文案改为「默认英文 + `values-zh` 中文」，非中文语系设备不再看到中文兜底。
- Release workflow 只在打 tag / 手动触发时运行，并强制要求签名 Secrets。

## [1.0.0] - 2026-09-22

首个版本（审计前的快照，已被 1.0.1 取代）。

### Added
- 作为分享接收方接收任意类型文件（`ACTION_SEND` / `ACTION_SEND_MULTIPLE`）。
- 上半部分显示旧文件名.扩展名；下半部分多行自动换行输入框改新文件名。
- 扩展名默认锁定（`TextInputLayout` suffix），勾选“允许修改扩展名”后解锁。
- 输入框右侧大发送按钮，通过系统分享面板再用自己的 `FileProvider` 分享出去。
- 收到文件立刻以 `FileChannel.transferTo` 复制到 `cacheDir/shared/<会话>/`，改名只做同目录 rename。
- 零权限、标准前台 Activity，无悬浮窗、无通知、无后台服务。

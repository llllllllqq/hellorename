# Changelog

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)；每个 `v*` tag 会由 GitHub Actions 自动构建并发布 APK。

## [1.5.0] - 2026-10-28

复制完成后显示文件校验值，方便用户自行和源文件对比，验证传输/写盘后内容是否一致。

### Added
- **MD5 / SHA-1 / SHA-256 展示**：文件复制完成后，在输入框/按钮下方显示三个小写十六进制校验值；支持长按选择，以及“Copy all”一键复制三行。
- **复制后回读计算**：校验值是对 `cacheDir/shared/<会话>/` 里的最终缓存文件做一次只读回读得到的，而不是边复制边算。这样显示的是真正会被分享/保存的那份文件，写盘阶段若发生损坏也能通过和源文件对比发现。
- **计算进度**：回读期间复用现有进度条，显示“Calculating checksums… %”；期间禁用发送/保存，避免在拿到校验值前交付。
- **状态恢复**：MD5/SHA-1/SHA-256 进入 `onSaveInstanceState`，深浅色/字体/语言重建后保留；新分享/重置/缓存失效时清空。
- **失败可重试**：回读失败显示“Could not calculate checksums. Tap Send to retry.”，不展示半套值，也不会把未完成校验的文件当成 ready。

### Changed
- **版本号**：`versionName` 1.5.0，`versionCode` 最低 13（可覆盖 v1.4.0 的 12）。
- **测试**：新增 `FileDigestUtilsTest`（已知向量、空文件、二进制、进度、取消、十六进制填充），JVM 单测增加到 78 条；`MainActivityFlowTest` 增加“校验值展示 + 重建后保留”用例，API 36 模拟器门禁共 8 条 instrumentation。

### Notes
- 回读是一次额外的完整读盘；大文件期间界面仍可响应，但发送/保存要等校验值完成。
- MD5/SHA-1 仅用于兼容旧工具和快速对比，完整性/安全性优先看 SHA-256。

## [1.4.0] - 2026-10-18

构建工具链升级到 AGP 9 / Gradle 9 / Kotlin 2.4，并把 `compileSdk` / `targetSdk` 升到 **36（Android 16）**。这是一个 minor 版本：targetSdk 36 带来强制 edge-to-edge，界面适配是本次的主要功能性改动。

### Changed（工具链）
- **AGP 8.5.2 → 9.4.1**，**Gradle 8.9 → 9.6.0**，**Kotlin 2.0.21 → 2.4.21**。
- 按 AGP 9 的 **built-in Kotlin** 迁移：移除 `org.jetbrains.kotlin.android` 插件；在根 `build.gradle.kts` 的 `buildscript` classpath 中显式指定 KGP 2.4.21，避免沿用 AGP 内置的旧 KGP。
- release 构建改用 AGP 9.3+ 的 `optimization { enable = false }` DSL，保持当前“不混淆、不 shrink”的行为。
- AndroidX 依赖升级到当前 stable：core-ktx 1.18.0、appcompat 1.8.0、activity 1.13.0、lifecycle 2.11.0、material 1.14.0、coroutines 1.11.0；AndroidX Test 升到 runner 1.7.0 / ext-junit 1.3.0 / espresso 3.7.0。
- `compileSdk = 36`、`targetSdk = 36`，`minSdk 24` 不变；Build Tools 使用 AGP 9.4 默认的 36.0.0。

### Changed（Android 16 行为）
- **强制 edge-to-edge**：targetSdk 36 在 Android 16 上不能再通过 `windowOptOutEdgeToEdgeEnforcement` 退出。`MainActivity` 对 API 35+ 调用 `WindowCompat.setDecorFitsSystemWindows(window, false)`，用 `WindowInsets` 给根布局加系统栏/刘海/IME padding；Toolbar 自身仍保持 56dp，状态栏区域由根布局让出。
- 状态栏/导航栏图标明暗改用 `WindowInsetsControllerCompat` 跟随深浅色；API 34 及以下保留原有 `statusBarColor` + 系统栏行为。
- 没有重写返回键，因此 Android 16 的 predictive back 默认行为不需要额外迁移；布局没有锁定方向/宽高比，大屏忽略 `screenOrientation`/`resizableActivity` 限制不会造成额外问题（已有的状态恢复继续覆盖重建）。
- 未使用 native 库，不涉及 16 KB page size；未使用健康、蓝牙或 `ScheduledExecutorService` 固定频率任务。

### Changed（验证）
- Emulator 门禁从 **API 34 升到 API 36**，只保留 API 36 主门禁；`ThemeUiTest` 改为 edge-to-edge aware（Toolbar top = 根布局状态栏 padding，而不是 0）。
- `tools/qa_check.py` 的 targetSdk 期望改为 36。
- release `versionCode` 最低值提高到 12，确保能覆盖 v1.3.2 的 11。

## [1.3.2] - 2026-10-18

底层逻辑集中修复：生命周期/状态恢复、保存竞态、缓存交付标记、主线程 Provider 查询、MediaStore/SAF 异常路径、文件名与 Intent 健壮性。全部修复都有对应的 JVM 单测或 API 34 模拟器 instrumentation 覆盖。

### Fixed（生命周期与状态）
- **H1 深浅色/字体/语言切换后丢状态**：Activity 重建时通过 `onSaveInstanceState` 保存并恢复源 URI、MIME、原文件名、缓存会话/当前文件、复制状态、输入框内容、扩展名锁定、已交付路径和 SAF 待保存文件。复制未完成会自动重试；已完成的文件不会重新复制。
- **H11 SAF 待保存文件在重建后丢失**：`savePendingFile` 进入保存状态，重建后仍能接住 `ACTION_CREATE_DOCUMENT` 的回调，不再把保存静默变成“已取消”。
- **L6/L7 MAIN/纯文本 Intent 与后台启动**：已有文件时收到 `ACTION_MAIN` 或纯文本分享不再清空界面；prepare 完成时如果界面已经 `onStop`，不会从后台启动分享面板/SAF，而是提示回到应用重试。
- **H8 `showEmptyState` 不失效旧任务**：重置/新入口统一递增操作代际并取消复制、prepare、MediaStore 保存和 SAF 写入；旧任务回到主线程后不再改 UI、不再 finish。

### Fixed（保存竞态与错误处理）
- **H2 保存中收到新分享会关掉新界面**：MediaStore/SAF 保存都捕获自己的操作代际，新分享后旧任务只清理自己的半成品，不再 `finish` 新界面、不再弹旧 Toast。
- **H5 MediaStore 异常/孤儿条目**：`insert`、`openOutputStream`、写入、`update(IS_PENDING=0)` 全部捕获；`update` 返回 `<= 0` 视为失败，删除 pending 条目；整个保存流程不会因 Provider 异常崩溃。
- **H6 SAF `wt` 部分写入后回退 `w`**：只有**一个字节都没写进去**时才回退 `"w"`；已经写了部分数据时不再盲目重写，失败后尝试 `deleteDocument`/`"wt"` 截断，并明确提示目标文件可能不完整；成功时校验写入字节数。

### Fixed（缓存与交付标记）
- **H7 交付标记在分享之后写**：改为**先写标记再启动分享面板**；`startActivity` 失败会回滚标记；标记写不进去时不启动分享，避免把死链交给目标 App。
- **H3 清理误删新会话标记**：`cleanup` 只 prune 本次真正列到过、且确实已经删除的会话标记；清理开始后新建的会话标记不会被误删。`listFiles()` 返回 null 时直接跳过 prune，不再把“读不出来”当成空目录。清理加进程内互斥，避免两个强制清理并发 prune。
- **H12 会话目录撞名**：会话名改为“时间戳 + 会话号 + UUID”，时钟回拨/进程重启/同毫秒都不会复用旧目录；目录已存在时重新生成，不再复用。
- **H9 后台未交付缓存永远不清理**：`onStop` 后给 30 分钟宽限，期间返回仍受保护；30 分钟后且没有复制/保存在跑时撤掉活会话登记，让 10 分钟未交付 TTL 生效。返回时如果缓存文件已被清理，会自动重试复制。
- **L9 移除无用的持久化 URI 授权**：不再对每次 SAF 选择的文件调用 `takePersistableUriPermission`，避免长期累积授权。

### Fixed（复制与空间）
- **H4 主线程查询 ContentProvider**：`getType`/`query` 移到 IO 线程，界面先显示“正在读取文件”；`mkdirs` 也移出主线程。慢/恶意 Provider 不会再直接 ANR。
- **H10 32MB 固定预留拒绝小文件**：预留量改为 `min(32MB, 剩余空间/4)`，只剩 20MB 的设备也能复制 1KB 文件。
- **L4 未知大小流无上限**：未知大小（或报 0）的流最多复制 `min(2GB, 可写空间 - 动态预留)`；超限时删除半成品并明确提示，不会无限填满磁盘。
- **复制路径健壮性**：`statSize == 0` 不再走“空文件成功”快路径；`statSize < 0` 复用已经打开的 PFD；读写 `0` 字节时让出而不是忙等；同目录 `copyFile` 改用 `copyTo` 并校验字节数，避免 `transferTo` 瞬时 0 导致改名失败。

### Fixed（文件名 / Intent / 其它）
- **L1 文件名劈开 emoji 代理对**：先按 code point 再按 UTF-8 字节截断，ASCII 前缀 + emoji 不再产生 `?`；Toast 文件名也按 code point 省略。
- **L2 控制字符 / 双向控制符**：C0/C1 控制字符替换为 `_`，bidi 控制符（U+061C、U+200E/200F、U+202A–202E、U+2066–2069）直接移除，避免文件名伪装。
- **L5 `EXTRA_STREAM` 类型不安全**：支持 `Uri`、`ArrayList<Uri>`、`Parcelable[]`，其他 Parcelable/坏数据按“没有文件”处理，不再 `ClassCastException`/`BadParcelableException` 崩溃；`clipData` 解析也做了兜底。
- **L3 分享面板包含自己**：chooser 通过 `EXTRA_EXCLUDE_COMPONENTS` 排除 HelloRename，底部警示语保留作为双保险。
- **L8 空间不足文案识别**：补充 `not enough space`、`disk full`、`quota`、`insufficient storage`、`storage full`。
- **时钟回拨导致清理永久节流**：`CacheState.shouldCleanup` 检测到 `now < lastCleanupAt` 时重置基准并放行一次。

### Tests
- JVM 单测增加到 **71 条**：新增 `StreamCopierTest`、`OperationRegistryTest`，并补充 `CacheCleanerTest`（prune 竞态、`listFiles` 为 null、原子标记、UUID、动态预留、未知流上限、时钟回拨、`isNoSpace`）与 `FileNameUtilsTest`（代理对、控制符、bidi、Toast 省略）。
- API 34 模拟器新增 `MainActivityFlowTest`：分享复制、重建后恢复输入/旧文件名、`ACTION_MAIN` 不清空、异常 `EXTRA_STREAM` 不崩溃、`Parcelable[]` 形式仍可用；与 `ThemeUiTest` 一起作为 QA/Release 的模拟器门禁。

## [1.3.1] - 2026-10-18

修复 v1.3.0 的重复标题，并把云端模拟器 UI 验证设为发布前的硬门槛。

### Fixed
- **界面出现两个标题**：v1.3.0 给 Activity 加了自绘 56dp Toolbar，但主题仍继承 `Theme.Material3.DayNight`（自带系统 ActionBar），于是系统标题栏和自绘顶栏各显示一次应用名。现在改为 `Theme.Material3.DayNight.NoActionBar`，只保留与页面同色的 56dp 顶栏。

### Added
- **云端模拟器 UI 门禁**：新增 `.github/workflows/emulator-ui.yml`，由 QA 与 Release 复用。在 GitHub Actions 的 API 34 模拟器上运行 instrumentation 测试，断言：
  - `supportActionBar` 必须为 null，且全屏只有一个应用名标题；
  - 顶栏紧贴内容区顶部，高度恰好 56dp；
  - 浅色模式白底黑字、深色模式黑底白字。
- 验证通过后额外截取浅色 / 深色两张截图和 UI 层级，作为 Actions artifact 留存。
- **Release workflow 以模拟器验证为前置依赖**：`release` job 通过 `needs: emulator-verify` 等待模拟器门禁；门禁失败时不会构建、更不会发布。

## [1.3.0] - 2026-10-18

界面与命名更新：英文应用名改为 **HelloRename**；应用内跟随系统深色 / 浅色模式自动切换，只保留黑白两套默认配色。

### Added
- **56dp 顶部应用栏**：标题显示当前语系的应用名，顶栏与页面同色，不额外加分割线或阴影（与 hellowidget 首页的默认设计一致）。
- 状态栏颜色与顶栏一致，状态栏图标（时间 / 电量）在浅色模式下用深色、深色模式下用浅色。

### Changed
- **英文应用名**从 `hellorename` 改为首字母大写的 **HelloRename**（中文名仍为「改名助手」）。
- **深色 / 浅色模式自动切换**：页面、顶栏、按钮、输入框、勾选框统一使用黑白配色 —— 浅色白底黑字，深色黑底白字；不提供用户自定义颜色的入口，只保留这两套默认配色。
- 移除 Material 3 默认的品牌蓝强调色：主要按钮、输入框描边、勾选框等一并改为黑 / 白，避免深浅色模式下残留彩色元素。

### Fixed
- **深色模式切换后界面不跟着变色**：`MainActivity` 原来在 `configChanges` 里声明了 `uiMode`，系统切换深浅色时 Activity 不会重建，已加载的界面继续使用旧配色。现在移除该声明，由系统正常重建 Activity 并应用 `values` / `values-night` 中对应的颜色资源。

## [1.2.0] - 2026-09-25

缓存自动清理重做：原来的实现只在冷启动清一次、按「目录 mtime 早于 1 小时」判断，而目录 mtime 既不代表「最后使用」，也不认识「谁还在用」，既可能删掉别人正在读的文件，又可能在主线程卡住启动。

### Fixed
- **清理在主线程做磁盘删除**：`onCreate()` 里同步 `deleteRecursively()`，攒了几个超过 TTL 的大文件就会卡冷启动甚至 ANR。现在整条清理链路都在 IO 线程。
- **清理不认识「正在使用的会话」**：旧实现完全不看当前会话，只靠「onCreate 里先清理、再建会话目录」这个**调用顺序**侥幸安全；`singleTop` 复用下的第二个界面实例、或任何新增的清理调用点，都会删掉别人正在用的目录（之后重试还会因为一次性读授权已失效而彻底失败）。现在会话在创建时登记进进程级注册表（`CacheState`），清理一律带排除集。
- **「已交付不再删」的保护只活在内存里**：`handedOutPaths` 一重启就没了，而清理恰恰发生在下一次冷启动。现在交付成功时会把交付时刻落盘到 `cacheDir/shared-state/<会话>`，跨进程有效。
- **TTL 的判断依据是错的**：原来用会话目录自身的 mtime，而目录 mtime 不反映「往文件里写内容」（慢速 Provider 的大文件复制期间一直停在开始时刻，会被当成旧会话删掉），也不反映第三方 App 的读取。现在取「目录 mtime」与「目录内最新文件 mtime」的较大者，已交付会话另用交付时刻。
- **清理只在 `onCreate` 触发**：界面被 `singleTop` 复用时不会再走 `onCreate`，长期存活的进程里缓存可以无限涨。现在冷启动（强制）+ `onNewIntent` + 新建复制会话三处触发，后两者按 10 分钟节流。
- **mtime 读不到（0）时会把整个缓存清空**：现在明确区分「未知」语义——未交付按「该清了」处理，已交付按「不敢删」处理。
- **空间不足只报「复制失败」**：复制前用 `OpenableColumns.SIZE` 预检空间（不够就先强行清一次旧会话再复核），仍然不够时明确提示「存储空间不足」；写入过程中撞上 `ENOSPC` 也会被识别（含藏在 cause 里的），不再让用户以为是自己文件的问题。
  - 判断「放不放得下」在 API 26+ 走 `StorageManager.getAllocatableBytes()`：算上系统可清理的缓存，避免“缓存很多、剩余空间紧张”时误报空间不足（只查询，不 `allocateBytes`）；老系统与查询失败时退回 `usableSpace`。
- **会话目录名可能碰撞**：目录名原来只有毫秒时间戳，同一毫秒内的两次复制会命中同一个已存在目录并共用 `_incoming<ext>`，后一次覆盖前一次（可能正被对方 App 读）。现在名字带会话号。

### Changed
- **未交付会话不再白留 1 小时**：过了 10 分钟就清掉，用户取消/复制失败不再长期留下明文副本。
- **已交付会话从 1 小时延长到 24 小时**：给接收方 App 留足读取时间（`DELIVERED_TTL_MS`）。
- 交付标记放在 `cacheDir/shared-state/`，FileProvider 暴露的 `shared/` 下仍然只有会话目录，状态文件不会被暴露。
- 清理结果会打一行 `hellorename` tag 的 log（删除数/释放字节/保留数/失败数），旧实现把一切都吞掉，线上出问题无从下手。
- 清理规则与磁盘层从 Activity 抽到 `CacheCleaner` / `CacheStore`（纯 `java.io`，不依赖任何 Android API），新增 `CacheCleanerTest` 21 条单元测试，把「活会话不删」「已交付保温」「目录 mtime ≠ 最后使用」「空间预检」「节流」「`shared/` 与 `file_paths.xml` 的一致性」都钉住。

## [1.1.1] - 2026-09-24

### Fixed
- **长中文名改名必然失败**：`MAX_NAME_LENGTH` 原来是 150 **字符**，而 `ext4/f2fs` 的单个文件名上限是 255 **字节**（一个汉字占 3 字节），86 个汉字以上就会 `rename` 失败。现在按 UTF-8 **字节数**截断（上限 240 字节，留出系统追加后缀的余量），并按字符边界回退，绝不会把汉字/emoji 劈成半个；扩展名始终完整保留。
- **复制失败后界面变成死路**：失败时两个按钮都被禁用，状态栏却提示“点发送重试”，根本点不了。现在失败后按钮保持可点，确实可以点“发送/保存”重新复制；建目录失败同理。
- **改名会卡主线程**：已交付过的文件再次改名走的是整份 `copyFile`，之前跑在主线程上，大文件会 ANR；而且「已交付」是在点按钮时登记的，SAF 取消保存这种“什么都没交付”的情况也会误登记。现在改名（含复制）整体派发到 IO 线程，只有真正交付成功才登记路径。
- **取消 SAF 保存后状态栏一直停在“正在保存到下载…”**：现在恢复按钮并提示“已取消保存”。
- **`statSize` 未知时的静默截断**：pipe 型 Provider 拿不到文件大小时，原先只要传过字节就判定成功。现在这种情况直接走流复制（读到 EOF 才算完成），同目录的 `copyFile` 也补上了字节数校验。
- **临时文件名直接拼接原始扩展名**：`_incoming + originalExt` 会把对方应用给的长/含 `/ \ :` 的扩展名原样带进路径，现在先经 `FileNameUtils.sanitizeExt` 规范化。

### Changed
- **Toast 适配 Android 12+ 的两行限制**（超出会被系统直接截断）：发送提示压到两行以内并带上应用名；保存提示里的文件名过长时保留首尾、中间省略，尾部能保住扩展名。
- Android 7–9 的下载目录定位改用 `DocumentsContract.buildDocumentUri()`；写入先试 `"wt"`，个别第三方 DocumentsProvider 不支持时退回 `"w"`。
- `status_rename_failed` 文案改为提示“名字可能太长”（非法字符其实会被自动替换成 `_`）。
- 新增 `FileNameUtils.truncateUtf8` 与 5 个单元测试（长中文名、emoji、字节边界截断）。

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

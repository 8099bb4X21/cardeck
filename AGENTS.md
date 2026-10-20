# AGENTS.md — 给 AI 编码助手的项目指引

让一台未 root 的 Android 手机（荣耀 Magic7 Pro）扮演 **CarPlay 发送端（iPhone 角色）**：
自绘车机桌面 → H.264 编码推流 → 奥迪车机 / DiPlay 测试台（Redmi K40 上的接收端）显示，
车机触摸经 Shizuku（shell 权限）回注虚拟屏。协议链路：蓝牙 iAP2 → 车机热点 → mDNS →
AirPlay（配对/控制/事件/流）→ 视频 + 音频推流。

## 常用命令

```bash
./gradlew assembleDebug                 # 构建
./gradlew testDebugUnitTest             # 单元测试（协议层/编解码为主）
./gradlew :app:lintDebug                # Android Lint（存量问题在基线，新问题会失败）
./gradlew spotlessApply                 # 代码格式化（ktlint official 风格，规则在 .editorconfig）
adb -s <serial> shell am force-stop io.github.shaonialife.cardeck   # 安装前必须先杀应用
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

**安装流程（重要）**：会话进行中直接 `adb install` 时，系统安装确认弹窗会落到车机侧
虚拟屏上（无人点得到，表现为 User rejected / 卡住）。**必须先 force-stop 再 install**；
若仍被拒，先 `input keyevent KEYCODE_WAKEUP` 亮屏再装——**熄屏时确认弹窗无人应答会被
系统自动拒绝**（2026-10-15 实测三次 INSTALL_FAILED_ABORTED，亮屏后即成）。

**装过 release 包之后 debug 包装不上**（2026-10-18）：`app-debug.apk` 用 debug 证书、
release 包用正式 keystore，`adb install -r` 会报
`INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`。同一台机器上要换类型只能
先 `adb uninstall`（会丢应用数据/Shizuku 授权），或直接改用
`./gradlew :app:assembleRelease` 的产物验证——**推荐后者**，签名一致、可覆盖安装。
注意 release 包不可 debuggable，`run-as ... tail files/main_debug.log` 会报
`package not debuggable`；日志验证要另走 debug 包或临时开 debuggable。

**卸载应用不会释放虚拟屏**（2026-10-18 实测，比上面更隐蔽）：虚拟屏由 Shizuku 的
UserService 子进程持有，**父应用卸载后该进程仍存活**，4 块屏（CarPlayDock/App/AppHi/Map）
继续占位。此时再 `adb install`，确认弹窗仍会落到这些屏上，表现为「安装卡死」，
而且因为应用已卸载、**无法靠「启动应用自动清理残留」补救**（那条路径需要应用存在）。
更糟的是每次尝试安装都会新叠一个 `PackageInstallerActivity`（实测堆积 11 个）。
处置（按顺序）：
1. `adb shell ps -A -o PID,NAME | grep car_input` 找到 `:<包名后缀>` 的 UserService 进程
   （名形如 `io.github.shaonialife.cardeck:car_input`，UID 为 shell）；
2. `adb shell kill <PID>` —— 进程退出，虚拟屏立即全部释放（实测 kill 后
   `dumpsys display | grep CarPlay` 为空）；
3. `adb shell am force-stop com.android.packageinstaller` 清掉堆积的安装弹窗；
4. 再 `adb install`。
（`adb install --user 0` / `-g` 等参数**绕不过** Honor 的安装确认弹窗，别指望静默安装。）
残留屏数量与是否为「上次会话遗留」可用 `dumpsys display | grep -oE '"CarPlay[A-Za-z]+", displayId [0-9]+'`
快速核对。

**发布（GitHub Release）**：
```bash
# 1) 改 app/build.gradle.kts 的 appVersionName（versionCode 自动推导）
# 2) 用正式密钥构建（需仓库根目录有 keystore.properties + .jks，均已 gitignore）
./gradlew :app:assembleRelease
# 3) 验证签名的 CN 不是 "Android Debug"（必须是自己的证书，见下）
$ANDROID_HOME/build-tools/<ver>/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
# 4) 打 tag 并发布（APK 重命名成 CarDeck-<版本>.apk，备注里写 SHA-256 与签名指纹）
git tag -a v0.1.0 -m "CarDeck 0.1.0"; git push origin v0.1.0
gh release create v0.1.0 CarDeck-0.1.0.apk --title "..." --notes-file notes.md --latest
```
**签名是不可逆决定**：debug 证书签的包一经发出，以后换正式证书时已安装用户必须
卸载重装（证书不同无法覆盖安装）。所以**首个公开版本就必须用正式 keystore**。
keystore 与密码只存本地/密码管理器：私钥泄露 = 任何人可签发覆盖安装的更新；
私钥丢失 = 已安装用户再也收不到更新。证书 SHA-256 指纹写进 Release 备注，
便于用户核对下载完整性。

**提交**：pre-commit 钩子（`.githooks/pre-commit`，经 `core.hooksPath` 启用；克隆后需
执行一次 `git config core.hooksPath .githooks`）会自动格式化暂存的 kt/kts 并跑
`lintDebug`，失败即拦截。存量 lint 问题豁免在 `app/lint-baseline.xml`——新代码引入
问题会直接失败；实际修复后条目自动失效，有意豁免经评审后 `./gradlew :app:updateLintBaseline`。
协作约定：功能完成后**装机给用户验证，用户说「提交」才 git commit**。

**作者邮箱（公开仓库，硬性要求）**：本仓库已公开（github.com/shaonialife/cardeck），
提交里的作者/提交者邮箱**只允许 `*@users.noreply.github.com`**
（`16135960+shaonialife@users.noreply.github.com`）。**绝不能出现公司/私人邮箱**——
提交推送后无法真正撤回，GitHub 会永久索引，搜索引擎也能关联到你的雇主。
四层防护（均已实测）：
1. **仓库局部 `.git/config`** 已设 noreply 邮箱，覆盖全局 `~/.gitconfig` 里的公司邮箱
   （根因就是全局配置是公司邮箱，任何新仓库都会默认带上）；
2. **`pre-commit` 守门**：`user.email` 不在白名单直接拒绝提交；
3. **`pre-push` 扫描**：检查**所有待推送提交**的 `%ae`/`%ce`，非白名单即拒绝推送。
   这条覆盖 pre-commit 挡不住的情况——`--no-verify`、cherry-pick/rebase 沿用原提交
   作者、从其它仓库 pull 进来的历史；
4. 必要时 `git filter-branch --env-filter` 改写历史（已对全部提交执行过一次）。

**踩坑记录**：`git reflog expire --expire=now --all` 会**连带清掉 stash 的 reflog**，
使 `git stash list` 变空（`refs/stash` 引用与内容其实还在，只是列表读 reflog）。
误清后可用 `git update-ref --create-reflog refs/stash <sha>` 或手工补
`.git/logs/refs/stash` 恢复。另：shell 脚本里 `$var` 紧跟中文/全角字符
（如 `$remote_ref（`）会被当成变量名的一部分，必须写成 `${remote_ref}`。

**调试**：logcat 受限（HKS），运行日志走文件 `files/main_debug.log`（任意线程
`SessionController.appendLog` 写入）：
`adb shell "run-as io.github.shaonialife.cardeck grep/tail files/main_debug.log"`。
接收端画面验证：`adb -s <测试台serial> exec-out screencap -p`。

## 架构速览

```
bt/          蓝牙 iAP2：Server（测试台回连）/ Client（真车主动连）+ BtSessionGate
iap2/        iAP2 链路层 + CSM + 发送端会话（0x5703 热点凭据 / 0x4301 AirPlay 端点）
net/         WiFi 入网绑定（WifiCarPlayConnector）+ mDNS ctrl 广播 + ctrl-int HTTP
airplay/     AirPlay：SRP 配对、加密 RTSP 控制通道、事件通道、流协商、ChaCha20 加密
video/       MediaCodec Surface 输入 H.264 → 128B 头 + ChaCha20 帧封装 TCP 推流
audio/       AudioPlaybackCapture → RTP/ChaCha20 over UDP
launcher/    隐形屏启动器（核心）：四块受信虚拟屏
render/      DualDisplayComposer：GL 合成多虚拟屏画面 → 编码器 Surface（出帧节奏在此）
input/       TouchRouter：车机触摸按坐标分流注入 dock/app/map 屏
shizuku/     Shell（cmd 执行）+ CarInputClient（触摸注入/虚拟屏创建/resize，AIDL UserService）
SessionController  会话总管（进程级单例）：链路生命周期、配对、WiFi 自动加入、取消
launcher/settings/ 设置面板体系（Fragment）：车机双栏与手机二级菜单共用同一套面板
media/       通知使用权 → 媒体会话状态（音乐卡数据源）
ui/ScreenDimmer  常亮+暗幕；WallpaperStore 壁纸
MainActivity 手机侧操作台 UI（英雄卡状态机 1s 轮询）
```

**虚拟屏**（横屏车机，全部经 Shizuku shell 创建）：CarPlayDock（左侧竖条，自有
DockActivity，不受信）+ CarPlayApp（原生应用屏）+ CarPlayAppHi（超采样应用屏，
竖屏锁定应用 letterbox 缓解）+ CarPlayMap（地图屏，仪表盘 tile/全屏间动态 resize）。
竖屏车机时 dock 落底部、无 Hi 屏。

## 关键约定与已踩过的坑（改代码前必读）

- **首页旋转：声明 configChanges 不重建 + 横屏双栏**（2026-10-18）：手机侧首页是
  有状态的操作台（滚动位置、折叠卡、已填 SSID、正在显示的弹窗），系统重建会全部丢掉，
  所以 `MainActivity` 声明了 `orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden`。
  **代价是 `layout-land/` 不会生效**，横屏排布只能在 `onConfigurationChanged` 里手工切：
  `applyContentLayout(landscape)` 在横屏把英雄卡**移出 ScrollView**、挂到 `contentArea`
  左缘固定（右栏 `mainScroll` 用左内边距让位），竖屏再移回 `contentRow`——
  英雄卡若留在同一个 ScrollView 里，滑右栏会把它一起带走（用户实测反馈）。
  移动父容器后 layoutParams 类型会变（LinearLayout ⇄ FrameLayout），必须整体重建；
  横屏高度要 `WRAP_CONTENT`（FrameLayout 里 MATCH_PARENT 会把按钮顶到卡片底缘）。
  **onCreate 也必须调一次**——以横屏启动不会触发 onConfigurationChanged。
  横屏屏高只有 ~300dp，实测预算：顶部栏 60 + 波形框 70 + 状态 40 + 副标题 20 +
  间距 18 + 按钮 52 + 内边距 24 ≈ 284dp 刚好放得下「未连接 + 启动连接」；波形框压矮后
  圆盘要同步缩到 44dp（否则 74dp 圆盘撑满框、弧线被完全挤出可视区），CARPLAY 标签
  在横屏隐藏。`SignalWavesView` 按 **min(宽比, 高比)** 缩放（原先只按宽度，压矮即溢出），
  `hubPlate`/`hubCaption` 用 gravity 居中而非固定 marginTop（后者按 176dp 算死，压矮必错位）。
  **英雄卡宽度不要读 `contentArea.width`**：旋转瞬间它还是旧方向的测量值（竖屏 1168 →
  横屏里英雄卡只剩 537px、副标题挤成三行），要用 `屏幕宽 - 页面内边距` 推算。
  弹窗尺寸统一走 `ui/PanelWindow`（宽度 92% 屏宽 + 可选高度比例），它维护「正在显示」
  的弱引用清单，`onConfigurationChanged` 调 `resizeShowing(this)` 重算——不重算的话
  弹窗会保持竖屏宽度、在横屏里只占中间一半。
- **线程**：短命一次性任务用 `Bg.run("name") { }`（util/Bg.kt，cached 池）；长驻循环
  线程（GL 合成、链路 reader/pacer、accept 循环、看门狗等）保持独立 Thread——需要
  命名、interrupt 和生命周期持有，不要收进池。主线程禁止 IO/解码（封面等走专用执行器）。
- **UI 线程绝对禁止 `Shell.exec`/Shizuku 调用**（2026-10-18 系统排查，两次真机 ANR 的
  共同根因）：每条 `Shell.exec` 都是 `newProcess` + 进程等待，正常 ~100ms、超时上限
  可达数秒；`detach()` 里 3 屏 ×(set+get) = 6 条直接 ANR，横屏勾选框的 `setEnabled`
  2 条（各 4s 超时）最坏 8s 也够 ANR。**已加运行时守卫**：`Shell.exec` 检测到主线程
  调用时累加 `mainThreadViolations` 并写文件日志
  （`SHELL: ⚠ 主线程调用 shell（第 N 次）cmd=…`），接线在 `SessionController.ensure`。
  出现该日志即说明有新代码走了 UI 线程，必须改 `Bg.run` / 异步包装。
  排查结论（2026-10-18 全量复查）：`MainActivity`（refreshShellState/resetShellState/
  releaseDisplays/通知授权）、`ScreenDimmer`、`SessionController`（autoJoinCarWifi/
  shellWifiStatus，仅由 Bg 调用）、`DeviceIdentity.bluetoothMac`（调用方均在后台）、
  `VirtualLauncher.launchApp`（三个调用点都包在 Bg）均已正确；本次修掉的两处是
  `VirtualLauncher.detach` 与 `AppOrientationCompat.setEnabled`（后者新增
  `setEnabledAsync` 供 UI 用，内部执行器串行保证连点顺序）。
  另注意 `BtProfiles.proxy()` 用 `getProfileProxy`，其**回调经主线程 Handler 投递**——
  在主线程调用必然等满 1.2s 超时并返回 null（已加显式主线程守卫与告警日志），
  这类「阻塞等主线程回调」的 API 一律只能在后台调用。
- **Shizuku 未就绪必须硬拦连接**（2026-10-18 用户实测：手动停掉 Shizuku 后仍能点
  「启动连接」）：隐形屏模式的虚拟屏创建、触摸注入、窗口/显示状态读取全部经 Shizuku
  shell，未就绪时放行只会走到「蓝牙已连、车机黑屏 + 触摸全丢」的半残态。
  拦截点在两条路径上都设了：`ensureShizukuReady`（CTA 前置，排在蓝牙之前）与
  `startConnectionNow` 开头（授权回调等延迟路径的兜底）。三态引导文案由
  `ShizukuGate.guidanceOf` 给出**互不相同**的动作（未装=无跳转只说明；未运行=打开
  Shizuku App；未授权=请求授权并自动续连），因为「未就绪」一句话无法指导用户。
  **连接准备清单把 Shizuku 计为第 1 项**（`readyCount` 现在 x/4），状态行本身可点开引导。
  授权结果经 `Shizuku.OnRequestPermissionResultListener` 驱动：granted 时刷新状态并
  在 `pendingShizukuThenConnect` 置位时自动继续，Activity 销毁必须注销监听。
  提示弹窗统一走 `ui/DarkMessageDialog`——裸 `AlertDialog.Builder.setMessage` 在
  `Theme.AppCompat.DayNight` 下是**浅色**的，与全深色 UI 割裂（同日真机截图暴露）。
  该组件支持 1~2 个操作横排靠右、3 个及以上**竖排等宽**（窄屏横排会挤爆；竖排时次要
  按钮也要上胶囊底，否则只有主按钮是实心整宽、权重失衡）；`Emphasis` 区分主操作
  （实心青）/ 正文按钮 / 危险操作（暗红）；`dismissOnClick=false` 可保留弹窗重试。
  已统一：Shizuku 引导、音频选择、取消连接、断开连接、蓝牙未开启、加入热点、
  残留虚拟屏、悬浮窗权限、横屏残留（列表外横幅 + 行内清除）。
- **三套坐标系**：车机触摸 = 通告视图坐标系（如 1160×540）；流/虚拟屏 = 缩放后尺寸
  （如 1450×674）。`TouchRouter.setCoordinateScale` 在会话建立时换算，改动触摸路由
  时不要破坏这层映射。地图屏坐标 = 显示内容本身（动态 resize 后）。
- **dock 尺寸必须随画布分辨率等比**（2026-10-17 修复「高分辨率下车机 dock 文字/图标
  显小」）：dock 的所有尺寸源自设计稿 `design/launcher-proto.html`（基准画布
  1160×540@420dpi，dock 48dp=126px、时钟 22px）。**dock 屏密度由 SessionController
  按画布短边等比下发**（`420 × min(流W,流H)/540`，上限 1680）——奥迪基准下系数恰为
  1.0、行为与旧版完全一致；测试台 2320×1080（2×）下密度 840，dock 条 252px、文字/
  图标同步 ×2，占画布短边恒定 23.3%。此前密度固定 `420×缩放%`，高分辨率车机上 dock
  相对画布减半显小（应用屏密度是自适应算的，所以只有 dock 显小）。配套约定：
  ① dock 布局（`activity_dock_sidebar/bar.xml`）**全部用 dp**（= 设计 px ÷ 2.625），
  禁止 px 写死；② 字号在 `DockActivity` 按「设计 px × designScale」以 PX 设置
  （designScale = densityDpi/420；不用 sp——dock 是像素级设计画布，不跟手机字体缩放）；
  ③ 模糊半径等物理像素量同样乘 designScale；④ `fitClockText` 的行程/步进也按
  designScale 缩放。改 dock 视觉时先确认这三处都随密度走。
- **竖屏桌面网格行数自适应**（2026-10-17 修复「竖屏单元格占用范围过大」：用户开
  「显示布局边界」截图暴露——固定 3 行把格子拉成 ~1:2.5 瘦高条，图标只占三成、
  大片空框）：`GridActivity.computeCellMetrics()` 按**格子目标高宽比 1.25** 自适应
  行数（`usableH / (格宽 × 1.25)`，钳制 3~6 行）——横屏 CarPlay 规格仍算出 3 行、
  行为与旧版完全一致；竖屏（如 648×1241 应用屏）算出 6 行（对齐 iPhone 竖屏主屏
  4×6 观感），格子收敛到 ~154×193。分页容量 = `COLUMNS × rows`（运行时字段，
  勿回退成常量）。UI 验证技巧：手机开发者选项「显示布局边界」下截图，单元格
  红框即实况边界；改尺寸后先算 `usableH/(格宽×1.25)` 再对照截图红框高度核对。
- **流尺寸必须为偶数**（H.264 4:2:0）：奇数高度会让编码器首帧输入时进错误态且
  曾被静默吞掉。缩放计算后统一在 SessionController 对齐（VideoSender 内另有兜底）。
- **虚拟屏 resize 必须同步消费端**：`VirtualDisplay.resize` 后要
  `SurfaceTexture.setDefaultBufferSize(新尺寸)`，否则投影把新内容画进旧 buffer 左上角
  （内容偏居左、宽度只剩比例值）。
- **虚拟屏上的应用弹不出键盘：两条独立原因，都必须修**（2026-10-17 定谳，车机侧
  「设置」搜索框点击无键盘根因；已实现并经真机端到端验证——车机屏弹键盘、点击按键
  文本进入搜索框）：
  **① 私有屏拒绝第三方 IME 窗口**：`createVirtualDisplay` 不声明
  `VIRTUAL_DISPLAY_FLAG_PUBLIC` 时屏带 `FLAG_PRIVATE`（owner=shell uid 2000），而
  `WMS.addWindow` 的访问闸 `displayContent.hasAccess(session.mUid)` → `Display.hasAccess`
  对私有屏只放行 owner/system/root/UID-present——**输入法（uid 10xxx）的键盘窗口被
  `ADD_INVALID_DISPLAY(-9)` 拒绝**，`SoftInputWindow.show()` 捕获后进入
  `REJECTED_AT_LEAST_ONCE` **永不重试**（表现为 IME 服务侧 `mViewsCreated/mWindowVisible=
  true` 但 WM 里没有 InputMethod 窗口、`mImeWindow` 恒 null）。修复：受信屏 flags 加
  `VIRTUAL_DISPLAY_FLAG_PUBLIC`（公开 API = 1；只放宽窗口访问，「公开屏默认自动镜像」
  仅在不声明 OWN_CONTENT_ONLY 时生效，我们已声明，不受影响；dock 屏保持私有）。
  **② IME 展示策略默认 FALLBACK**：`VIRTUAL_DISPLAY_FLAG_TRUSTED` 只保证受信，**虚拟屏的
  IME 展示策略默认是 `DISPLAY_IME_POLICY_FALLBACK_DISPLAY`**（`dumpsys window displays`
  里该屏 SettingsEntry `mShouldShowIme=null` 即此默认值）——IMMS 的
  `computeImeDisplayIdForTarget` 对非 LOCAL 策略一律返回默认屏，键盘请求被路由到
  **手机主屏**。修复：创建受信屏后 shell 反射调 `IWindowManager.setDisplayImePolicy(id,
  LOCAL)`——**LOCAL 值必须运行时反射读 `android.view.WindowManager.DISPLAY_IME_POLICY_LOCAL`
  而非硬编码**（本机 ROM 有魔改枚举值的风险，用户 2026-10-17 提示；AOSP = 0，读不到回落 0
  并记日志）。IWindowManager 代理走 `ServiceManager.getService("window")` +
  `IWindowManager$Stub.asInterface` 反射（`WindowManagerGlobal.getWindowManagerService`
  在本 ROM 上会致进程被杀，勿用）；需 `INTERNAL_SYSTEM_WINDOW`，本机
  `com.android.shell` 已授予，`wm`/`cmd window` 均无对应命令。策略按 display 实例
  （uniqueId 含代次）记录、虚拟屏每次会话重建，故必须随 createDisplay 重设（幂等）；
  只对受信屏可设（否则 WMS 抛 SecurityException）。
  两条都要修才通：只设策略 → 令牌迁到虚拟屏但窗口被 private 闸拒绝（实测
  `imeDisplayId=299` 而 `mImeWindow=null`、show 卡在
  `PHASE_SERVER_SET_REMOTE_TARGET_IME_VISIBILITY(49)`）；只放开 PUBLIC 不设策略 → 键盘
  仍落手机屏。**诊断工具**：反射读 `WindowManager` 全部 IME_POLICY 字段 + 逐屏读策略
  （`/data/local/tmp/const.jar ImeConst <ids...>`）；`dumpsys` 看 `mViewsCreated` 与
  `mImeWindow` 是否同真。改动文件：`CarInputServiceImpl.createDisplay`。
- **虚拟屏必须「忽略应用请求的方向」**（2026-10-18 定谳，头条旋转整屏的根因）：
  竖屏应用（头条等）在横屏虚拟屏上会请求 portrait，系统照办就把**整块虚拟屏**旋转
  ——`dumpsys display` 可见 `rotation 0→3`、`real 2251x1152→1152x2251`。后果是一连串：
  合成器按整幅映射 → 画面比例异常；`TouchRouter` 按未旋转尺寸换算坐标 → 点击漂移、
  点不到目标；退回桌面时仍是旋转态 → 「桌面分辨率也变了」。
  修法：会话建立时对 **app / appHi / map 三块屏**执行
  `cmd window set-ignore-orientation-request -d <id> true`，`detach` 时置回 false
  （必须在释放虚拟屏**之前**，否则 id 失效、设置以 uniqueId 残留到下次会话）。
  **踩过的坑**：这条命令只接受 `true|1|false|0`，传 `reset` 会报
  `Error: expecting true, 1, false, 0, but we get reset` 且**只写 stderr**——
  而 `Shell.exec` 不读 stderr，于是旧代码静默无效了整整一段时间（方向策略从未生效）。
  现在 `setIgnoreOrientationRequest()` 写完**读回校验**，失败会打印
  `⚠ 应用屏忽略方向请求失败`。**新增任何 shell 写操作都应读回确认**，
  尤其 `cmd window` 这类参数校验严格的命令。
- **GL 绘制坐标约定**：DualDisplayComposer 顶点/裁剪的 y 全程是 **GL 坐标（底部
  原点，向上增长）**，触摸/流坐标是顶部原点——两套方向相反，混用即出 bug。圆角
  裁剪矩形（uClipRect）必须与顶点同坐标系直传；曾因翻转成屏幕坐标 + 全高 tile
  对称重合而长期未暴露，半高 tile（竖屏）整块被 discard（地图卡不可见根因，
  2026-10-14）。部分区域可见性异常时优先核对该区域顶点与裁剪的坐标系一致性。
- **出帧节奏**在 DualDisplayComposer 合成循环：有新帧按 `1000/targetFps` 节流，
  静止画面保底 33ms 心跳（编码器断粮 = 接收端画面冻结）；「新帧」只统计会画进
  输出画面的输入（dock + 活动应用屏 + tile 可见时的 map）。
- **默认地图解析禁止回环**（2026-10-18，「卸载重装后地图卡不渲染」根因）：
  `DefaultMapPrefs.resolve()` 在「未设置默认地图」时曾回调
  `VirtualLauncher.defaultMapComponent()`，而后者只是转调回 `resolve()` ——
  无限递归 → `StackOverflowError`。触发条件恰好是**偏好为空**（卸载重装/清除数据
  后的状态），所以症状是「重装后首次连接地图卡黑屏、在车机设置里重选一次默认地图
  就恢复」。异常还被 `Bg.run` 的 catch 吞进 logcat（本机型 HKS 加密读不到），
  文件日志完全静默——极易误判成虚拟屏/合成器问题。
  现状：解析逻辑集中在纯函数 `resolveDefaultMap()`（显式优先 → 优先序扫描），
  单测 `DefaultMapPrefsTest` 覆盖 5 条路径（含「反复解析必须终止」）。
  **改默认地图相关代码时不要引入跨对象互调**；`VirtualLauncher.defaultMapComponent`
  只允许单向转调 `DefaultMapPrefs`。
- **会话生命周期钩子**：强制横屏覆盖（AppOrientationCompat）attach 时 applyAll、
  retireSession/onEnded 时 resetAll——新加系统级写入（settings/cmd）照此模式收口。
- **暗幕/常亮必须由会话驱动，不能由 App 启动驱动**（2026-10-19 用户实测
  「会话已断开、过一会儿仍然启动暗幕」）：`ScreenDimmer.attach` 原先在启动时就
  `schedule()` 45 秒定时器，而车机侧断开走的是 `VideoSender.onEnded`——它**不经过
  `retireSession`**，`VirtualLauncher` 仍挂着，定时器照常触发。现在自动暗幕的条件是
  `enabled && sessionActive && attached`（纯函数 `shouldAutoDim`，有单测）：
  会话开始（`VideoSender.start` 的 onSurfaceReady 里 `attach` 成功后才置位）才排程与
  把屏幕超时改「永不」，`onEnded` / `retireSession` 与 `detach()` 都必须清零标记
  （否则下次启动会沿用 true）。手动「假息屏」`enterDimNow` 走 `force` 路径不受限制。
  **判断标准**：任何「会话期间才该生效」的 UI/系统副作用，都要同时挂在
  「推流启动成功」与「onEnded」两端——只挂一端必然漏掉车机主动断开这条路径。
- **取消连接必须全程可中断**：`sessionStarting` 互斥标志的复位依赖配对流程线程
  走到 finally——该线程可能阻塞在不可中断的等待（无超时 connect、mDNS 等待、
  BT 保活读循环），任何一处卡住都会让取消「无效」（英雄卡永久「正在连接」）。
  防御三件套（2026-10-15 实战）：disconnect/取消时主动 detach 关 BT 会话 socket
  （**只在用户终态做，见下条**）、AirPlayClient.cancelled 标志让配对重试循环提前退出、
  cancelConnecting 强制 `sessionStarting.set(false)`（用户显式取消即强制解锁）。
  **新增任何长等待点时必须响应 connectCancelled**，且取消路径要实测「发起后立即取消」场景。
- **连接耗时三大头（奥迪实测，2026-10-18 日志量化）**：从「车机 ctrl 邀请/端点解析」
  到 RECORD 的 4.9s 里，`/info` ~3.0s 是车机侧会话初始化（返回值与车机第二次 ctrl
  邀请同一毫秒到达——它在窗口就绪前不应答，catplay 同样要等，**不可优化**）；
  自家代码可省的两笔已移出关键路径：
  ① `retireSession` 的 `AppOrientationCompat.resetAll`（6 应用 × 2 次 `am compat`
     ≈1.6s，原先同步跑在配对前）；
  ② 虚拟屏 attach 里的 `applyAll`（≈2.0s，原先推迟首帧）。
  两者改为同一个**单线程执行器**异步执行（`applyAllAsync`/`resetAllAsync`，串行保证
  「先复原上一会话、后写入本会话」）。**注意异步化的副作用**：`am compat enable`
  会立即杀目标应用，而桌面图标点击可能在写入完成前发生——`VirtualLauncher.launchApp`
  必须先 `AppOrientationCompat.awaitPending()` 排空队列，否则应用刚启动就被杀。
  另一笔大浪费是 WiFi 绑定超时竞态：旧 `latch.await(5s)` 在手动加入热点场景会在
  onAvailable 到达前 ~116ms 判失败并放弃整轮（真实耗时 20:02:09.352 放弃 →
  20:02:09.467 成功），用户白等一轮 BT 重连（+13s）。现窗口 15s、分片等待可取消；
  `WifiCarPlayConnector` 的 5s 看门狗只提示不改判定。
- **iAP2 接收队列：暂存区与新到帧必须分开**（2026-10-18，日志刷屏活锁根因）：
  `expectOrNull` 等待期间会把非目标帧放进 `pendingFrames`「待后续消费」，而
  `recvFrame` 又优先从 `pendingFrames` 取——旧实现两者共用一个优先队列，同一帧被
  反复取出→打日志→塞回，形成活锁，日志 ~1000 行/秒直到等待超时（触发场景：车机
  bring-up 时 `0x5000 StartNowPlayingUpdates` 与 `0x5703` 相邻到达；跳过 2s
  StartHID 空等后它直接落进下一段等待）。修法：`recvFrame`（通用，优先暂存）与
  `recvNewFrame`（只读新帧）分开，`expectOrNull` 只走后者、并先查暂存区里是否已有
  目标帧；暂存区加 256 条上限。回归测试 `Iap2SenderSessionTest` 在 mock 时序里插入
  迟到的 0x5000——去掉修复即超时失败。另外 `pumpIncomingForever` 对同一消息 ID
  连续到达做了限流（文件日志超 2MB 会被删，刷屏等于丢掉有用上下文）。
- **连接时序：能力广播先行、邀请驱动、不盲等**（2026-10-18，对照 catplay 连接流程）：
  catplay 发送端的顺序是「CarPlayCtrlServer 常驻广播 `_carplay-ctrl._tcp`（每 1s
  force_announce）→ 车机 `GET /ctrl-int/1/connect` 邀请 → 收到邀请立刻建 AirPlay」，
  全程没有固定 sleep。我们旧流程三处盲等，合计每轮白花 5~13s：
  1) ctrl 广播要等 0x5703 才发布（车机发现手机天然晚一步）→ 改为 `startConnection`
     即发布，0x5703/绑网后只做 `ensureForNetwork` 重注册；
  2) 端点交接后固定睡 1.6s「赌邀请已到」→ 改 `awaitCtrlInvite`（邀请计数事件驱动，
     2s 未到补一次 force_announce，4s 兜底；已发现 `_airplay._tcp` 也放行）；
  3) 等 StartHID 2s + 等 0x4301 10s 无条件 → 先读 0x1D01 参数 6（车机声明它可发送的
     消息清单），没声明就跳过（奥迪只声明 0x5703/0xea03，正是这两个空等的来源），
     未给清单时才按保守窗口（0x4301 由 10s 收到 3s）。
  配套：`_airplay._tcp` 发现器常驻（绑网即后台发现，需要时查缓存而非现查重试 3×6s）；
  端点解析移到邀请之后（车机服务常在受邀后才上线）且不再占用网络回调线程；
  WiFi 绑定前先确认 SSID 已是车机热点（`bindCurrentWifi` 只按 transport 请求、
  **不校验 SSID**，切网途中会绑到旧网）；`bindCurrentWifi` 的 release+rebind 会
  解绑进程网络，故绑过同一热点后走快路径、并用锁串行化两条绑定路径。
  **注意**：广播提前后首注册可能赶上 Shizuku 未就绪 → TXT id 写成占位 MAC，
  而车机按该 id 匹配手机（占位 = 永不邀请）。`DeviceIdentity.bluetoothMac(forceRetry)`
  + `CarPlayCtrlAdvertiser.repairPlaceholderMac`（每 2s 重试、成功即重注册）是必需兜底。
- **配对/推流期间绝不能断 BT 控制会话**（2026-10-16 定谳，「连上约 3 秒即断」循环根因）：
  DiPlay 在 WiFi 隧道 iAP2 就绪前（AirPlay 通道从未下发 `disableBluetooth`，
  `handoffRequested=false`）看到 RFCOMM 断开即判 `wireless bring-up failed` →
  `closeWirelessStack` 拆掉整轮无线栈（Bonjour/热点/AirPlay 服务），**把已建立甚至
  正在推流的 AirPlay 会话一起拆掉**（日志：DiPlay 侧 `RFCOMM reader FAILED` →
  `bring-up failed`；手机侧约 +0.4s 后 `TUNNEL/EVENT 读取结束 - Connection reset`、
  `推流中止`）。持续关一次就重建栈 30~40s，形成连上→断→重建→再连循环。
  曾经错在 `retireSession()` 里 detach（每轮配对起步都会执行一次）；现在只在
  `disconnect()`（用户断开/取消的终态）detach。**改会话退役逻辑时先确认没有新增
  中途关 RFCOMM 的路径**；`SessionController.kt:876` 的「第二阶段 hack」（配对失败
  后故意断 BT 重试）对 DiPlay 必然触发一次整栈重建，属已知代价。
- **蓝牙只配对、不连接**（2026-10-18，用户实测奥迪 MIB3）：手机与车机处于蓝牙
  **已连接**状态（HFP/A2DP 在线）时，车机**不开放无线 CarPlay 的 iAP2 服务窗口**
  ——表现为 `BT-C: 连接失败（车机未就绪或不在无线配对窗口）` 每 3s 重试，直到系统
  空闲自己断开才连上。真 iPhone 的无线 CarPlay 也只用系统级配对 + iAP2 RFCOMM
  （独立 SDP 服务），声音走 WiFi 的 AirPlay，这些音频/通话配置文件可断。
  实现（`bt/BtProfiles`）：发 iAP2 连接前查目标地址的配置文件状态，有连接就先断开
  （配对成功回调里也断两次：立即 + 1.5s 后，车机侧重连常发生在两者之间）。
  **断开只对「确认提供 iAP2 服务（SDP …caff）或用户选定目标」的设备做**（2026-10-20
  收窄，`Iap2BluetoothClient.awaitIap2Service`）：自动模式候选=全部已配对设备，
  耳机/手表没有该服务、连不上还白被断 HFP/A2DP（设备级断开兜底更会波及其它在线
  设备）。SDP 探测先读系统 uuids 缓存、缺失再 fetchUuidsWithSdp 等 ACTION_UUID
  （2.5s 窗），结论按地址缓存到客户端停止、start() 时重置。
  **注意 API 约束**：`BluetoothHeadset/A2dp/LeAudio.disconnect(BluetoothDevice)` 是
  **@SystemApi**（编译不可见，运行时只要 `BLUETOOTH_CONNECT`），只能反射调用；
  查询用公开的 `getProfileProxy` + `connectedDevices`（需主 Looper）。**不要**把这段
  挪进 Shizuku UserService（曾试过，不可行）：UserService 里
  `BluetoothManager.getAdapter()` 返回 null（无主 Looper 的应用上下文），查询恒为
  「未连接」，反而掩盖真实状态。诊断日志：`BT-PROFILE: 连接前…的蓝牙配置文件状态=`。
- **Shell 命令**：参数用 `shellQuote`（单引号转义）；查 WiFi 开关/当前 SSID 用一次
  `cmd wifi status`（勿用「任意 WiFi 即成功」的盲绑判定——手机连着别的 WiFi 时会误判）。
  **无互联网热点：默认走应用专属网络，不再系统级入网**（2026-10-18 定谳）：
  `WifiNetworkSpecifier`（`WifiCarPlayConnector.joinCarPlayNetwork`）加入车机热点——
  网络只对本进程可见（系统侧 `Ephemeral: true` + `OwnerUid=本应用`），**不进系统已保存
  网络、不成为默认网络**，其它应用继续走 4G/5G。实测（荣耀 + Redmi K40 + DiPlay）：
  `cmd wifi list-networks` 无该 SSID（返回 0）、`Active default network` 仍是蜂窝、
  推流期间 `ping baidu.com` 0 丢包。顺带解决三个老问题：系统 watchdog 不再切回原
  WiFi；不需要 Shizuku 也能自动加入；不会因「已连接无互联网」被 ROM 连带关掉移动数据。
  **迁移坑**：旧版本用 `cmd wifi connect-network` 存过热点，只要还在系统配置里系统就
  会自动全局连接——加入前先 `cmd wifi list-networks` 查同 SSID，**命中则弹窗询问用户
  是否清除，绝不擅自删除**（保留是正当需求：用户可能就想用系统自动连、或车机热点
  本身带流量；删除只影响「会不会全局自动连」，与本轮连接无关）。选择保留 → 照常
  以应用专属网络连接。每个 SSID 每进程只问一次（`forgetPromptHandled`），否则配对
  重试/端点重入会反复弹窗挡住流程；无人应答按「保留」处理（不做破坏性动作）。
  解析用 `WifiSavedNetworks.idsOf`（纯函数，有单测：SSID 含空格/非 ASCII/同网多行
  同 netId/畸形行都覆盖；宁可漏判不误删）。手动兜底（`manualJoinCarWifi`）会把热点
  存回系统网络，日志与弹窗都明确写出这个代价。
  **WEP 无法用 specifier 表达**（无对应 Builder API）→ `JoinResult.Unsupported`，
  走旧的 `connect-network` 兜底路径（`legacyJoinCarWifi`）。
  specifier 在 **Android 12/12L 仍需位置权限 + 位置服务**（13+ 由 NEARBY_WIFI_DEVICES
  接管）：缺权限时 `requestNetwork` 直接失败、现象只是「未能加入车机热点」，很难自查，
  故在连接前置检查拦截（`ensureWifiLocationReady`）；manifest 里 FINE/COARSE 必须成对
  声明，否则 lint `CoarseFineLocation` 直接失败。`onLost` 必须处理（热点消失/熄火），
  否则英雄卡会在「已连接」与失败间静默摇摆（`WifiCarPlayConnector.onNetworkLost`）。
- **目标车机选择（多台车机场景，2026-10-16）**：用户可能配对多台 CarPlay 车机
  （真车 Audi + 测试台 DiPlay）。未指定目标时出站客户端逐个尝试全部候选、入站
  服务端来者不拒——连到哪台取决于谁先应答；选定目标后**出站只连该设备、入站
  拒绝其它设备**（`TargetDevicePrefs` 存地址+名称快照，地址大小写不敏感比对；
  `targetProvider` 动态读取，选择立即生效、无需重连）。实测证据：目标=Audi 时
  日志只出现「连接 Audi_MMI_6318 失败」并且 DiPlay 的周期回连被
  「忽略非目标设备连入」拒绝；切回「自动」后 DiPlay 立刻恢复连入。
  注意：选择列表**只列已配对设备**（无线 CarPlay 前置是系统级蓝牙配对，未配对
  设备无法建 iAP2 会话，故不做蓝牙扫描发现新设备）；目标从系统配对列表消失时
  UI 提示「需重选」。`btnPickTarget` 在「蓝牙配对」标题右侧（仅已配对>0 时显示；
  最初放行尾与右侧徽标拥挤，2026-10-16 移入标题行）。
- **推流中的冗余端点/凭据必须忽略**：接收端控制器（DiPlay 实测）在我们配对推流**之后**
  才完成它本轮的 RFCOMM 连接并重发一整套 0x5703/0x4301；若照常当新会话处理，
  `retireSession()` 会把健康会话拆掉（编码器 stop 竞态，日志表现为 `Pending dequeue
  output buffer request cancelled`），接收端视为 session ended 再重连——形成「连上后
  自动断开」循环（2026-10-15）。防护在 `SessionController.onCarPlayEndpoint` /
  `onWifiCredentials`：`videoSender.isRunning` 时直接忽略/只刷新密码缓存；真断连时
  推流自行终止，下一个端点自然接续。会话中的冗余 0x5703 还会连带触发 WiFi 重绑 +
  ctrl mDNS force 重注册，都会扰动推流，一并拦下。
- **0x4301 是活事件，不是「该车机不发」**（2026-10-18，Audi MMI 9310 用户日志定谳）：
  这台车识别帧明确声明 `车机发送清单=0x4301 0xea03`，但 0x4301 在 Phase 1 结束后
  **2 分 35 秒**（配对 120s 窗口都已耗尽、mDNS 也始终没发现 `_airplay._tcp`）才到达；
  车机等到 15 秒无人连入 AirPlay 就自己拆掉 RFCOMM——用户看到的是「连上后自动断开」。
  旧实现有两处会吞掉它：`pumpIncomingForever` 把所有消息当 keep-alive 只记长度，
  且同 ID 连续到达会被限流 `continue`。现行三层修复：
  ① `Iap2SenderSession` 在保活泵里先于限流识别 0x4301 → `parseStartSession`（用缓存的
  0x5703）→ `onEndpointUpdate` 回调，并由 BT client/server 透传到
  `SessionController.onLateEndpoint`；② `onLateEndpoint` 按状态分流：推流中忽略（防上面
  的拆会话循环）/ 配对进行中 → `AirPlayClient.updateEndpoint` 热更新（关旧 socket，下一轮
  重连新地址）/ 交接尚未建 client → 暂存进 `pendingEndpointUpdate`，建 client 时 `drain`
  / 上一轮已失败 → 用新端点重走一轮 `onCarPlayEndpoint`；③ `AirPlayClient` 配对循环每轮
  重试前调用 `endpointRefresher` 再读一次 mDNS 缓存，网关兜底地址连不通时能及时切换。
  **新增生命周期消息（0x5702/0x6800/0x4301 等）时，先判断「它晚到会怎样」**——保活泵里
  必须按消息 ID 分流，不能只当日志。另：`resolveEndpointHost` 的等待窗口只有 3s，
  不能在超时日志里断言车机「不发」（MMI 9310 就是声明会发但晚到）。
- **0x4301 的地址字段是「定长 + NUL 填充」，解析必须去掉全部尾部 NUL**（2026-10-18，
  Audi MMI 3315 日志定谳）：车机把 IPv6 填充到 47 字节（`fe80::…` 后跟 **22 个 NUL**）、
  IPv4 填充到 17 字节（`10.174.189.1` 后跟 **5 个 NUL**）。`asStringOrEmpty()` 原先只
  `copyOf(size - 1)` 去掉一个，得到 `"10.174.189.1\0\0\0\0\0"`——这个字符串
  `contains('.')=true`、`contains(':')=false`，能通过所有「是不是 IPv4」的判断，
  但 Android 的 getaddrinfo 解不了（iOS/桌面 Java 反而容忍，极易误判为「不是这个原因」），
  日志表现为 `Unable to resolve host "10.174.189.1": No address associated with hostname`。
  典型症状链：SRP 配对靠 mDNS 刷新后的干净地址**侥幸成功** → 视频数据端口与 keepAlive
  仍用带 NUL 的旧地址 → 双双失败 → 车机 ~10s 后拆会话（用户看到「连上就断」）。
  教训：**协议字段里的 NUL 填充一律 `rstrip(0)`**，不要假设只有一个终止符。
  另外两处配套收口：① `SessionController.appendLog` 把 `\u0000` 换成可见的 `␀` 再写
  文件——否则导出的日志是二进制文件，用户反馈时打不开（本次日志 216 个 NUL，直接
  无法读取）；② 视频/音频/keepAlive 的 host 一律取 `client.currentEndpoint`（配对期间
  mDNS 刷新过的真实连接地址），不要用解析阶段的 `effectiveEndpoint` 旧值。
  单测 `nulPaddedAddressesAreStrippedCompletely` 用真实填充宽度锁住这一行为。
- **回退（应用侧 PUBLIC 屏）形态也必须建地图屏**（2026-10-20 华为 Mate30Pro/EMUI12
  实测）：EMUI 对 shell 侧 `createVirtualDisplay` **静默返回 null**（不抛异常）——受信
  创建整体失败落回退分支，而回退分支历史上只建 dock/app/appHi 三屏、无地图，仪表盘
  永远没有地图 tile（日志特征：`三屏创建(回退)` + 每轮 `地图屏未创建`）。修复后回退
  分支同样建地图屏；**resize 分流**：受信形态走 shell `svc.resizeDisplay`，回退形态
  必须用应用侧 `VirtualDisplay.resize()`（shell 对他人创建的 display 无 resize 权限，
  token 属主校验）。诊断约定：受信创建失败要逐屏留痕（哪块屏 -1），不能只留「回退」。
- **UserService 虚拟屏跨版本残留：三层收口**（2026-10-16 定谳，「重启后检测到残留屏、
  点清理却释放 0/N」根因）：Shizuku 按「组件+进程名+版本(APK lastUpdateTime)」复用常驻
  shell 进程——重装 APK 起新进程，旧进程 `am force-stop` 杀不掉（实测）、Shizuku 也不
  回收；而虚拟屏**只有创建它的进程能释放**（`releaseDisplay` 只查本进程 map，跨进程返回
  false）。判定进程版本看 `/proc/<pid>/maps` 的 base.apk 路径（`==deleted==` 即旧版本）。
  三层收口（缺一有洞）：① `CarInputServiceImpl` 自清——周期性比对 lastUpdateTime，
  过期自尽（重装后 ≤15s 旧进程退出、屏随之释放，实测 5s）；② `CarInputClient.
  reapStaleUserServices`——绑定成功即枚举 `:car_input` 杀掉非当前绑定 pid（覆盖无自清
  逻辑的历史旧进程，实测「已回收 1/1」）；③ 启动检测到残留先自动清理一轮，清不掉的
  才弹窗。**今后任何「进程持有的资源」（虚拟屏等）都必须考虑跨版本残留的释放路径**：
  进程回收是唯一兜底，`force-stop` 不可依赖。
- **启动残留清理必须与「强制横屏复原」同条件触发**（2026-10-16）：`AppOrientationCompat.
  resetAll` 挂在「检测到残留屏」这一条件上（`MainActivity.refreshShellState` 的
  startupCheck 分支）——残留屏是「会话异常退出」的判据，会话末未执行的复原与它同源。
  两条约束：① 复原必须在残留清理**之前**执行（清理会让残留清单变空）；② 预绑定
  UserService（会触发旧进程回收）必须放在残留扫描**之后**，否则回收先让屏消失 →
  残留清单变空 → 复原被跳过（2026-10-16 差点引入）。测试方式：写
  `shared_prefs/app_orientation.xml` 造 `force_<pkg>=true`，建会话后 force-stop 应用，
  重启应见 `ORIENTATION: 残留会话的横屏覆盖已复原 N 个`。
- **系统侧 compat 覆盖的读法（应用横屏面板）**：事实源是 `dumpsys platform_compat`
  （该 ROM 无 `cmd platform_compat`；全量 30ms/36KB，`grep` 到两个 ChangeId 后约 3KB，
  面板打开时查一次、行绑定只做内存查表，绝不可每行一次）。**必须同时解析
  `packageOverrides` 与 `rawOverrides`**：已卸载应用的覆盖只存在于 rawOverrides——
  packageOverrides 按「已安装包」解析，包不存在时条目消失（2026-10-16 实测：给未安装
  包写的覆盖只在 rawOverrides 可见，只读前者会让「列表外残留」横幅永远空着）。
  rawOverrides 里还有 `force_xxx/组件` 形式的历史脏键（旧版本偏好键格式泄漏），
  一并按「列表外残留」清理。UI 三层：行内小字（勾选开+系统开=灰字正常；勾选关+系统开=
  **红色**「系统残留·点击清除」，弹窗提示会重启应用）、面板顶部列表外残留横幅（批量清）、
  会话期 applyAll/resetAll 常规收口。
- **`am compat enable/disable` 会立即杀掉目标应用**（兼容覆盖只能在新进程生效；
  2026-10-15 实测：执行后 pid 当场消失，用户返回时按新配置重启）。给「应用横屏」
  这类开关做交互时不要再设计「查活/询问是否重启」流程——问出问题时应用已经被
  系统杀了，pidof 是在与击杀竞态（提示时对时错）。会话建立时的 `applyAll` 批量
  写入同样会杀掉正在运行的名单内应用（含手机侧实例），属已知代价。
- **「强制横屏」需要 Android 14+（AOSP 标准 ChangeId，SDK 门槛）**（2026-10-18 定谳，
  「车机设置→应用横屏」勾选报「写入失败」的根因）：两个 ChangeId
  （`265451093` OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR /
  `265464455` OVERRIDE_ANY_ORIENTATION）**是 AOSP 标准项，自 Android 14 / SDK 34
  起定义**在 `android.content.pm.ActivityInfo`（`@ChangeId @Disabled @Overridable`），
  由 `LetterboxUiController` 消费；Android 16 起重构到
  `AppCompatOrientationOverrides`。**Android 13 及更早的 ActivityInfo 里一个
  orientation 类 ChangeId 都没有**（实测 A13=0 个、A14=9 个）——这是硬版本门槛。
  **实测对照（同一操作、同一个包 `com.autonavi.minimap`）**：
  - 荣耀 Magic7 Pro（Android 17 / SDK 37）：注册 392 个 ChangeId，含这两个 →
    写入成功，`packageOverrides` 里可见 `com.autonavi.minimap=true`；
  - 红米 K40（Android 13 / SDK 33）：只注册 173 个，两个都不在表内 → 必失败。
  **与 Shizuku 无关**（adb shell 与 UserService 同为 uid 2000，结果一致），
  换 Shizuku 版本或重试都无效。
  次要因素：即便 ChangeId 已注册，user build 也只允许改 debuggable 应用
  （`Cannot override a change on a non-debuggable app and user build.`）。
  另注意「给未安装的包写入会假成功」（只落 rawOverrides、不做 debuggable 校验），
  所以**用包名试探得出的结论会自相矛盾**，必须以 `dumpsys` 的 name 字段为准。
  代码侧：`AppOrientationCompat.MIN_SUPPORTED_SDK = 34` 先做零成本版本判定，
  SDK ≥ 34 再用 `changeIdsRegistered()` 跑一次 dumpsys 复核（约 30ms，进程内缓存，
  两个 ID 必须**各自**带 `name=`——只注册一个时用整段 `grep name=` 会误判）；
  `WriteResult.UNSUPPORTED`（永久：偏好**不落盘**，否则下次连接白跑 applyAll
  且 UI 与系统脱节）vs `FAILED`（Shizuku 临时故障，保留用户意愿待重试）；
  `applyAll` 探测失败即整体跳过，不再逐应用空跑 2×4s 的 shell 往返；
  面板顶部显示黄色横幅并整列置灰。
  **查证方法教训**：gitiles 的 `?format=TEXT` 单文件抓取失败时会返回空/错误页，
  grep 不到不代表不存在——要校验字节数，或直接下 `+archive/.../<dir>.tar.gz`
  整目录再搜（本结论最初就是被一次静默失败的抓取误导成「OEM 定制项」）。
- **应用先运行、后写入覆盖 → 旧窗口仍 letterbox（需手动重启）**（2026-10-18 真机定谳）：
  `am compat enable` 确实杀掉进程，但系统恢复任务时 ActivityRecord 可能仍按**旧配置**
  重建（`displayChangedWithoutRestart=true`），表现为 `overrideOrientation=PORTRAIT`、
  `isLetterboxRunning=true`、`letterboxReason=FIXED_ORIENTATION`，而
  `platform_compat` 里该包明明是 `true`。只有 force-stop 后重新 `am start` 才生效
  （实测重启后同一屏变 `NOSENSOR`、`areBoundsLetterboxed=false`）。
  **确定性复现（不用跟任务时序碰运气）**：`am compat enable/disable --no-kill ...`
  写覆盖但不杀进程，此时把应用拉到虚拟屏就是纯净的「旧窗口 + 新覆盖」态。
  `--no-kill` 走 `setOverridesForTest`，只用于复现/排查，产品路径仍用常规写入。
  现行处理在 `LetterboxHintController`：事件驱动探测（连接建立/应用启动/地图全屏/重启后）
  → 解析 `dumpsys activity activities` 的 `bounds=`（内窗）与 `mMaxBounds=`（外框）
  算黑边 → 黑边够宽才在**左侧**画提示卡（`OverlayCard` 走合成器 2D 纹理层，
  左侧不够宽退右侧，两侧都放不下就不显示）→ 「强制重启」按钮 force-stop + 原屏重启。
  **补写自愈**：偏好开着但系统侧覆盖缺失时（会话写入失败/被外部清掉），先补写覆盖
  并自动把应用拉回原屏（`am compat` 会杀进程，补写后不重启就是把用户打回桌面还不给
  提示——2026-10-18 头条实测踩到）；重启后仍竖屏才给「仍然竖屏」终态卡。
  探测必须传 `displayIdHint`（launchApp/地图启动确知的目标屏）：不传时按合成器活动屏
  推断，而切屏要等目标屏出首帧（最长 2s），探测早于切换完成会看错屏、读空结果。
- **免责声明（对外分享的启动门）**：`legal/DisclaimerPrefs.CURRENT_VERSION` 用**版本号**
  而非布尔记录接受状态——**改 `DisclaimerContent` 文案必须同步 +1**，否则老用户不会重新
  确认；布尔值只能靠卸载重装才会再弹。强制形态三条约束同时成立才放行「接受」：
  10 秒倒计时、内容需滚动时已滑到底、（由 UI 保证）未点「放弃使用」；三者是**并列**关系，
  滑到底不能跳过倒计时。「放弃使用」立即可点（拒绝不该被阻拦）并 `finishAndRemoveTask()`。
  判定「内容是否可滚动」必须等布局完成（`scroll.height > 0`）：布局未完成时
  `canScrollVertically` 恒为 false，会被误判成「内容不足一屏」从而永久放行（真机踩到）。
  倒计时存**绝对截止时刻**（`elapsedRealtime`）经 `onSaveInstanceState` 透传，旋转重建
  后继续剩余时间而不是重新等 10 秒。强制弹窗 `setCancelable(false)` +
  `setCanceledOnTouchOutside(false)`；底部菜单入口只读（无倒计时/无滚动要求）。
- **媒体卡片**：元数据切歌是分阶段的（歌名先到、封面/时长滞后），空结果需短退避重试
  而非固化缓存；播放位置用「锚点 + 速度」外推，UI 每秒自刷新。
- **日志导出落 `下载/CarDeck/`，不能落应用私有目录**（2026-10-18）：用户要能自己找到
  并转发给开发者。实现走 `MediaStore.Downloads`（无需存储权限、文件管理器可见、
  微信/分享面板能直接选到）；`filesDir` 与 `Android/data/<pkg>` 在 Android 11+ 的
  系统文件管理器里直接被屏蔽，导出等于没导。导出弹窗必须用 `DarkMessageDialog`
  列出**绝对路径 + 大小**，snack 两秒就消失、用户看不到（本次需求即为此）。
  **默认只导本次进程启动后的日志**（历史轮次动辄几百 KB、淹没关键行）：
  `SenderApp.onCreate` 用 `DebugFileLog.markSessionStart` 写一条
  `SESSION_MARKER` 进文件，导出时 `lastIndexOf` 截取最后一条之后的内容；
  长按导出按钮走 `Scope.ALL` 取全量。标记**必须写在文件里**而不是只记内存偏移
  ——2MB 截断会让偏移失效；截断后也要立刻补写一条标记，否则导出退化为全量。
  无标记（旧版本文件）时回退全文——全量比空好。
- **荣耀 ROM 打开目录要用显式组件**（2026-10-18 实测）：`ACTION_VIEW` +
  `vnd.android.document/directory` 走隐式 intent 会被 resolver 乱匹配（实测弹出
  「网易云音乐」当默认项）；而 `am start` 同样的 intent 却正确落到 DocumentsUI。
  解法：优先显式 `ComponentName("com.android.documentsui",
  "com.android.documentsui.files.FilesActivity")` + `document/primary%3ADownload%2FCarDeck`，
  失败再退隐式/存储根/文字指引。**凡是「唤起系统 UI」的功能都不要只写隐式 intent**。
- **「音乐在播但卡片显示媒体信息未连接」= 没拿到通知使用权**（2026-10-18 定谳）：
  `MediaSessionManager.getActiveSessions()` 在**未授权时仍返回会话**——`PlaybackState`
  正常（`playing=true` 照常翻转），但 `metadata` 恒为 null，于是歌名/歌手全空。
  这个组合极易误判成 metadata 解析 bug，实际是权限问题（`dumpsys media_session`
  里能看到完整 `metadata: description=歌名, 歌手`，我们却读到 null，就是判据）。
  授权要点（`media/NotificationAccess`）：
  - **不能只在 onCreate 试一次**：那次若 Shizuku 未就绪就永久跳过（手机重启后
    Shizuku 未运行、或改包名后首次启动尚未授权 Shizuku），用户只会看到空卡片。
    现在三个时机都补（幂等，已授权直接返回）：App 启动、Shizuku 授权成功、
    **会话建立后**（`VirtualLauncher.attach`，此时 Shizuku 必然可用）。
  - **`cmd notification allow_listener` 的 exitCode 不可作判据**，且**立即复核也不可靠**：
    settings 写入有延迟，实测命令成功（结果=0）后马上读仍是旧值。必须短重试
    （4 次 × 250ms）确认，否则会误报「未授予」。
  - 判定「是否已授权」要**按条目精确比对** `enabled_notification_listeners`
    的 `:` 分隔列表，不能用 `contains(包名)`——旧包名/同前缀应用的条目会造成误判。
  - 改包名后 Shizuku 侧授权状态需要**重启 Shizuku 服务**才干净（实测：新包反复
    点「请求授权」无响应，在 Shizuku 里「停止」再「启动」后一次成功）。
  - 卡片文案要区分三种空：授权缺失（显示「未授予通知使用权」）、有会话但暂停、
    完全无会话（后两者都是「未在播放」）。
- **协议差异以日志为准**：测试台（DiPlay）与真车（奥迪 MIB3）行为多处相反（MFi 挑战
  32B/20B、0x4301 有无、ctrl 邀请死锁等），细节见 `docs/PROTOCOL-NOTES.md` §9，
  改协议代码前先对照。
- **老车机（CarPlay 210.81 / 奥迪 4xxx 固件）必须按 catplay 的「三时代」分类处理**
  （2026-10-19 定谳，`docs/PROTOCOL-NOTES.md` §9f）：该代是 **pre-HomeKit** 接收端
  （`_airplay._tcp` features 无 bit38 `HK_PAIRING_AND_ENCRYPT`），只接受
  `et=NONE` 明文控制会话 + **AES-128-CTR 零密钥**屏幕流，`pair-setup` 必然失败。
  六处硬约束：① bplist **根对象必须在字节 8**（210.81 不读 trailer 的 topObject，
  我们的编码器默认根在末尾——已统一 `makeRootFirst`）；② 初始 SETUP 用相对 URI
  `/setup`；③ 未配对会话显式带 `et=1`，事件通道明文；④ 屏幕帧未配对走 AES-128-CTR
  （密钥流**跨帧连续**，每帧新建实例 = 第二帧起全乱码）；⑤ 控制密文帧 MTU 1440 →
  单帧明文上限 **1422**（旧 0x4000 会被判 PayloadTooBig）；⑥ **不要用
  `contains('.')` 判「有没有地址」**——奥迪 4xxx 常只发 IPv6 link-local，判成无地址
  就会退到「网关:7000」兜底并永远连不上（统一走 `net/EndpointHosts`，IPv4 → 全局
  IPv6 → 链路本地 IPv6，且视频/音频/事件通道复用配对真正连通的 `activeHost`）。
  TXT features 缺失一律按「未知」保持旧路径，不能误判成「不支持配对」。
- **屏幕流 VideoConfig 帧头必须带「加密标志 + 画布宽高」**（2026-10-19 奥迪 4851
  实车定谳，`docs/PROTOCOL-NOTES.md` §9g）：车友反馈「app 显示已连接、车机没反应」，
  日志里会话/触摸/心跳全健康、零错误。根因是我们只写了 `body_size`+`opcode`，而
  catplay `ScreenFrame::config` 还写：`small_param[1] |= ScreenFlag::Encrypted(0x04)`
  （**偏移 6**，声明后续帧是密文）、`params[1]`（**偏移 16**）= 宽高两个 **f32 LE**、
  `params[0]`（偏移 8..16）留 0。Audi/DELPHI 按这些头字段建解码器，缺了就**整屏黑但
  会话照旧、触摸照常**；DiPlay/WinPlay 只读 body 与 avcC，**测试台永远发现不了**。
  因此凡是「车机屏黑但 app 显示已连接」都先核 `VIDEO: ✓ VideoConfig … flags=` 这行
  日志（会同时打印头部前 24 字节 hex）。新增屏幕流头字段时同理：先用 catplay
  `screen_frame_ext.rs` 对照，别只对着测试台验证。
- **iAP2 协商：SYN|ACK 必须用新序号，且每条 SYN 都要回**（2026-10-19 BMW 87866
  实车定谳，`docs/PROTOCOL-NOTES.md` §9h）：14 轮会话全部卡在链路层（`iap2 link
  ready` 0 次），车机发 133 条 SYN、回 0 条 ACK。两个根因：① `sentSequence` 只在
  发数据帧时自增，导致 SYN 与 SYN|ACK 同为 seq=99，对端按 `peerSeq+1` 校验后判重复
  丢弃（catplay 用 `take_own_seq()`）；② 只有首次采纳时回显，而 BMW **每轮重试都用
  新序号重开协商**，其余 SYN 只拿到裸 ACK 永远等不到回显。现状：每条**纯 SYN**
  （`control and ACK == 0`）都回一帧 SYN|ACK，同一 SYN 的重发字节级幂等（不推进
  序号）。两条踩坑：**裸 ACK 会与 SYN|ACK 撞号**（故主机路径只回 SYN|ACK，Audi
  帧序随之从「裸 ACK+SYN|ACK」变为单帧，需复测）；**不能对所有带 SYN 位的帧都回**
  （会与对端的 SYN|ACK 形成无限乒乓，单测曾因此空转 55 秒，只有 `jstack` 能看出）。
- **车机如何识别我们的字段，一律不许"照 catplay 抄"**（2026-10-19 奥迪 0.1.7 回归
  定谳，`docs/PROTOCOL-NOTES.md` §9i）：把 ctrl TXT 的 `srcvers` 从 `950.7.1` 改成
  catplay 的 `566.25.21`、实例名 `sender` → `iPhone` 之后，同一台 Audi MMI 3315
  从「0x4300 后 0.65s 下发 0x4301」变成**连 `GET /ctrl-int/1/connect` 都不发**，
  `_airplay._tcp` 与 0x4301 双零、网关 `:7000` 空转 54s（`ECONNREFUSED`）。
  iAP2 全通、车机还在发 0xAE00，极易误判成网络问题。已回退并加测试
  `CarPlayCtrlAdvertiserTest` 钉死。**同一类字段**：`sourceVersion`、`deviceID`、
  服务名、`model`、TXT `id`——改前必须真车 A/B 验证，且日志必须能直接看到取值。
  配套：`_device-info._tcp` 已移除（Android `NsdServiceInfo` 不接受 port=0，
  每次都报 `The port is missing`，属未验证收益的噪音）。
- **0x4300/0x4E0E 的蓝牙标识必须在会话启动时就拿到真实 MAC**（2026-10-19 奥迪
  0.1.7 日志）：整轮都是占位 `02:00:00:00:00:00`。根因是 `DeviceIdentity` 的 10 秒
  静默窗口撞上「BT 会话在 Shizuku 刚就绪的同一毫秒构造」——首次失败后 10 秒内全返回
  占位值。`BtSession` 现改为 `forceRetry = true` 并打印解析结果。奥迪会用它与 RFCOMM
  对端核对，占位值导致静默丢弃。排查时**先看 `设备状态问候 … mac=` 这行**。
- **AirPlay 会话级 SETUP 的 `deviceID` 必须是本机真实蓝牙 MAC**（2026-10-19
  WinPlay 实测）：接收端可以按「用户选中的 iPhone 蓝牙地址」过滤会话，对不上即回
  **403**，状态行是它特有的 `403 OK`（WinPlay 的 STATUS_TEXT 表没有 403，回退成
  OK——看到这个组合优先怀疑设备过滤）。此前用 catplay 的写死假值
  `ff:ee:dd:cc:bb:aa`——Audi/DiPlay 不校验，WinPlay 必拒；表象是「连上后约 45s
  自动断开」（接收端等视频超时后自己拆链）。现统一走 `DeviceIdentity`（与
  0x4300/0x4E0E 同源），占位值 `02:00:00:00:00:00` 只作 Shizuku 未就绪兜底，
  403 日志会打印该值供用户到接收端对照。**SETUP 非 200 要主动 close 控制通道**，
  别静默等接收端拆链——否则用户只看到「自动断开」，排查从零开始。
- **会话存活靠三条相互独立的心跳，缺一整轮 10 秒后被车机拆**（2026-10-19 奥迪
  MMI 3315 两轮实测，时序逐毫秒重合）：
  ① UDP keepAlive 4B ping → keepAlivePort；**间隔必须远小于车机看门狗**——catplay
  取的 10s 与看门狗等长，`Thread.sleep` 抖动让心跳晚到 1ms 即超时（现取 5s）；
  ② RTSP `POST /feedback` 空体，1s；
  ③ **屏幕数据口内的 keepAlive 帧**（128B 明文头，opcode=2；`/info` 的
  `keepAliveSendStatsAsBody=true` 时 opcode=5 带 bplist 统计体），1s，**视频持续
  推流时也照发**——它不是视频流量的备份。只有 ①② 时现象是「推流正常、RECORD 后
  10.2s 整轮 reset」，日志里视频/feedback/事件通道全都健康，极易误判为网络问题。
  **纠偏（2026-10-19 晚）**：三条心跳齐发时 MMI 3315 **仍**在 ~10s 被拆——该车的
  10s 拆链另有主因（缺 WiFi 隧道，见下条）；心跳是必要条件，不是充分条件。
  keepAlive 帧不加密（catplay 只加密 opcode=VideoFrame），不消耗 ChaCha 帧 nonce。
- **MIB3 需要第二条 iAP2 链路（WiFi 隧道），只回 ACK 会被 ~10s 拆链**（2026-10-19
  定谳，当晚日志 + AADisplay Frida 对照）：AirPlay 会话建立后，手机必须在屏幕流
  SETUP 成功后**主动经控制通道** POST /command（iAPSendMessage 信封）发一次 DETECT，
  车机回 SYN（事件通道）→ 手机 SYN|ACK（控制通道）→ 链路 NORMAL 后重跑控制会话
  （0x1D00→0x1D01→0x1D02+0xAA00→0xAA06→0xAA02 挑战【长度按车机记忆，隧道独立键，
  冷启动 20B 优先；同车蓝牙/隧道偏好可能不同——3315 蓝牙认 32B 而 Frida 隧道 20B】
  →0xAA03→0xAA05→突发
  0x4E0C/0xA100/0x4E0A/0x4E0B/0x4E09/0xFFFA/0xFFF0→应答订阅 0x5000/0x4157/0x4154/
  0x4170，0xFFFB 只链路 ACK）。实现：`airplay/VirtualIap2Link.kt`（三形态合一：
  DiPlay 隧道/MIB3/旧回显），协议细节与 Frida 逐字节常量见 `docs/PROTOCOL-NOTES.md`
  §12。硬约束：① 手机→车机链路帧**只能**走控制通道 POST（事件通道出站 MIB3 不读）；
  ② DETECT 必须在屏幕 SETUP 之后发一次（早发被冲掉，之后车机 marker 一律忽略）；
  ③ 回显 LSP 声明自己的 maxOutgoing=0x7F（原样回显对端表=自己限死在小窗口，突发
  积压）；④ 0xFFFA/0xFFF0/0x4158/0x5001 用逐字节常量，**勿重新编码**；⑤ 参考仓库
  `reference/AADisplay` 是 GPL-3.0：只看协议事实，代码不抄。
- **MFi 挑战长度按车机持久化，不要全局写死某一档**（2026-10-19 奥迪 MMI 3315 实测）：
  20B 挑战在该车静默 **6.0 秒**无应答，32B 才在 0.64 秒回签名——只靠「真车都 20B
  优先」的基线，每次连接都白等 6 秒。而各接收端偏好相反：DiPlay 只认 32B（长度不符
  直接断链）、MMI 6318 认 20B（32B 曾 0.4s 拆链）、MMI 3315 认 32B。
  `MfiLengthMemory` 按 0x1D01 的 name+model+manufacturer 生成稳定 key，持久化
  成功/静默/被拒三种结局；`orderAttempts`（纯函数）把**上次成功的长度排第一**，静默
  与被拒只降级、不丢弃（固件可能变，全被拒时照常全试，防永久卡死）；已知静默过的
  档位重试窗口压到 1.5s。**记忆是跨进程的**——旧实现只在进程内记，App 重启即丢。
- **音频通道（车机侧声音）三条硬约束**（2026-10-17 奥迪联网实测打通，此前从无声音）：
  ① **会话建立顺序必须是 屏幕SETUP → 连数据端口 → RECORD → 视频起流 → 音频SETUP →
  补发RECORD**：车机对「屏幕流 SETUP 后约 2s 内未激活」有看门狗，而音频 SETUP 本身
  要 2.0~2.7s（车机侧音频通路初始化），排在 RECORD 前必然把激活拖过看门狗——日志特征
  是 `音频 SETUP 往返 20xxms` 与 `EVENT 读取结束 - Connection reset` 同一毫秒、5 次
  实测全复现。音频流在 RECORD 之后加入时必须**补发一次 RECORD**才纳入会话。
  ② **audioType 必须用 `media`**（媒体音频专用通路）：车机 /info 的 audioFormats 里
  type=100 分档声明（奥迪有 compatibility/alert/default/media/telephony/speechRecognition
  六档，**每档位图不同**）；往 compatibility 推媒体流时车机接受 SETUP、给 dataPort、
  照收 UDP 包，**但不接入播放通路**（表现：手机静音成功、采集非零、包持续发送、无声）。
  必须**全量解析 audioFormats 逐档选**，不能只看前两档（旧日志 take(200) 截断曾导致
  误判「只有 compatibility」）。具体选择交给 `AirPlayClient.negotiateAudio()`：优先
  media 档 + PCM48k 立体声（bit15），退 44.1k（bit11），延迟取该档 audioLatencies 的
  outputLatencyMicros，无 /info 时回退协议基线（media/48k/250ms）。
  ③ **RTP 参数按 catplay 真车值**：SSRC=**0**（随机 SSRC 会被当未知流）、每包 **352**
  样本帧（AirPlay 标准 SPF）、timestamp 按帧递增、PT=100、大端 s16、AAD=头 4..12、
  尾 8B 小端 nonce。改这些值等于改线上格式，必须对照 catplay `rtp_recorder.rs` /
  `rtp_cipher_chacha.rs`。
  诊断三件套（无声时按序看）：`AUDIO: 采集样本 非零X% 峰值=N`（0%=没采到：应用禁止
  捕获或音频走了蓝牙）、`音频协商 → audioType=?`（选错通路）、车机 `/feedback` 里该
  connId 的 sampleTime 是否推进。
- **音乐卡「无法读取播放信息」= 通知监听器「有授权、无绑定」**（2026-10-19 实测）：
  `settings get secure enabled_notification_listeners` 里能看到本组件、
  `MediaListenerService.onListenerConnected` 却从未被调用——此时 `dumpsys notification`
  的 **Live notification listeners** 段里没有本组件，`getActiveSessions()` 仍返回会话
  （播放状态可见）但 `metadata` 恒为 null，卡片就显示「无法读取播放信息」。
  **授权列表 ≠ 已绑定**：重装 APK 后列表项还在，绑定可能丢失。实测对照——App 进程
  不运行时静置 10s 不会自愈；启动 App 后 1s 内恢复，说明必须由我们主动重绑。
  自愈实现见 `NotificationAccess.rebindIfNotConnected`：仅当本进程尚未确认连上
  （`serviceConnected` 为 false，由 onListenerConnected 置位）时读一次
  `dumpsys notification --noredact`，**只认 Live 段**，缺失则 `disallow_listener`
  + `allow_listener` 触发系统重新绑定并短轮询确认。别用「在 enabled 列表里」当已授权
  判据——那正是这个 bug 的误判来源。
  另：监听器断开/销毁时必须 `MediaSessionsState.publish(null)`，否则卡片会保留旧歌名，
  出现「正在播放 + 未在播放」这类自相矛盾的残留状态（布局静态小标题也不能写死
  「正在播放」，已改为中性的「媒体」）。
- **RTSP 控制通道必须异步读**（2026-10-17）：一条连接一把锁 + 同步读响应的实现，
  会让任何慢请求（奥迪音频 SETUP 2s+）期间 feedback 心跳停摆；车机对 feedback 有
  ~3s 看门狗，断供即拆会话。现为独立读线程收包解密 + 按 CSeq 投递，写侧只串行化
  「分配 CSeq + 加密写」；`soTimeout=0`（超时由待响应表的 poll 控制）。重连时旧 socket
  必须关（防两个读线程抢包），cipher 由空转非空的瞬间要丢弃明文残渣（否则密文帧
  边界错位、后续应答全解不出）。
- **HID 设备的能力一律按「设备描述符」判定，不按车机型号/uuid/报文长度写死**
  （2026-10-17：车机媒体键「收到但不切歌」+ 触摸多车机兼容的教训）：
  ① **媒体键（Consumer Control）**走 `hidSendReport`，1 字节报告、**字节值是 Usage
  数组下标**（HID Array 语义，`LogicalMax` = 数组长度-1），**不是 Usage ID**。
  奥迪按下「下一首」实测收 0x05 → usages[5]=0xB5；早期把 0x05 直接当 Usage ID →
  无对应键（表现为「有反应但不切歌」）。映射由 `ConsumerKeys.parseDescriptor()`
  解析描述符的 Usage 顺序得到，再按 `[KEYCODE_MEDIA_PLAY/PAUSE/NEXT/PREVIOUS/...]`
  派发（`AudioManager.dispatchMediaKeyEvent`，与桌面音乐卡同一路径）。
  ② **触摸**同样按描述符判格式：Digitizer(0x0D) 页 + X/Y 16bit 才是触摸设备；
  单指 Collection（奥迪 5 字节 `[tip][X u16LE][Y u16LE]`）vs 多槽（DiPlay 12 字节
  `[槽][down][X][Y]×2`）按「Contact Identifier(0x09 0x22) 出现次数」区分。
  **不能用报文长度或 uuid 判**：DiPlay 的 knob(4B)/telephony(1B) 与媒体键长度重叠，
  只靠长度会把 telephony 的 usage 0xB3 当成「快进」误发。描述符缺失时才回退长度判据。
  ③ 判定实现均有单测钉死语义（`ConsumerKeysTest` / `TouchReportsTest`，含奥迪与
  DiPlay 两套真实描述符），改这些解析器先看测试。
- **风格**：Kotlin，中文注释（注释写「为什么/实测依据」而非复述代码）；UI 文案中文。
  `reference/` 下是 GPL 参考仓库（gitignore，勿复制代码入本项目、勿外传）。

## 知识沉淀约定

- **随时补录**：工作中遇到值得记录的点——性能实测结论、踩坑、设计决策、已知问题与
  放弃方向——应随手补录到本文件对应章节，后续根据实际情况再调整组织结构。
- **取舍**：一次性的调试细节留在代码注释里；**跨会话仍有价值的**（会改变后续做法的
  结论、复发即踩的坑）才沉淀到本文件。判断标准：未来会话在不了解它的情况下大概率
  重犯或重查一遍。

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
adb -s <serial> shell am force-stop com.lizhihua.carplaysender   # 安装前必须先杀应用
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

**安装流程（重要）**：会话进行中直接 `adb install` 时，系统安装确认弹窗会落到车机侧
虚拟屏上（无人点得到，表现为 User rejected / 卡住）。**必须先 force-stop 再 install**；
若仍被拒，先 `input keyevent KEYCODE_WAKEUP` 亮屏再装——**熄屏时确认弹窗无人应答会被
系统自动拒绝**（2026-10-15 实测三次 INSTALL_FAILED_ABORTED，亮屏后即成）。

**提交**：pre-commit 钩子（`.githooks/pre-commit`，经 `core.hooksPath` 启用；克隆后需
执行一次 `git config core.hooksPath .githooks`）会自动格式化暂存的 kt/kts 并跑
`lintDebug`，失败即拦截。存量 lint 问题豁免在 `app/lint-baseline.xml`——新代码引入
问题会直接失败；实际修复后条目自动失效，有意豁免经评审后 `./gradlew :app:updateLintBaseline`。
协作约定：功能完成后**装机给用户验证，用户说「提交」才 git commit**。

**调试**：logcat 受限（HKS），运行日志走文件 `files/main_debug.log`（任意线程
`SessionController.appendLog` 写入）：
`adb shell "run-as com.lizhihua.carplaysender grep/tail files/main_debug.log"`。
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

- **线程**：短命一次性任务用 `Bg.run("name") { }`（util/Bg.kt，cached 池）；长驻循环
  线程（GL 合成、链路 reader/pacer、accept 循环、看门狗等）保持独立 Thread——需要
  命名、interrupt 和生命周期持有，不要收进池。主线程禁止 IO/解码（封面等走专用执行器）。
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
- **GL 绘制坐标约定**：DualDisplayComposer 顶点/裁剪的 y 全程是 **GL 坐标（底部
  原点，向上增长）**，触摸/流坐标是顶部原点——两套方向相反，混用即出 bug。圆角
  裁剪矩形（uClipRect）必须与顶点同坐标系直传；曾因翻转成屏幕坐标 + 全高 tile
  对称重合而长期未暴露，半高 tile（竖屏）整块被 discard（地图卡不可见根因，
  2026-10-14）。部分区域可见性异常时优先核对该区域顶点与裁剪的坐标系一致性。
- **出帧节奏**在 DualDisplayComposer 合成循环：有新帧按 `1000/targetFps` 节流，
  静止画面保底 33ms 心跳（编码器断粮 = 接收端画面冻结）；「新帧」只统计会画进
  输出画面的输入（dock + 活动应用屏 + tile 可见时的 map）。
- **会话生命周期钩子**：强制横屏覆盖（AppOrientationCompat）attach 时 applyAll、
  retireSession/onEnded 时 resetAll——新加系统级写入（settings/cmd）照此模式收口。
- **取消连接必须全程可中断**：`sessionStarting` 互斥标志的复位依赖配对流程线程
  走到 finally——该线程可能阻塞在不可中断的等待（无超时 connect、mDNS 等待、
  BT 保活读循环），任何一处卡住都会让取消「无效」（英雄卡永久「正在连接」）。
  防御三件套（2026-10-15 实战）：disconnect/取消时主动 detach 关 BT 会话 socket
  （**只在用户终态做，见下条**）、AirPlayClient.cancelled 标志让配对重试循环提前退出、
  cancelConnecting 强制 `sessionStarting.set(false)`（用户显式取消即强制解锁）。
  **新增任何长等待点时必须响应 connectCancelled**，且取消路径要实测「发起后立即取消」场景。
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
- **Shell 命令**：参数用 `shellQuote`（单引号转义）；WiFi 连接走
  `cmd wifi connect-network`，查开关/当前 SSID 用一次 `cmd wifi status`
  （勿用「任意 WiFi 即成功」的盲绑判定——手机连着别的 WiFi 时会误判）。
  **无互联网热点的自动切回（已知限制，放弃侵入式抑制）**：shell 连接的配置没有
  「用户选择」标记，系统 watchdog 会自动切回有网的原 WiFi 导致断连（手动连接不受
  影响）。曾实现「会话期间 add-network -d 禁用原网/全部其它网络的自动加入」——放弃：
  多已保存网络环境禁不完整、移动场景误伤、侵入用户配置。现行方案：connect-network
  自动加入（best effort）+ 阶段提示告知「若被切回请到 WLAN 手动连接热点」+ WiFi 未
  开启时等待提示（30s）+ 加入超时的手动兜底指引。
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
- **媒体卡片**：元数据切歌是分阶段的（歌名先到、封面/时长滞后），空结果需短退避重试
  而非固化缓存；播放位置用「锚点 + 速度」外推，UI 每秒自刷新。
- **协议差异以日志为准**：测试台（DiPlay）与真车（奥迪 MIB3）行为多处相反（MFi 挑战
  32B/20B、0x4301 有无、ctrl 邀请死锁等），细节见 `docs/PROTOCOL-NOTES.md` §9，
  改协议代码前先对照。
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

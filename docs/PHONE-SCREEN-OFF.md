# 手机物理息屏（真息屏）+ 虚拟屏独立常亮

> 2026-10-23。目标：手机黑屏揣进兜里，车机侧照常 60fps 交互。
> 代码：`shizuku/CarInputServiceImpl.setPhysicalScreenPower` +
> `SessionController.enterPhoneScreenOff`。

## 1. 为什么不是「按电源键」

按电源键/调 `PowerManager.goToSleep` 会进入 **锁屏 + Doze**：

- Keyguard 挡住虚拟屏（车机看到锁屏或黑屏）；
- CPU 降频/挂起，编码器与协议线程被拖死。

scrcpy 的 `-S/--turn-screen-off` 走的是另一条路：**系统逻辑上保持「亮屏且未上锁」**，
只把**物理面板的电源**在 SurfaceFlinger 层切断。CarDeck 照抄这个做法。

## 2. 三个关键点

### 2.1 虚拟屏要进独立显示组

受信虚拟屏（app/appHi/map）额外声明两个 @hide flag：

| Flag | 值 | 作用 | 额外权限 |
|---|---|---|---|
| `OWN_DISPLAY_GROUP` | `1<<11 = 0x800` | 虚拟屏进自己的 DisplayGroup，主屏休眠/断电时不跟着 OFF | `ADD_TRUSTED_DISPLAY`（受信屏本来就要求） |
| `ALWAYS_UNLOCKED` | `1<<12 = 0x1000` | 锁屏时该屏不画 Keyguard | `ADD_ALWAYS_UNLOCKED_DISPLAY`（**部分 ROM 没给 shell**） |

`DisplayManagerService.createVirtualDisplayInternal` 对两者都有显式权限校验，缺权限直接
`SecurityException`。所以 `createDisplay` 是**三级降级**：`独立组+ALWAYS_UNLOCKED` →
`独立组` → 基础 flags，任何一级失败都会留痕并继续，不会整块屏打挂。

注意 `PUBLIC` 会隐式加 `AUTO_MIRROR`，而 `AUTO_MIRROR` 会把 `OWN_DISPLAY_GROUP` 清掉——
我们同时声明了 `OWN_CONTENT_ONLY`（清掉 AUTO_MIRROR），所以独立显示组能保留。

**dock 屏也必须独立成组**（2026-10-23 实测）：dock 是**不受信**屏，原来只拿基础 flags，
于是落进默认显示组——物理面板一断电，整组跟着灭，车机侧表现为「app 屏正常、dock 栏全黑，
解锁后恢复」。修法是给 dock 也加 `OWN_DISPLAY_GROUP`（带回退）：
AOSP 只在 `AUTO_MIRROR` 时清这个 flag，而 `AUTO_MIRROR` 只随 `PUBLIC` 自动置上，
dock 没声明 `PUBLIC`，所以 flag 会保留；`OWN_DISPLAY_GROUP` 只要求 `ADD_TRUSTED_DISPLAY`，
不改变 dock「不受信」的语义（第三方任务仍然上不去）。

### 2.2 物理面板断电：`SurfaceControl.setDisplayPowerMode`

对**物理主屏 token** 调 `setDisplayPowerMode(token, 0)`（0=OFF，2=NORMAL）。
框架仍认为屏幕亮着 → 不锁屏、不进 Doze；触摸 IC 也随面板断电，口袋误触无效。

**Android 14 起取 token 的方式变了**（这是最容易踩的坑）：

- SDK ≤ 33：`SurfaceControl.getInternalDisplayToken()`，退路是
  `getPhysicalDisplayIds()` + `getPhysicalDisplayToken(id)`；
- SDK ≥ 34：上述方法**已从 SurfaceControl 删除**，只能用
  `com.android.server.display.DisplayControl`。它在 `services.jar` 里，普通类加载器看不到，
  必须用 `ClassLoaderFactory.createClassLoader(SYSTEMSERVERCLASSPATH, ...)` 造一个类加载器，
  再 `Runtime.loadLibrary0(cls, "android_servers")` 把 JNI 方法注册进去（scrcpy 同款）。

### 2.3 保持唤醒与屏幕超时

- `CarPlayService` 已经在会话期持 `PARTIAL_WAKE_LOCK` + WiFi 低延迟锁，不需要新增；
- `ScreenDimmer` 已在会话期间把 `screen_off_timeout` 设为最大值、结束时恢复，
  所以框架不会自己触发熄屏（否则组状态变化会把面板重新点亮）。

## 3. 时序

```
推流开始（虚拟屏已建好）
  → 若「连接时真息屏」关闭：只留暗幕，跳过以下步骤
  → 若手机在休眠：input keyevent KEYCODE_WAKEUP → 等 400ms → wm dismiss-keyguard（尽力）
  → setPhysicalScreenPower(false)        // 面板断电
  → phoneScreenOff = true

车机侧断开（VideoSender.onEnded）
  → restorePhoneScreen(sleep=true)       // 保持面板黑，直接 KEYCODE_SLEEP
用户手动断开 / 会话退役（retireSession）
  → restorePhoneScreen(sleep=false)      // 面板点亮（人就在手机前）
```

## 3.1 设置开关

**设置 → 显示 → 连接时真息屏（开/关，默认开）**，持久化在
`DisplayPrefs.screenOffOnConnect`（`settings` 共享偏好，键 `screen_off_on_connect`）。

- 关掉后：会话建立时不再断电面板，只保留原有的 45s 暗幕 + 常亮；
- **切换立即生效**（`SessionController.applyScreenOffPreference`）：面板黑着时也能在
  **车机屏**的设置面板里关掉它，手机会立刻亮回来，不必等下次连接；
- 没有会话时切换不会触发任何操作。

## 4. 已知限制（都不是 bug，是这套机制的固有代价）

1. **电源键要按两次才亮**：框架以为屏幕是亮的，第一次按电源键是「关屏」，第二次才是「亮屏」。
   这是 scrcpy 同款行为；真变砖的风险不存在。
2. **安全锁（PIN/图案）无法解锁**：`wm dismiss-keyguard` 对安全锁无效。虚拟屏靠
   `ALWAYS_UNLOCKED` 不受 Keyguard 影响；但手机上打开 App/接电话仍需手动解锁。
3. **应用侧回退屏（EMUI 等）不保证**：回退屏没有独立显示组，息屏后是否继续出帧取决于 ROM，
   日志里会看到 `SCREEN: 物理屏已断电` 但车机画面可能停住——这类 ROM 建议关掉本功能。
4. **进程异常退出**：UserService 是常驻 shell 进程，它的电源状态不随应用进程消失。
   兜底有三层：会话结束恢复、服务过期自尽前恢复、应用下次启动恢复一次。
5. **部分 ROM 不给 shell `ADD_ALWAYS_UNLOCKED_DISPLAY`**：日志里会出现
   `带 ALWAYS_UNLOCKED 失败——降级为独立组`，功能仍可用，只是锁屏时虚拟屏可能出现锁屏界面。

## 5. 排查

shell 进程的日志在 `/data/local/tmp/carinput_svc.log`：

```
createDisplay(CarPlayApp) 受信+独立组+ALWAYS_UNLOCKED flags=0xc48
createDisplay(CarPlayApp) 受信+独立组 flags=0xc08
DisplayControl 已就绪
setPhysicalScreenPower(false) ok=true
setPhysicalScreenPower(true) ok=true
```

应用侧（`files/main_debug.log`）：

```
SCREEN: 物理屏已断电（真息屏；系统保持唤醒，虚拟屏照常渲染，按电源键两次可恢复）
SCREEN: 物理屏断电失败（ROM 未给 shell 权限 / 取不到 display token）——继续用暗幕
SCREEN: 车机侧断开——已让手机休眠（面板保持黑）
SCREEN: 物理屏已恢复点亮 ok=true
```

## 6. 如何回滚

1. **用户层面**：设置 → 显示 →「连接时真息屏」切到「关」（立即生效，只保留暗幕）；
2. 若要连 flags 一起回滚：删掉 `CarInputServiceImpl.createDisplay` 里的
   `FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED`（第一级尝试），降级链会自动走基础 flags。

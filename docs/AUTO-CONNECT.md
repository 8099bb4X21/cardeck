# 上车自动连接（车机蓝牙 ACL 广播唤醒）

> 2026-10-23。目标：手机在口袋里，拉开车门 → 车机蓝牙连上 → CarDeck 自动被拉起并开始
> CarPlay 会话。代码：`bt/CarBluetoothReceiver` + `CarPlayService.ACTION_AUTO_START_SESSION`。

## 1. 广播：为什么清单静态注册能拉起被杀进程

`BluetoothDevice.ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED` 在
[隐式广播例外清单](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions)
里（Android 8+ 对清单注册的隐式广播做了限制，这两个是例外）。所以只要设备广播，
系统就会把：

```
<receiver android:name=".bt.CarBluetoothReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.bluetooth.device.action.ACL_CONNECTED" />
        <action android:name="android.bluetooth.device.action.ACL_DISCONNECTED" />
    </intent-filter>
</receiver>
```

投递到一个**冷启动的进程**里，不需要任何前台 Activity。

## 2. 前台服务：真正的豁免来源不是蓝牙

**Android 12+ 的后台 FGS 启动豁免清单里没有「蓝牙广播」这一项**（官方清单见
[Restrictions on starting a foreground service from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)）。
从 ACL 广播里直接 `startForegroundService` 会被 `ForegroundServiceStartNotAllowedException`
拦下——**除非**应用满足清单里的某一条。本项目满足的是：

> **Your app holds the SYSTEM_ALERT_WINDOW permission.**

暗幕（`ScreenDimmer`）和触摸探针本来就要求「显示在其他应用上层」，所以这个豁免天然成立
（targetSdk 34 只要求权限存在；Android 15+ 才额外要求当时有**可见**悬浮窗——本项目
targetSdk 34，不受该补充条款影响）。

推论与对策：

- 用户**没给**悬浮窗权限时，自动启动会失败 → `CarBluetoothReceiver` 捕获异常并写日志
  （手动打开 App 连接一次即可，那次有可见 Activity）；
- FGS 类型必须是 `connectedDevice`（不能用 2 参 `startForeground` 顺手带上
  `mediaProjection`——Android 14 会对后者校验 AppOps PROJECT_MEDIA，直接崩溃）。
  本项目的 `CarPlayService.onStartCommand` 已经固定用 `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`。

## 3. 目标过滤（避免耳机/手表误触发）

`CarBluetoothReceiver.isCarDevice`：

1. 用户在「蓝牙配对」里选过**目标车机**（`TargetDevicePrefs`）→ 只认那个 MAC；
2. 没选 → 按蓝牙名关键词兜底（BMW / MINI / AUDI / PORSCHE / MERCEDES / BENZ / VOLVO /
   CARPLAY）。

ACL 广播里只能拿到 `BluetoothDevice`（`address` / `name` 都需要 `BLUETOOTH_CONNECT`，
没授权就直接放弃——也无法判断是不是车机）。

## 3.1 开关

**设置 → 显示 →「上车自动连接」（开/关，默认开）**，持久化在
`AutoConnectPrefs.enabled`（`settings` 偏好，键 `auto_connect_on_bt`）。
接收器每次收到 ACL 广播都会读一次（进程可能刚冷启动，没有缓存）。关掉后 ACL 广播
仍会到达，但直接 return，不启动任何服务。

## 4. 时序

```
车机蓝牙 ACL 连上
  → CarBluetoothReceiver（清单静态，进程可被冷启动）
  → startForegroundService(ACTION_AUTO_START_SESSION)
  → CarPlayService.onStartCommand：5s 内 startForeground(connectedDevice) + 持锁
  → SessionController.ensure(applicationContext)   // 冷启动时先建会话对象！
  → 静默领取 MediaProjection（见 §4.1）→ audioEnabledForSession = 是否成功
  → SessionController.startConnection(applicationContext)  // 与首页「启动连接」同一条链路

车机蓝牙 ACL 断开
  → ACTION_AUTO_STOP_SESSION（startService：服务在跑时进程已是前台服务态，允许）
  → SessionController.disconnect()（退役会话、释放虚拟屏、恢复屏幕/超时、停服务）
```

## 4.1 静默领 MediaProjection（自动连接的「声音」）

AudioPlaybackCapture 必须有 MediaProjection 实例，而令牌是**单次**的（停止捕获即作废），
自动连接时没人能点「允许投屏」。两条系统闸门与对策（`audio/SilentProjectionActivity`）：

1. **授权弹窗** → Shizuku 执行 `appops set <pkg> PROJECT_MEDIA allow`：SystemUI 的
   `MediaProjectionPermissionActivity` 见到该 app-op 会直接返回 `RESULT_OK`，不弹窗
   （Tasker 官方文档同款做法）。
2. **后台启动 Activity（BAL）** → Shizuku 执行
   `am start -n <pkg>/<activity> --activity-no-animation`：shell 持有
   `START_ACTIVITIES_FROM_BACKGROUND`，不受应用侧 BAL 限制（所以该 Activity 必须
   `exported=true`——shell 没有 `START_ANY_ACTIVITY`）；失败时退回应用内
   `startActivity`（需要 `SYSTEM_ALERT_WINDOW`，缺失时先 `appops` 补授）。

页面本身：1×1、不可触摸/聚焦、无动画、`noHistory` + `excludeFromRecents` +
`taskAffinity=""`；`setShowWhenLocked/setTurnScreenOn` 保证手机锁屏揣兜里也能走完回调。

时序上有一条 **Android 14 铁律**：拿到授权后必须**先把 FGS 类型升级为包含
`mediaProjection`**，才能调 `getMediaProjection()`；实现直接复用
`pendingProjectionResult → upgradeToProjection() → takeProjection()`。
另外 `CarPlayService.onStartCommand` 里重复 `startForeground` 时，如果
`mediaProjection != null` 必须继续带上该类型，否则等于把类型摘掉、投影会被系统停掉。

**副作用**：`appops set PROJECT_MEDIA allow` 是**持久**的（appops.xml），即本应用以后
都能静默投屏；不需要时手动清：`adb shell appops set <pkg> PROJECT_MEDIA default`。

## 5. 已知限制

1. **声音依赖 Shizuku 在线**：静默领投屏令牌要走 `appops` + `am start`；Shizuku 不可用时
   自动连接仍能推视频（主动放弃音频，不弹窗）。`RECORD_AUDIO` 没授过时会先尝试
   `appops set RECORD_AUDIO allow` 补授。
2. **Shizuku 必须已启动**：虚拟屏/触摸注入全靠 shell 权限；手机重启后 Shizuku 未启动时
   自动连接会停在「蓝牙已连、车机黑屏」。日志里会出现 `SHIZUKU: 服务未运行`。
3. **首连可能失败一次**：车机刚上电时 iAP2 服务窗口未必就绪，现有 BT 客户端会自动重试；
   若一直失败，看 `BT-C: 连接失败…` 行。
4. **下车清理**：`ACL_DISCONNECTED` → `disconnect()`。若此时正在推流，`onEnded` 与
   `disconnect` 会先后跑（`phoneScreenOff` 标志保证只收一次）。
5. 自动启动不弹任何界面；用户想知道发生了什么，看通知（「CarDeck 运行中」）或日志。

## 6. 排查

应用日志（`files/main_debug.log`）：

```
AUTO: 车机蓝牙已连上 BMW 87866（AA:BB:CC:DD:EE:FF）——自动拉起会话
AUTO: 忽略非车机蓝牙 FreeBuds Pro（…）
AUTO: 自动启动被系统拦截（ForegroundServiceStartNotAllowedException: …）——请授予「显示在其他应用上层」
SVC: 收到「上车自动启动」——拉起会话
AUTO: 车机蓝牙已断开 …——结束会话
```

命令行验证：

```bash
# 接收器是否已注册（清单里的 receiver 会出现在 package dump 中）
adb shell dumpsys package io.github.shaonialife.cardeck | grep -i -A3 CarBluetoothReceiver
# 开关一次车机蓝牙电源，观察日志里是否出现 AUTO: / SVC: 两行
```

> **不要**用 `am broadcast -a …ACL_CONNECTED -n <pkg>/.bt.CarBluetoothReceiver` 来模拟：
> `EXTRA_DEVICE` 是 Parcelable，`am` 传不进去，接收器拿不到设备会直接返回，看起来像「没生效」。

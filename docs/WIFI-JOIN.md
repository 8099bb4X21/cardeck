# 车机热点入网：静默命令行连网 + 无 specifier 取句柄

> 2026-10-22。本文记录「为什么分两步」「为什么仍然必须 bindProcessToNetwork」
> 以及几处容易踩的框架/命令坑。代码：`SessionController.joinCarWifiSilently`
> 与 `net/WifiCarPlayConnector.bindCurrentWifi`。

## 1. 两条入网路径

| | 静默路径（默认） | 应用专属网络（兜底） |
|---|---|---|
| 触发 | Shizuku `cmd wifi connect-network` | `WifiNetworkSpecifier` + `requestNetwork` |
| 系统弹窗 | **0 个** | 首次连该 AP 弹一次确认框 |
| 网络归属 | 系统已保存网络（全局可见） | 仅本进程可见（`Ephemeral`） |
| 需要 Shizuku | 是 | 否 |
| 需要定位权限 | 否 | Android 12/12L 需要（13+ 由 NEARBY_WIFI_DEVICES 接管） |
| 用于 | 正常连接 | Shizuku 不可用 / ROM 屏蔽 `connect-network` 时 |

两条路径最后都走同一步：拿到 `Network` 句柄 → `bindProcessToNetwork` → 重注册 ctrl mDNS。

## 2. 为什么不能只用 `WifiNetworkSpecifier`

它把「让系统连上 Wi-Fi」和「向应用返回 Network」绑在一起：请求里一旦带 specifier，
`WifiNetworkFactory` 在批准前会走 `isAccessPointApprovedForActiveRequest`，全部批准来源
（CompanionDevice 关联、内部已批准 AP 列表、shell 预批准）都为 false 时**弹系统确认框**，
应用侧没有任何 API 能关掉它。

两个常见误区：

- **批准结果其实是持久化的**：批准过的 AP 按包名存进 wifi config store
  （`NetworkRequestStoreData`），所以「同一个热点」只有第一次弹窗。
- **不能用 shell 预批准**：`cmd wifi network-requests-set-user-approved <pkg> yes` 能把
  指定包加入 `mApprovedApp`（之后 specifier 请求直接放行、不弹框；但它是全局单值，
  批准本应用会挤掉别的应用），但这条命令**不在 `WifiShellCommand.NON_PRIVILEGED_COMMANDS`**
  里，uid 2000 执行会抛 `SecurityException: Uid 2000 does not have access to ...`（要 root）。
  所以 Shizuku 用不了。

结论：想做到「换车、重装、清数据后依然 0 弹窗」，只能绕开 specifier 去让系统自己连。

## 3. 两步式的两个实操坑

### 3.1 `connect-network` 的 exit=0 不代表连上了

`WifiShellCommand.connect-network` 只是把配置交给 `WifiService.connect(...)` 就 `return 0`，
连 `IActionListener` 都没等。所以**必须**轮询 `cmd wifi status` 直到出现
`Wifi is connected to "<SSID>"`（断开旧网 → 扫描 → 关联 → DHCP 实测 2~10s，5GHz 更久）。
代码里窗口 25s（`CAR_WIFI_JOIN_WAIT_MS`），可被「取消连接」即时打断。

### 3.2 `removeCapability(NET_CAPABILITY_INTERNET)` 不是必须的

`NetworkRequest.Builder()` 默认只有 `NOT_RESTRICTED / TRUSTED / NOT_VPN`，本来就没有
`INTERNET`；请求是「子集匹配」，少声明一项只会放宽匹配、不会排除没有外网的网络。
写上去无副作用，保留是为了语义清晰。**真正保证不弹窗的是「没有 specifier」**。

### 3.3 SSID 校验用 `cmd wifi status`，不要用 `WifiInfo`

`NetworkCapabilities.transportInfo` 里的 `WifiInfo.ssid` 在未授予精确定位权限（或定位
服务关闭）时返回 `<unknown ssid>`，还要处理 `FLAG_INCLUDE_LOCATION_INFO`、API 版本
分支；而车机热点「是不是当前连着的那一个」本来就是一条 shell 查询的事。统一用
`shellWifiStatus()`（一次 `cmd wifi status` + 正则）判定，既不需要任何权限，也能在
绑定前就排除「手机还连在家里路由器」的误绑。

## 4. 为什么仍然必须 `bindProcessToNetwork`

直觉上「只把 `Network` 传给 NsdManager / `socketFactory` 就够了」，本项目做不到，原因有三：

1. **mDNS 广播没有按网络指定的 API**：`NsdManager.registerService(NsdServiceInfo, int,
   RegistrationListener)` 没有 `Network` 参数（API 36 才新增 `AdvertisingRequest` 重载；
   本项目 compileSdk 34）。注册跟随**进程默认网络**——不绑进程，`_carplay-ctrl._tcp`
   就从蜂窝发出去，车机永远发现不了手机，`GET /ctrl-int/1/connect` 与 0x4301 都不会来。
   （发现侧的较新 API 提供了 `discoverServices(..., NetworkRequest, ...)` 重载，但广播侧没有。）
2. **全项目多处裸套接字**：AirPlay 控制/事件、视频 TCP、音频 UDP、timing server、
   ctrl 服务端都是 `Socket()` / `DatagramSocket()`，依赖进程默认网络。
3. **只影响本进程**：`bindProcessToNetwork` 作用于调用进程的 socket（同 UID），不是系统级
   设置。车机上虚拟屏里的导航/音乐是**独立进程**，照常走默认网络（蜂窝），不会断网。
   真正的代价只有「CarDeck 自己」的互联网请求（媒体封面 URL）在会话期间也走车机 Wi-Fi——
   这与 specifier 时代的行为完全一致，不是本次改动引入的。

## 5. `cmd wifi set-wifi-watchdog-enabled false` 不存在

AOSP `WifiShellCommand` 里只有：

```
set-wifi-watchdog <true|false>     # 作用是 WifiLastResortWatchdog（连续连接失败后的自救）
get-wifi-watchdog
```

两条都**不在** `NON_PRIVILEGED_COMMANDS`，shell（uid 2000）执行会抛 SecurityException，
只有 root 能跑；而且它管的是「一直连不上就自救」，不是「没有外网就断开」。想关掉
「无外网自动切走」需要看 OEM 的智能网络切换（荣耀/小米/OPPO 各有各的入口），没有通用的
shell 开关。项目现在的做法是：**失败就把原因写进日志与英雄卡**，由用户决定是否换手动路径。

## 6. 系统级保存的代价（这条是设计取舍，不是 bug）

- 热点进入「系统已保存网络」：下次进入范围自动连——这正是第二次连接完全零操作的原因；
  离开车后自动断开。
- 默认网络仍是蜂窝：该 Wi-Fi 没有 `VALIDATED`，`Active default network` 不会变成它，
  其它应用照常走 4G/5G（系统级 Wi-Fi ≠ 默认网络）。
- 部分 ROM 会提示「此 WLAN 无法访问互联网」甚至提供「切换回移动数据」按钮；这属于厂商
  UI，无法用 shell 统一关闭。
- 想撤销：系统 WLAN 设置里「忘记」该热点，或
  `cmd wifi list-networks` 查 netId 后 `cmd wifi forget-network <id>`。

## 7. 排查用的日志关键词

```
WIFI: 静默加入车机热点 "..."（cmd wifi connect-network，0 弹窗；当前 ...）
WIFI: connect-network 失败（exit=...）
WIFI: 已切到车机热点 "..."
WIFI: 手机已连在车机热点 "..."——直接建立网络通道并绑定
WiFi: 已绑定当前WiFi网络 ... bindProcessToNetwork=true
WIFI: 已连在 "..." 但 requestNetwork 未回调——本轮中止（不退回会弹窗的 specifier 路径）
>>> 以应用专属网络加入 "..."（走兜底时才会出现）
```

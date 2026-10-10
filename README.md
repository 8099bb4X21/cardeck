# android-carplay — 让荣耀手机扮演 iPhone 直连 CarPlay 车机

让一台普通 Android 手机（未 root）扮演 **CarPlay 发送端（iPhone 角色）**，
把 app 自绘界面投到奥迪车机的 CarPlay 上。

## 结论先行（可行性）

- **方向与 DiPlay/xcertplay 相反**：它们是接收端（车机角色）；本项目是发送端。
  **DiPlay 是 xcertplay 的衍生版，也是本项目实际使用的测试台**；`reference/`
  里两者的源码都留作参考（协议知识在 iAP2/链路层/Bonjour 层共享，角色逻辑互不包含）。
- **发送端不需要苹果签名身份**：iAP2 认证是单向的（手机验证车机的 MFi 证书，
  车机信任手机自报结果，Oligo Security DEF CON 2025 已公开证实）。
- **有线 USB 路线已排除**（app 无法控制 USB gadget，荣耀无法解锁 BL），
  只走无线：蓝牙 RFCOMM → 车机热点 → mDNS → iAP2/AirPlay。
- 详细协议依据见 [docs/PROTOCOL-NOTES.md](docs/PROTOCOL-NOTES.md)。

## 路线图与 Go/No-Go

| 阶段 | 内容 | 门槛 | 状态 |
|---|---|---|---|
| Phase 0 | 测试台 + 网络层 demo（本仓库） | 两台手机 mDNS 互发现 | ✅ 已验收（DiPlay 测试台） |
| Phase 1 | iAP2 发送端会话（链路状态机 + 0x1D00/0x5702/0x4300） | 接收端日志显示「iPhone 接入」 | ✅ 已验收：完整走通至 0x4301，拿到热点凭据与 AirPlay 端点 |
| Phase 2 | AirPlay 主会话（PairVerify + H.264 编码 + HID 回传） | 备用机显示本机画面并可触摸 | ✅ 已验收（2026-10-08 23:17）：30fps 推流+渲染、触摸事件回传打通 |
| Phase 3 | 真车（奥迪 MIB3）验证 | 车机点亮 | 🔶 进行中（2026-10-09 实车）：iAP2 全链路打通（识别→MFi→凭据→ctrl 邀请→mDNS 端点），卡在 AirPlay 配对（pair-setup 403 → 已实现 pair-verify 快路径，待实车复测） |
| 兜底 | 买 Tbox 安卓盒子，把同一套自绘 UI 装进盒子 | — | 失败时启用 |

**Phase 3 实车突破（2026-10-09，Audi_MMI_6318 / MHI3_AS_AU_P3801，见 `docs/PROTOCOL-NOTES.md` §9b）**：
与测试台（DiPlay）相比，奥迪 MIB3 有六处关键差异，逐一修正后 iAP2 全链路首次贯通：
1. 车机**主动发 SYN** 声明会话表 `{1:control, 2:EA}`，手机必须 ACK+原样回显，
   之后 CSM 走 **1 号会话**（发 10 号被车机静默丢弃，而链路层照常 ACK——极具迷惑性）
2. **MFi 挑战必须 20 字节**（32B 车机完全不应答；测试台 DiPlay 反而要 32B → 做成 32/20/16 回退）
3. 车机**不发 0x4301**（识别帧能力清单实锤：可收 0x4e0c/0x4e09/0x4e0d/0x4e0e/0x5702/0xea00/0xea01、
   可发 0x5703/0xea03）→ AirPlay 端点只能走 WiFi 上 **mDNS `_airplay._tcp`** 发现
4. **ctrl 邀请死锁**：车机在 WiFi 上发现手机 `_carplay-ctrl._tcp` 并 `GET /ctrl-int/1/connect`
   邀请后才开放 AirPlay 窗口 → 手机必须**在 0x5703 到达时立即**绑热点+广播 ctrl
5. ctrl TXT `id` 必须是**真实蓝牙 MAC**（Shizuku shell 读 `settings secure bluetooth_address`；
   应用层 API 只有占位 `02:00:…`，占位值导致车机不邀请 → 双向死锁）
6. pair-setup 被 **403**（奥迪不接受匿名 SRP 配对）→ 转「已配对设备」pair-verify 快路径；
   且**绝不能**断 BT 控制会话（测试台 detach hack 会让奥迪拆掉整轮会话）

**Phase 1 真机调试记录（2026-10-08，DiPlay on Redmi K40）**：
1. `StreamDuplexStream` 读写共享锁导致死锁（读阻塞持锁，写永远等待）→ 读写分离
2. MFi 挑战 16 字节被拒（Carlinkit EC 身份走 AuthV3，要求**精确 32 字节**）→ 已修
3. 参考代码（xcertplay）在订阅后发 0x6800 StartHID；**实际测试台 DiPlay 不发**（订阅完直接等 0x5702）→ 改为 2 秒可选等待

**Phase 2 里程碑 A：AirPlay 配对打通（2026-10-08 22:58）**——蓝牙保持连接状态下一次通过：
`/info` 前哨 87ms 应答 → pair-setup（SRP 固定码 3939，M1/M3/M5）→ pair-verify（M1/M3，
接收端 ed25519 签名对照 mDNS `pk` 校验通过）→ 控制通道 ChaCha20 密钥派生。
DiPlay 侧 logcat 确认：5 个 POST 全部处理，随即 `airplay control encryption enabled`，
之后耐心等待 23 秒（等加密的 SETUP/事件通道）才重置。

**调试教训（值得记一辈子的一个）**：「RTSP 无应答」曾归因于接收端状态门禁/时序竞争，
最终真凶是**客户端读缓冲 bug**——响应头和 body 在同一次 read 到达，`readExactly` 却去
流里等已消费的字节。**在下结论「对端不理我」之前，先用对端日志/抓包验证字节是否到达、
自己的读取路径是否正确。**

**Phase 2 全链路验收（2026-10-08 23:17）**：控制面（蓝牙 iAP2 → pair-setup/verify →
加密 RTSP → SETUP → 事件通道 → 流协商 → RECORD）+ 数据面（MediaCodec H.264 →
128 字节头+ChaCha20 帧封装 → TCP 推流）。接收端实测 rx=shown=29.6fps、decode p50=6ms、
touch2frame 18ms、recoveries=0。

**触摸闭环验收（2026-10-09 10:20）**：事件通道命令解析（RtspAccumulator + plist）→
hidSendReport 双槽报文解析（u16LE 像素坐标）→ UI 命中测试。真机确认：按住高亮、
松开熄灭、计数递增、坐标实时刷新；拖动轨迹完整。注意坑：报文第二槽是
`(0,0)↑` 空闲占位，必须过滤，否则覆盖真实手指状态。

**音频通道（2026-10-10）**：SETUP type=100 协商 UDP 音频流 → `AudioPlaybackCapture`
捕获本机媒体/导航声音 → PCM 48k 立体声按 RTP+ChaCha20 封包推流（每包 512 帧）。
密钥与视频同规则（`DataStream-Salt<connId>`）；需要 MediaProjection + RECORD_AUDIO
授权（点「申请投屏权限」时一并请求）。已单测（解密回环 3 用例），待真机验证。
剩余：麦克风上行（通话）、正式车机桌面 UI 完善、真车（Phase 3）。

## 测试台搭建（Phase 0 使用说明）

1. **备用安卓手机**（Android 9+）：从 [DiPlay releases](https://github.com/shihabal3amri/DiPlay/releases)
   下载 APK 安装——它是我们的「虚拟车机」（接收端）。
   （DiPlay 是 xcertplay 的衍生版，更活跃；`reference/` 里两份源码都留作参考。）
2. **两台手机系统级蓝牙配对**（设置里直接配对即可，这是 DiPlay 回连的前提）。
3. **本 app 安装到荣耀手机**：
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
4. 按界面四步操作：
   - **③ 启动蓝牙 iAP2 监听**：监听 iPhone UUID，等 DiPlay 回连。
     连上后日志会十六进制转储入向字节——看到 `ff 55 02 00 …` 即链路层 SYN，
     Phase 0 的核心验证点；
   - 备用机 DiPlay 选无线模式（热点/局域网），开启后它会扫描已配对手机并回连；
   - **① 加入热点**：输入 DiPlay 显示的 SSID（系统弹窗输密码）；
   - **② 扫描 _airplay._tcp**：应解析出备用机的 AirPlay 端点 + TXT（deviceid/features/pi/pk）；
   - **④ 广播 _carplay-ctrl._tcp**：验证 DiPlay 能发现并反连手机控制通道。
5. DiPlay 的发布版内置了从 Carlinkit 固件提取的实验性配件身份（其 README 已声明），
   即可走完 MFi 认证（我们的发送端跳过校验）。若需自备 MFi 身份（芯片/本地证书/
   远程服务三种途径），见 xcertplay README。

## 工程结构

```
app/src/main/java/com/lizhihua/carplaysender/
├── MainActivity.kt              # 四步验证台 UI
├── iap2/
│   ├── Iap2LinkPacket.kt        # 链路层 FF5A/FF55 包编解码（Phase 1 补状态机）
│   ├── Iap2Csm.kt               # CSM 0x4040 分帧 + TLV 参数体
│   └── Iap2MessageIds.kt        # 发送端视角消息目录（含完整时序注释）
├── bt/Iap2BluetoothServer.kt    # RFCOMM 监听（iPhone UUID）+ 字节转储
├── net/
│   ├── WifiCarPlayConnector.kt  # WifiNetworkSpecifier app 专属入网
│   ├── CarPlayServiceBrowser.kt # mDNS 发现 _airplay._tcp
│   └── CarPlayCtrlAdvertiser.kt # mDNS 广播 _carplay-ctrl._tcp
└── util/HexDump.kt              # 协议观测
docs/PROTOCOL-NOTES.md           # 协议知识库（分层格式/时序/参考索引）
reference/                       # 参考仓库（gitignore，不入库不分发）
```

## 边界

- **产品形态是自绘车机桌面**（导航/音乐由 app 自己集成），不是整机镜像——
  车机触摸只能回传给本 app（注入其他 app 需系统权限）。
- **许可证**：`reference/` 下 xcertplay/DiPlay 为 GPL-3.0、catplay 各 crate许可证见其仓库；
  本项目为独立实现，仅参考公开协议格式；勿闭源分发对 GPL 代码的衍生品，勿分发固件提取的密钥。
- 真车验证前请先用可借到的 iPhone 与车机做一次正常无线配对留作对照。

## 已知风险（详单见 PROTOCOL-NOTES.md §9）

1. 奥迪 MIB2 在 catplay 兼容表中有断电前科；MIB3 全新未知。
2. 真车可能要求先插 USB 才开放无线配对（测试台无此限制）——Phase 3 实测。
3. WifiNetworkSpecifier 无法程序化传密码，每次入网需系统弹窗确认。

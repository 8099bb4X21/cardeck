# 无线 CarPlay 发送端协议笔记

> 本项目目标：Android 手机扮演 iPhone（CarPlay 发送端），连接 CarPlay 车机（接收端）。
> 本笔记综合四个本地参考仓库（`reference/`：DiPlay、xcertplay、catplay、
> carplay-wifi-extractor，已 gitignore）与公开逆向资料整理。
> **测试台接收端实际使用 DiPlay**（xcertplay 的衍生版，`reference/DiPlay/`）；
> 代码精读路径多为 xcertplay（`reference/xcertplay/`），两份源码同源、协议逻辑一致。
> 标注 ✅ 的为已在参考代码中逐行确证；标注 ❓ 的为待验证项。

## 1. 角色与认证模型

| | 接收端（车机角色） | 发送端（iPhone 角色，本项目） |
|---|---|---|
| 承担者 | 奥迪车机 / DiPlay（测试台） | 本 app |
| 需要苹果签名身份 | ✅ 是（MFi 证书/芯片，被手机验证） | ❌ 否 |
| 验证对方身份 | ❌ 否（单向认证，Oligo DEF CON 2025 证实） | 可选（测试台阶段直接跳过） |

**关键结论**：iAP2 认证是单向的——手机验证车机的 MFi 凭据（0xAA00 段挑战应答），
车机完全信任手机自报的验证结果。因此发送端**不需要**任何 Apple 签名材料，
DiPlay 从 Carlinkit 固件提取的证书对发送端无用（那是接收端身份）。

## 2. 分层结构（自底向上）

```
┌────────────────────────────────────────────────────────┐
│ AirPlay 主会话: RTSP 风格控制 + H.264/H.265 流 + HID     │  Phase 2
│   承载: WiFi TCP（车机 AirPlay 端口, 来自 0x4301）        │
├────────────────────────────────────────────────────────┤
│ mDNS/Bonjour: 车机广播 _airplay._tcp；手机广播           │
│   _carplay-ctrl._tcp（车机反连此端口）                    │
├────────────────────────────────────────────────────────┤
│ iAP2 控制会话 (CSM): 0x4040 分帧 + TLV 参数体            │  Phase 1
├────────────────────────────────────────────────────────┤
│ iAP2 链路层: FF55 SYN / FF5A 数据帧 + 校验和             │  Phase 1
│   承载: 蓝牙 RFCOMM（无线）/ USB bulk（有线,已排除）      │
└────────────────────────────────────────────────────────┘
```

## 3. 链路层（✅ 已按 xcertplay Iap2LinkEngine.kt 精读修正，2026-10-08）

数据帧头（大端，9 字节 + 载荷 + 载荷校验和）：

```
FF 5A | u16 总长 | u8 控制 | u8 seq | u8 ack | u8 会话ID | u8 头校验和 | 载荷 | u8 载荷校验和
```

**Phase 1 实测修正（与早期笔记的差异）**：
- 握手不是「FF55 控制帧」：双方互发 marker `FF 55 02 00 EE 10`，然后发同步包
  （FF5A 帧，control=0x80，载荷 = 参数协商结构 `>BBHHHBB` + 会话描述符×3）
- **无线模式下手机是同步发起方**：配件（openWireless, initiateNegotiation=false）
  只发 marker 等手机先发同步包；双方收到同步包即回 ACK，协商态收到 ACK 进 NORMAL
- **会话 ID 是 10（控制）/11（EA）/12（文件传输）**，不是 Oligo 笔记里的 0/1/2
  （那可能是蓝牙承载变体；以能互通 DiPlay 的 10/11/12 为准）
- 序号 u8 回绕，初始 99；数据帧 control=0x40（捎带 ACK）
- 控制位：SYNC=0x80, ACK=0x40, EAK=0x20, RST=0x10
- 校验和 = 字节和的补码（含校验和在内 mod 256 == 0），头/载荷各一个
- 无线参数参考（DiPlay/xcertplay WIRELESS_LINK_CONFIG）：maxOutgoing=4,
  controlSessionVersion=2, 保留 ACK（有线用 zero-ack 模式）
- CSM 大帧按对端协商的 maxLength 分片，接收端经 CSM 解析器重组

**本项目代码**: `app/src/main/java/.../iap2/Iap2LinkEngine.kt`（完整状态机，已回环测试）

## 4. CSM 分帧与 TLV（✅ xcertplay `iap2/wire/Iap2CsmFramer.kt`）

```
帧:   0x4040 | u16 总长(含头6字节) | u16 消息ID | 体       （大端, 无校验和）
参数: u16 参数总长(含4字节头) | u16 参数ID | 值             （体 = 参数序列）
字符串: NUL 结尾 UTF-8；嵌套参数 = 参数值为子参数序列
```

**本项目代码**: `app/src/main/java/.../iap2/Iap2Csm.kt`（帧编解码 + 增量解析器 + TLV 构建器已实现）

## 5. 无线控制时序（✅ xcertplay `Iap2WirelessControlClient.kt` + extractor README）

蓝牙 RFCOMM 建链（两设备需先系统级配对）后：

```
配件 → 手机: FF55 SYN                          （链路层握手由谁发起 ❓ Phase 1 验证）
手机 → 配件: StartIdentification      (0x1D00)
配件 → 手机: IdentificationInformation (0x1D01)   车机身份 + 收发消息能力清单
手机 → 配件: IdentificationAccepted    (0x1D02)
配件 → 手机: MFi 认证序列              (0xAA00 段)  ← 手机侧可跳过校验 ❓细节待读
手机 → 配件: RequestAccessoryWiFiConfiguration (0x5702)
配件 → 手机: AccessoryWiFiConfiguration (0x5703)   明文 TLV: SSID/密码/信道/安全类型
   （安全类型: 0=None 1=WEP 2=WPA/WPA2 3=WPA3过渡 4=WPA3）
手机加入车机热点 → mDNS 解析 _airplay._tcp
手机 → 配件: CarPlayAvailability      (0x4300)
配件 → 手机: CarPlayStartSession      (0x4301)   无线参数 + 车机IP + AirPlay端口
                                                    + deviceIdentifier + 公钥 + sourceVersion
手机 → 配件: WirelessCarPlayUpdate    (0x4E0D)   通知无线 CarPlay 可用
手机 → 配件: DeviceTransportIdentifier(0x4E0E)   蓝牙/USB 标识，收到后配件重发 0x5703
配件 → 手机: StartHID                 (0x6800)   Phase 2: 触摸回传通道
手机 → 配件: DeviceHIDReport          (0x6801)   应答
```

0x1D01 身份参数布局（发送端需要解析；字段 id → 含义）：
0=name, 1=modelIdentifier, 2=manufacturer, 3=serialNumber, 4=firmwareVersion,
5=hardwareVersion, 6=发出的消息ID列表(u16[]), 7=接收的消息ID列表(u16[]),
8=电源类型, 9=最大电流(mA), 10=EA协议组, 12=语言, 13=语言列表,
16=USB传输组件(有线), 17=蓝牙传输组件(无线), 18=HID组件, 24=无线CarPlay组件(含SSID)。

## 6. mDNS/Bonjour（✅ xcertplay `network/CarPlayBonjour.kt`）

**车机侧**广播 `_airplay._tcp`，TXT 记录：

```
deviceid=AA:BB:CC:DD:EE:FF   features=0x44540380,0x61   flags=0x4
model=...  srcvers=...  protovers=1.1
pi=<配对ID>  pk=<ed25519公钥hex>
```

**手机侧**广播 `_carplay-ctrl._tcp`（TXT: `id=<蓝牙MAC>`），车机 resolve 后**反连**该端口
作为控制通道（即 iAP2 控制会话从蓝牙迁移到 WiFi TCP 的载体）。
测试台（DiPlay，沿袭 xcertplay）偏好 link-local IPv6，回落 IPv4——实测 ctrl 探测
源地址为 `fe80::…%wlan0`。

## 7. 蓝牙 RFCOMM（✅ xcertplay `CarPlayController.kt:1591,1963`）

- iPhone（发送端）监听 SDP 服务：UUID `00000000-deca-fade-deca-deafdecacafe`
- 配件端 UUID：`00000000-deca-fade-deca-deafdecacaff`
- 测试台（DiPlay）的连接策略：扫描**已配对**的 `PHONE_SMART` 设备 → 逐个连接上述 UUID
- 测试台因此只需：两台手机系统级配对 → 本 app 监听 → 备用机 DiPlay 回连

## 8. AirPlay 主会话（Phase 2，仅索引）

- 参考实现：catplay `carplay/catplay_carplay/src/carplay_tx/`（发送端 1959 行）+
  `screen/tx/`（视频发送会话）+ `rtp/record/`（发送侧 RTP）+ `pairing/pairing_helper_tx.rs`
- 加密：ed25519 PairSetup/PairVerify（HomeKit 风格）+ RTP AES/ChaCha
  （接收端参考实现在 xcertplay/DiPlay `airplay/PairSetup.kt`/`PairVerify.kt`/`Srp6a.kt`）
- 视频：H.264/H.265，AVCC/AnnexB 封装转换（catplay `video/`）
- 时钟：NTP 风格 timing 端口 + RTCP（catplay `clock/`）
- ❓ FairPlay/流加密细节无公开规格，靠 catplay 源码 + ludwig-v 解密固件对照

## 8b. 音频通道（✅ 已实现：手机 → 车机主音频流）

**协商**：在已加密的 RTSP 会话上 `SETUP`，body = `streams:[{type:100(主音频),
streamConnectionID, audioFormat:<位图>, audioType:"media"}]`；接收端应答
`{type:100, dataPort:<UDP>, controlPort:<UDP>, streamConnectionID}`。
- 音频格式位图（catplay `msg/streams.rs` AudioFormat）：PCM 48k 立体声 = `1<<15`、
  AAC-LC 48k 立体声 = `1<<23`；当前实现用 PCM 48k 立体声（协议基线）
- 流密钥与视频同规则：`HKDF(shared, "DataStream-Salt<音频 connId>", "DataStream-Output-Encryption-Key")`
- 音频流 ID 独立（用自己的 connId），与视频互不影响

**线格式**（RTP over UDP；接收端 DiPlay `AudioStream.kt` 解密路径证实）：
```
[RTP 头 12B][ChaCha20-Poly1305 密文 + 16B tag][8B 小端 nonce]
头: v=2 | M=0 PT=<stream type=100> | seq u16BE | timestamp u32BE(累计样本帧) | ssrc u32BE
AAD = 头字节 4..12（timestamp+ssrc 的 BE 表示，catplay "modern AAD"）
nonce(12B) = 4 字节零 + 尾 8 字节小端计数（逐包递增）
```
- 每包 512 样本帧（48000Hz 立体声 16bit = 2048B 负载，整包 ~2084B；
  接收端 UDP 收包缓冲 4096B，安全）
- 采集：`AudioPlaybackCapture`（Android 10+，需要 MediaProjection 授权 + RECORD_AUDIO）
- 当前为单向（媒体/导航下车机）；麦克风上行（通话）未实现

**本项目代码**：`audio/AudioSender.kt`（采集+封包+UDP）、
`airplay/AirPlayClient.runAudioStream()`（协商）、单测 `AudioSenderTest`（解密回环）

## 9. 真车（奥迪 MIB3）Phase 3 风险清单

1. catplay 兼容表记录：Audi/Porsche HARMAN MIB2「角色切换后对 dongle 断电重启」——
   但那是 USB 有线方向；无线方向未知
2. 用户车机为 MIB3（2020 款后），无公开兼容记录，全新未知
3. 真车首次配对：需要先有「系统级蓝牙配对」，奥迪车机通常要求先插 USB 才开放无线
   配对（❓ 关键未知项：能否纯蓝牙触发。DiPlay 测试台无此限制）
4. WifiNetworkSpecifier 不支持程序化传入密码（系统弹窗输入）——0x5703 拿到密码后
   无法静默入网，需要用户每次确认（❓ Phase 3 实测影响）

## 9b. 奥迪 MIB3 实测记录（2026-10-09，Audi_MMI_6318 / MHI3_AS_AU_P3801）

车机识别为 `AUDI 3260 / DELPHI / CL37_MIB3H_AU_AS_G4x_2200910PROD`。
蓝牙 SDP 广播配件端 iAP2 服务 `…decacaff`（系统级蓝牙配对后即可连，无需 USB 触发）。

**实测通过（逐字节对照修正后）**：

| 环节 | 结果 | 关键点 |
|---|---|---|
| RFCOMM 双向 | ✅ | 车机也主动回连（约 60s 一轮）；双向都工作 |
| 链路协商 | ✅ | 车机发 SYN 声明会话表 `{1:control, 2:EA}` → 手机必须**ACK + 原样回显该表**；之后 CSM 走**1 号会话**（发 10 号被静默丢弃，链路层照常 ACK——极具迷惑性） |
| 识别 | ✅ | 0x1D01 收到 |
| MFi | ✅ | **20 字节挑战**（32B 静默但不打断链）；0xAA03 签名 132B |
| WiFi 凭据 | ✅ | 0x5702 → 0x5703 ssid=Audi_MMI_6318_5GHz ch=149 psk 明文 |
| ctrl 邀请 | ✅ | 车机在 WiFi 上发现手机 `_carplay-ctrl._tcp` 后 `GET /ctrl-int/1/connect`（死锁关键：手机必须**在 0x5703 到达时立即**绑 WiFi + 广播带**真实蓝牙 MAC** 的 ctrl） |
| mDNS 端点 | ✅ | 车机 `_airplay._tcp` 上线于 10.174.189.1:7000，TXT: `features=0x4040280,0x61 srcvers=320.17.7 flags=0x4 model=AUDI 3260 deviceid=54:64:DE:5E:51:B0`（**无 pk 字段**） |
| AirPlay /info | ✅ | RTSP 200/455（在线） |
| pair-setup | ❌ 403 | 车机不接受匿名 SRP 配对（iAP2 已完成 MFi 信任） |
| pair-verify 快路径 | ⏳ | 403 后转「已配对设备」pair-verify，待实测 |

**2026-10-11 复测补充（新增两条经证实的坑）**：

1. **奥迪对 32B MFi 挑战的行为不稳定**——10-09 是「静默不断链」，10-11 实测
   车机在 32B 挑战后 ~0.4s **直接拆链**（此前 0xAA01 证书 912B 已正常收到，
   说明识别阶段全对）。若每次会话都从 32B 撞起会反复断链。
   修复：`Iap2SenderSession` 记「被拒档位」，本进程内后续会话直接跳过该长度。
2. **客户端线程泄漏会让车机错乱**（10-11 排查发现 3 个 `iap2-bt-client` 并发运行）：
   `BluetoothSocket.connect()` 是阻塞调用，旧代码成功后才登记 socket，
   `stop()` 关不到正在 connect 的 socket → 线程滞留；用户再次「启动连接」后
   旧线程因共享 `running=true` 复活 → 多个 RFCOMM 会话并发连车机，
   车机表现为随机拆链/时序错乱。修复：世代号（start/stop 递增，过期线程自杀）+
   connect 前登记 socket（保证 stop() 能 close 打断阻塞）。

**奥迪与测试台（DiPlay）的差异清单**：
1. 0x4301 因**车机型号/固件而异，不能按「奥迪」一概而论**：
   - **Audi MMI 6318（2026-10-09/11 全通那台）**：不发 0x4301——识别帧声明可收
     `0x4e0c 0x4e09 0x4e0d 0x4e0e 0x5702 0xea00 0xea01`、可发 `0x5703 0xea03`；
     AirPlay 端点只能走 WiFi 的 mDNS `_airplay._tcp`（10.174.189.1:7000）
   - **Audi MMI 9310（AUDI 5810 / DELPHI，2026-10-18 用户实测）**：识别帧
     **明确声明会发 0x4301**（`车机发送清单(2)=0x4301 0xea03`），但实际在 Phase 1
     结束后 **2 分 35 秒**才发（车机侧 CarPlay 窗口刚开）；此时我们的 3s 等待窗口、
     4s ctrl 邀请窗口、120s 配对窗口**全部已过期**。若把它当心跳丢弃，车机等 15s
     无人连入即拆 RFCOMM → 用户看到「连上后自动断开」。
     另：该车热点网关是 `10.174.189.254`（不是 .1），网关兜底端口 7000 仍 Connection
     refused——是否端口不同未定谳（该轮 mDNS 全程未发现 `_airplay._tcp`），
     下一轮日志的 `iap2 0x4301 原始体` 可确认它是否携带 IPv4/端口。
   - 结论：**0x4301 必须作为生命周期事件处理**（迟到也要解析并触发配对），
     超时日志不得写「该车机不发」。
2. 车机**主动发 SYN**（测试台等手机发）；会话表 {1,2} 且必须回显
3. **MFi 挑战长度双兼容（重要坑，2026-10-10 踩过）**：
   - DiPlay（Carlinkit AuthV3 EC 身份）：**只接受精确 32B**，收到 20B **直接断链**
     （实测 70~350ms 断；表现为「连不上 DiPlay + 挑战期间链路断开」）；应答 ~0.2s
   - Audi MIB3：对 32B 静默（不断链）或拆链（10-11 实测两种都出现过），对 20B 秒答
   - 结论：顺序必须 **32B(3s) → 20B(6s) → 16B(3s)** 回退。反过来 20B 优先会打死 DiPlay；
     对奥迪的 32B 拆链波动由「被拒档位」记忆自愈（见下方 2026-10-11 补充）
4. `pair-setup` 被 403（测试台接受 3939 SRP）
5. AirPlay 端口窗口式开放（ctrl 邀请接受后短暂开放；未受邀时 Connection refused）
6. **不要断 BT 控制会话**（测试台 hack 的前提是 DiPlay 重建栈；奥迪会在断链后拆掉
   整轮 AirPlay 窗口）

**设备身份（0x4300/0x4E0E/ctrl TXT id 用）**：真实蓝牙 MAC 必须动态解析
（Android 11+ 应用层只能拿到占位 `02:00:00:00:00:00`）。
Shizuku shell 侧读法：`content query --uri content://settings/secure/bluetooth_address`。

## 9c. 奥迪 MIB3 全通记录（2026-10-11，Phase 3-C 验收）

iAP2 → AirPlay 配对 → 视频推流 → 触摸回传 → 虚拟屏注入，全链路可交互。
本日在 §9b 基础上攻克的最后一批协议差异：

| 环节 | 根因 | 修复 |
|---|---|---|
| pair-setup/pair-verify 403 | 配对请求缺 Apple 标识头 | `Content-Type: application/pairing+tlv8` + `X-Apple-HKP: 2`（pair-verify 再加 `X-Apple-PD: 1`）；M1 加 Method=None(0)+State(1) TLV |
| 屏幕流 SETUP 400 | ① timingPort=0 ② features 只提 videoPlayback（接收端算交集得空） ③ 缺资源声明 ④ 身份自报用车机版本 | TimingServer（32B RTCP NTP，pt=0xD2 请求/0xD3 应答，回显 t3+本地单调钟）；CONTROLLER_FEATURES 完整清单（uiContext/viewAreas/…共 14 项）；SETUP 前发 modesChanged（resourceID 1/2 entity=1）；UA/sourceVersion=iPhone 自报 566.25.21 + osName/osBuild 等全字段 |
| 会话 ~10s 被拆 | 缺双心跳 | keepAlive UDP 4B ping →车机 keepAlivePort（10s）；POST /feedback 空体（1s）。两者独立看门狗 |
| 触摸无报文（一） | 车机命令路径错 | 命令必须发**事件通道 socket** 上的 POST /command（Events 密钥、独立 CSeq、fire-and-forget）；RTSP 控制连接上的 200 是假确认 |
| 触摸无报文（二） | 未武装 HID | SETUP 后对 /info hidDevices[].uuid 逐个发 hidSetInputMode {hidInputMode:0, uuid}；**奥迪 uuid 是字符串 "0"/"1"**（DiPlay 是整数） |
| 触摸无报文（三） | 事件通道是双向 RTSP | 每条来向请求立即回 RTSP/1.0 200 OK（加密）；不应答→车机塞 3 条就断通道（「能滑一下」） |
| 触摸坐标 | 奥迪 HID 描述符 | uuid=1 eso_touchScreen：5 字节 `[tip 1bit][X u16LE][Y u16LE]`，X≤1412/Y≤654=屏幕分辨率绝对坐标；uuid=0 是 Consumer 媒体键（非触摸） |
| 虚拟 iAP2 链路 | iAPSendMessage 语义 | 事件通道 iAPSendMessage.data = 链路层字节流；marker=DETECT，车机（客户端）每 200ms 重发、等服务器回一次 DETECT 后发 SYN；服务器重发 DETECT 会把客户端打回 Detecting |
| 0x4E0E 杀 BT 链路 | 车机接受但不 ACK | sendControl(awaitAck=false) 发后不管；0x4300 同改 |
| mDNS 抓错目标 | 手机开热点共享 | 按「本机 IP /24 前缀」过滤 _airplay._tcp 结果；mDNS 失败时网关兜底（网关=车机，端口 7000） |

**调试流程坑**：`adb install -r` 不杀旧进程（旧 dex 继续跑，修复「无效」）；
NsdManager unregisterService 异步（复用 listener 报 already in use，每次注册新建）；
ctrl mDNS 注册必须发生在 bindProcessToNetwork **之后**（组播跟随进程绑定网络）。

## 9d. WinPlay（Windows 接收端）实测记录（2026-10-19）

WinPlay 是开源 Windows 无线 CarPlay 接收端（`reference/WinPlay`，GPL-3.0，Node + TS，
接收端角色）。用户用荣耀做发送端连它（日志 `logs/cardeck/xiaomi-winplay.log`）：
iAP2 → WiFi → mDNS → 配对全通，卡在会话级 SETUP。

**根因：会话级 SETUP 的 `deviceID` 必须等于接收端选中的那台 iPhone 的蓝牙 MAC。**

- WinPlay 启动时用 `cfg.targetBluetooth`（用户在 Windows 上选定的已配对 iPhone）
  设置过滤器：`stack.deviceFilter = id => normalize(id) === normalize(cfg.targetBluetooth)`
  （`reference/WinPlay/src/engine/run.ts:64`）。
- 会话级 SETUP 里 `dict.deviceID` 对不上（或缺字段）直接回 **403**
  （`src/engine/protocol/cpStack.ts:696-699`）；`RECORD` 在设备验收前同样 403。
- 我们此前发的是 catplay 的写死假值 `ff:ee:dd:cc:bb:aa`
  （旧注释「车机不校验」只在 Audi/DiPlay 成立）。
- 状态行 `RTSP/1.0 403 OK` 是 WinPlay 的指纹：它的 `STATUS_TEXT` 表没有 403 条目，
  `buildResponse` 回退成 `OK`（`src/engine/protocol/rtspMessage.ts`）。看到「403 OK」
  优先怀疑设备过滤，而不是通用 RTSP 实现差异。
- 失败后的表象是「连上后约 45 秒自动断开」：发送端不再发请求，接收端等视频超时
  （`armVideoDeadline` 45s）后拆链路。发送端应在 SETUP 非 200 时主动 close，别静默等待。

对照 Audi/DiPlay：两者都不校验 SETUP 的 deviceID，所以这个字段长期没有被发现。
本项目已改为复用 `DeviceIdentity` 解析出的**真实本机蓝牙 MAC**（与 0x4300/0x4E0E
一致），占位值 `02:00:00:00:00:00` 只在 Shizuku 未就绪时兜底，并在 403 日志里
明确提示「接收端请选择该地址」。

**WinPlay 的其他特性（对本项目有用的）**：

- 端口固定 TCP 17000；`_airplay._tcp` TXT 与 iPhone 同构
  （`srcvers=950.7.1 features=0x44540380,0x61 flags=0x4`）。
- 会话级 SETUP 只在 `keepAliveLowPower` 为真时才开 keepAlive 端口。
- 事件通道/流 SETUP/音频协商路径与 DiPlay 基本一致（`_setupScreen`/`_setupAudio`），
  同样按 `DataStream-Salt<id>` 派生流密钥，可作为接收端行为的第三份交叉验证。

**顺带修掉的 bplist 编码 bug**：`BinaryPlist.encodeInt` 原先按**无符号**范围选宽度，
但 bplist 整数是**有符号**的——35449（0x8A79）被写成 2 字节，Apple 侧解析成 int16
-30087。临时端口（timingPort/eventPort/dataPort 常落在 32768..65535）都会踩到；
WinPlay 用 BigInt 无符号读取所以不受影响，Audi 是否受影响未知（视频曾正常，但不能
排除它忽略了错误的 timingPort）。现已改为按 `Byte/Short/Int` 有符号范围选宽度，
回归测试覆盖 0/127/128/255/256/32767/32768/35449/65535/65536。

## 9e. 奥迪 MMI 3315 会话 10 秒被拆：三条心跳缺一不可（2026-10-19）

用户实测日志 `logs/cardeck/CarDeck-log-20261019-081700.txt`：iAP2 → MFi → 热点 →
配对 → SETUP → RECORD → 视频 30fps 稳定推流，**RECORD 后 10.17~10.23s 整轮被 reset**，
两轮时序逐毫秒重合（不是随机网络问题）。

**根因一：UDP keepAlive 间隔与车机看门狗等长。** 我们每 10s 发一次（catplay
`KeepAliveClient::PING_INTERVAL`），而车机看门狗也是 10s。日志里 ping #2 距 #1
恰好 10.001s，随后 213~247ms 内被拆——`Thread.sleep` 的毫秒抖动让心跳永远晚到
deadline 1ms。catplay 的 10s 只适用于它自家接收端；对真车必须留余量。
改为 **5s**（`AirPlayClient.KEEPALIVE_INTERVAL_MS`）。

**根因二：缺屏幕流内 keepAlive 帧。** catplay 在**视频持续推流时仍然每秒**向
屏幕数据口写一帧 keepAlive（`ScreenTransmitSession::create_keep_alive`，
`DEFAULT_KEEP_ALIVE = 1s`）——它不是视频流量的备份，而是接收端屏幕上独立的存活
信号。我们从未发过。帧格式（`reference/catplay/.../screen/screen_frame.rs`）：

```
128B 明文头: u32LE body 长度 | u8 opcode | 3B 小参数 | 15 × 8B 参数
opcode 2 = KeepAlive（空体）
opcode 5 = KeepAliveWithBody（/info.keepAliveSendStatsAsBody=true 时；
           body = bplist{txUsageAvg, encoderCurrentFPS, sentFramesAvg,
                        queuedFramesAvg, lossAvg}，
           params[14] 后 4 字节 = body 长度 f32 LE）
```

**keepAlive 帧是明文**——catplay 的 `ScreenFrameCodec::encode_composite` 只对
`opcode == VideoFrame` 加密，所以 keepAlive 不消耗 ChaCha 帧 nonce。我们按此实现，
并在 `VideoSender` 的编码器出帧循环里按 1s 节流发送。

**三条心跳相互独立**：UDP keepAlive（5s，keepAlivePort） + RTSP /feedback（1s，
控制通道） + 屏幕流 keepAlive 帧（1s，视频数据口）。此前只实现了前两条，
`docs` §9c 里记的「补双心跳」只在 MMI 6318 上够用。

**音频采集全零（未解决，与拆链无关）**：`AUDIO: 采集样本 非零0% 峰值=0` 连续两轮。
候选原因按概率：当时无媒体在播（会前只有一条旧记录）、音频被路由到车机蓝牙
A2DP（PlaybackCapture 抓不到蓝牙输出）、或目标应用禁止播放捕获。

**MFi 挑战长度的车机差异（本机实测补充）**：MMI 3315 对 20B **静默 6.0 秒**
（15.605 发出 → 21.604 判定无应答），32B 在 0.64 秒回签名（22.247 收到 0xAA03）。
这 6 秒属于每次连接都要付的固定成本，除非按车记住偏好。现已实现
`MfiLengthMemory`：按 0x1D01 身份（name+model+manufacturer）持久化
「成功 / 静默 / 被拒」三种结局，命中成功档位直接首选；静默档位重试窗口压到 1.5s。
注意这与 §9b/§9c 的记录并不矛盾——**不同年款/固件的车机偏好相反**，MMI 6318
认 20B 且对 32B 会拆链，所以基线顺序仍按接收端类型选择，记忆只做覆盖。

## 9f. 老车机（CarPlay 210.81 / 奥迪 4xxx 固件）连不上：按 catplay 对齐的六处差异（2026-10-19）

用户反馈「奥迪软件版本 4 开头的（如 4851）连不上」。该代车机对应 catplay 分类中的
**pre-HomeKit 接收端**（README 兼容表：Audi/Porsche ~2020 HARMAN MIB2/MHI2 即
CarPlay 210.81）。对照 catplay 上游（本地引用停在 9-30，本次拉到 v0.17.0、39 个提交）
逐项核对后，本项目有六处与之不一致，全部按 catplay 修正：

**① bplist 根对象必须位于字节 8（catplay 9ad3a7e）**。CarPlay 210.81 不读 trailer 的
topObject，**直接按偏移 8 取根**；我们的编码器先编码子对象、最后才追加根容器，根默认在
末尾。现象是车机收到合法 bplist 却解析不出字典（`SETUP plist decode failed` / 400）。
修复：`BinaryPlist.encode` 末尾统一 `makeRootFirst` 重排并按偏移表修正（catplay 同算法），
单测 `StreamFrameCipherTest.encodedPlistRootObjectStartsAtByteEight` 用 >255 对象的
多字节偏移表锁住（小 plist 是单字节表、测不出该 bug）。

**② 初始 SETUP 的请求 URI 用相对路径 `/setup`**（catplay `initial_setup` 同款）。
旧实现发绝对 `rtsp://host:port/`。老固件对绝对 URI 敏感。

**③ 未配对会话必须带 `et=1`（NONE），屏幕流走 AES-128-CTR 零密钥**。
catplay `ScreenFrameCodec::new` 注释：「There was never support for unencrypted screen
sessions. ET = None maps to AES session where session_key before derivation is all
zeroes.」即 `et=NONE` ≠ 明文视频，而是 **AES-128-CTR + 16 字节全零会话密钥**，
密钥/IV = `SHA512("AirPlayStreamKey"/"AirPlayStreamIV" + connId(decimal) + sessionKey)[0..16]`。
事件通道在未配对会话里也是**明文**（catplay `EventsServer::unencrypted`）。
`et=NONE` 时音频没有共享密钥可派生 DataStream 输出密钥，直接跳过。

**④ 屏幕数据帧的两种加密形态**。已配对（pair-verify）→ ChaCha20-Poly1305；
未配对 → AES-128-CTR（无 tag、长度不变、密钥流**跨帧连续**，每帧新建实例会让第二帧起
全部乱码）。只加密 `opcode=VideoFrame`，keepAlive/config 始终明文。

**⑤ 控制通道密文帧的 MTU 是 1440**（catplay `AIRPLAY_CIPHER_MTU_MAX`）：
单帧明文上限 = 1440 - 2B AAD - 16B tag = **1422**。旧实现 0x4000 会被严格车机
判为协议违规（catplay 解码侧 `len > 1422` 直接 `PayloadTooBig`）。

**⑥ 只发 IPv4 地址的判据过窄**。奥迪 4xxx 的 `_airplay._tcp` / 0x4301 常只给
**IPv6 链路本地**（`fe80::…%wlan0`）。旧代码到处用 `contains('.')` 判「有没有地址」，
这类端点被判成不完整 → 白等 ctrl 邀请 → 退回「网关:7000」兜底（4xxx 的网关未必是
`.1`、端口也未必 7000）→ 永远连不上。现统一走 `net/EndpointHosts`：
IPv4 优先 → 全局 IPv6 → 链路本地 IPv6（补 scope），并且**视频/音频/事件通道复用
配对真正连通的地址**（`AirPlayClient.activeHost`），不再各自回头挑 IPv4。

**配套：用 TXT features 识别车机年代**。`_airplay._tcp` 的 `features=低32,高32`，
bit38 = `HK_PAIRING_AND_ENCRYPT`。老车机不声明该位 → 直接 `skipPairing` 走明文会话，
不再空跑 120s 配对窗口（这正是「一直连不上」的直接观感）。TXT 缺失/解析失败一律
视为「未知」并保持旧路径，绝不误降级。若明文 SETUP 仍被拒（判断失误），再补做一次
配对（catplay `handle_setup_result` 的 crypto 升级路径，窗口压到 30s）。

**顺带对齐**：`_carplay-ctrl._tcp` 的 TXT `srcvers` 改为发送端版本号（566.25.21，
此前误写车机侧 950.7.1）、服务名 iPhone；同时广播 `_device-info._tcp`（model=D84AP，
catplay `DeviceInfoBonjourEntry`）；`GET /info` 带空 bplist 字典体 + plist Content-Type。

## 9g. 奥迪 4851 实车「连上了但车机没反应」：VideoConfig 帧头缺两个字段（2026-10-19）

车友日志 `logs/cardeck/CarDeck-log-20261019-101353-all.txt`（小米 24129PN74C /
Android 16，应用 **0.1.4**；车机 `Audi_MMI_3315` / `AUDI 5163` / DELPHI，
`srcvers=450.14.2`，CarPlay `name=CarPlay`）。分析结果分两部分：

**A. 这次日志里确实每次都 10.2 秒被 reset**（六轮全部复现，与 §9e 同源）：
事件通道建立 → 恰好 +10.2s `EVENT: 读取结束 - Connection reset`。该版本缺
屏幕流内 keepAlive 帧、UDP keepAlive 还是 10s（与看门狗等长）。这两点已在
**0.1.6** 修掉（§9e），所以本轮不必再改——但车友必须升级到 0.1.6 才会有完整会话。

**B. 「app 显示已连接、车机没反应」的直接原因：VideoConfig 帧头缺字段。**
0.1.6 之前的实现在发 VideoConfig 时只写 `body_size` + `opcode`，而 catplay
`ScreenFrame::config` 还会写两项，Audi 这类严格解码器缺了就**整屏黑**：

| 字段 | 头部位置 | 值 | 作用 |
|---|---|---|---|
| `small_param[1]` | 偏移 6 | `ScreenFlag::Encrypted (0x04)` | 声明后续 VideoFrame 是加密的；不声明就按明文 NAL 解 → 解码失败 |
| `params[1]` | 偏移 16 | `(width, height)` 两个 **f32 LE** | 车机据此建立解码器画布尺寸 |
| `params[0]` | 偏移 8..16 | 全 0 | config 不带 PTS（只有 VideoFrame 才写） |

**为什么测试台一直没暴露**：DiPlay `ScreenStream.onMessage` 只读 `body`（opcode 0/1）
并从 avcC 里取分辨率，WinPlay `screenStream.ts` 同样只看 opcode 与 body——两者都不校验
这几个头字段。真车（Audi DELPHI）按头字段建解码器，缺了就黑屏，且**会话、触摸、
心跳全部正常**，日志里没有任何错误行——这是最难排查的一类。

**A 与 B 的关系**：A 让会话活不过 10 秒（用户看到「闪一下就断」）；B 让会话活着时
屏幕也是黑的（用户看到「显示已连接但车机没反应」）。两者独立，必须都修。

**诊断日志（本次新增）**：`VIDEO: ✓ VideoConfig 已发送 … flags=0x07 1412x654` 后面
跟一行头部前 24 字节 hex——下次反馈可直接核对这些字段是否写对。

## 10. 参考文件速查

> 表中路径均指 `reference/` 下**代码精读来源**（xcertplay 与 DiPlay 同源，
> 接收端逻辑一致，读哪份都行）；**实际测试台运行的是 DiPlay**（`reference/DiPlay/`）。

| 内容 | 位置 |
|---|---|
| 链路层字节格式/校验和 | `reference/carplay-wifi-extractor/example/iap2/link_layer.py` |
| RFCOMM 传输/UUID | 同上 `transport/bluetooth.py`；xcertplay/DiPlay `CarPlayController.kt` |
| CSM 分帧/TLV | `reference/xcertplay/.../iap2/wire/Iap2CsmFramer.kt`、`Iap2WireCodec.kt` |
| 无线控制时序 | `reference/xcertplay/.../transport/Iap2WirelessControlClient.kt` |
| 0x1D01 身份字段 | `reference/xcertplay/.../transport/Iap2IdentificationClient.kt` |
| 消息端点目录 | `reference/xcertplay/.../iap2/catalog/Iap2Endpoint.kt`（931行,全部参数布局） |
| Bonjour 细节 | `reference/xcertplay/.../network/CarPlayBonjour.kt` |
| 接收端 AirPlay 会话 | `reference/xcertplay/.../airplay/AirPlaySession.kt` + `PairVerify.kt` |
| 测试台（实际运行版） | `reference/DiPlay/`（含真车诊断/音频等增强；与 xcertplay 同源） |
| 发送端全套参考 | `reference/catplay/carplay/catplay_carplay/src/carplay_tx/` 等 |
| Windows 接收端（WinPlay） | `reference/WinPlay/`（TS 接收端；设备过滤见 §9d） |
| MFi 认证（接收端向） | `reference/xcertplay/.../mfi/`；Oligo 博客（单向性证据） |
| 解密固件(仅查阅) | ludwig-v/wireless-carplay-dongle-reverse-engineering（未克隆） |

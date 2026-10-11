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
1. 车机**不发 0x4301**——识别帧声明可收 `0x4e0c 0x4e09 0x4e0d 0x4e0e 0x5702 0xea00 0xea01`、
   可发 `0x5703 0xea03`；AirPlay 端点只能走 WiFi 的 mDNS `_airplay._tcp`
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
| MFi 认证（接收端向） | `reference/xcertplay/.../mfi/`；Oligo 博客（单向性证据） |
| 解密固件(仅查阅) | ludwig-v/wireless-carplay-dongle-reverse-engineering（未克隆） |

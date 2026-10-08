# android-carplay — 让荣耀手机扮演 iPhone 直连 CarPlay 车机

让一台普通 Android 手机（未 root）扮演 **CarPlay 发送端（iPhone 角色）**，
把 app 自绘界面投到奥迪车机的 CarPlay 上。

## 结论先行（可行性）

- **方向与 xcertplay/DiPlay 相反**：它们是接收端（车机角色）；本项目是发送端。
  两者的协议知识在 iAP2/链路层/Bonjour 层共享，但角色逻辑互不包含。
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
| Phase 3 | 真车（奥迪 MIB3）验证 | 车机点亮 | ⏳ 风险区 |
| 兜底 | 买 Tbox 安卓盒子，把同一套自绘 UI 装进盒子 | — | 失败时启用 |

**Phase 1 真机调试记录（2026-10-08，DiPlay on Redmi K40）**：
1. `StreamDuplexStream` 读写共享锁导致死锁（读阻塞持锁，写永远等待）→ 读写分离
2. MFi 挑战 16 字节被拒（Carlinkit EC 身份走 AuthV3，要求**精确 32 字节**）→ 已修
3. DiPlay 与 xcertplay 时序差异：**DiPlay 不发 0x6800 StartHID**，订阅完直接等 0x5702 → 改为 2 秒可选等待

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
touch2frame 18ms、recoveries=0。剩余：触摸事件消费（hidSendReport 解析→UI 命中）、
正式车机桌面 UI 替换测试卡、音频流、真车（Phase 3）。

## 测试台搭建（Phase 0 使用说明）

1. **备用安卓手机**（Android 9+）：从 [xcertplay releases](https://github.com/shilapi/xcertplay/releases)
   下载 mobile APK 安装——它是我们的「虚拟车机」（接收端）。
2. **两台手机系统级蓝牙配对**（设置里直接配对即可，这是 xcertplay 回连的前提）。
3. **本 app 安装到荣耀手机**：
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
4. 按界面四步操作：
   - **③ 启动蓝牙 iAP2 监听**：监听 iPhone UUID，等 xcertplay 回连。
     连上后日志会十六进制转储入向字节——看到 `ff 55 02 00 …` 即链路层 SYN，
     Phase 0 的核心验证点；
   - 备用机 xcertplay 选无线模式（热点/局域网），开启后它会扫描已配对手机并回连；
   - **① 加入热点**：输入 xcertplay 显示的 SSID（系统弹窗输密码）；
   - **② 扫描 _airplay._tcp**：应解析出备用机的 AirPlay 端点 + TXT（deviceid/features/pi/pk）；
   - **④ 广播 _carplay-ctrl._tcp**：验证 xcertplay 能发现并反连手机控制通道。
5. xcertplay 需要一个 MFi 身份才能走完认证；它默认用测试合成身份即可继续（我们的
   发送端跳过校验）。若需真实身份见其 README 的三条途径。

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

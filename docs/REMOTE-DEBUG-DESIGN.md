# CarDeck 远程调试桥（remoteDebug 变体 + cardeck-bridge）

> 2026-10-20 设计定稿并首版落地。目的：车友远程排障——开发者电脑经内网穿透
> 提供调试服务，车友手机安装 **remoteDebug 变体**填地址+配对码接入；开发者
> （或 AI agent）实时看日志、下发白名单命令、推送悬浮提示、一键重装新包。

## 总体拓扑

```
车机 ←→ 手机[remoteDebug 变体 .rd] ←WSS/WS── 内网穿透 ── 开发者电脑
                                                    ├─ WS 设备面（:8787）
                                                    ├─ MCP agent 面（stdio）
                                                    └─ GET /apk/<file>（重装下发）
```

## 关键决策与理由

1. **构建隔离用 product flavor 而非新模块/新仓库**：`bridge` 维度，`standard`
   （日常/发布，包名不变——Shizuku 授权按包名授予）与 `remote`（`applicationIdSuffix
   = ".rd"`，与日常版并存安装）。调试桥代码全部在 `app/src/remote/`，依赖用
   `remoteDebugImplementation`——**一行不进 standard 产物**（构建已验证）。
   manifest 叠加（`tools:replace="android:name"`）替换 Application 类为
   `BridgeApp`，并声明 `REQUEST_INSTALL_PACKAGES` 与调试专属组件。
2. **App 侧与业务代码的耦合面只有两处**：`DebugFileLog.tap`（main 加的日志
   序号+监听钩子，standard 恒为 null 零开销）与 `BridgeCommands`（调既有公开
   API `SessionController.ensure/startConnection/disconnect/statusLine`）。
   main 侧不认识任何桥类型。
3. **WS 客户端手写（~200 行 RFC6455 子集）**：不引 okhttp——依赖不进日常包、
   消息形态自家可控（文本帧/掩码/ping-pong/服务端分片）。wss 走系统
   SSLSocketFactory（穿透服务提供有效证书），自签/裸 IP 场景用 ws://。
4. **服务端独立仓库 `cardeck-bridge`（MIT）**：Node/TS 单进程双面——WS 设备面
   + MCP(stdio) agent 面 + APK 静态下发。环形日志 5000 行/设备按 seq 去重；
   配对码一次性握手；命令白名单（绝不放开任意 shell）。
5. **重装闭环的三道保险**：sha256 校验拒装错包；安装前**先 `SessionController.
   disconnect` 释放全部虚拟屏**（否则系统安装确认弹窗落到车机侧虚拟屏——
   AGENTS 已记录的坑）；安装前写 `resumePending` 标记，新版本首启动悬浮窗问
   「继续进入调试模式？」自动重连。

## 手机侧组件（app/src/remote/java/.../bridge/）

| 文件 | 职责 |
|---|---|
| `BridgeApp` | 入口：装 tap、自动重连/重装后续接询问 |
| `BridgePrefs` | 地址/配对码/设备ID/自动连/续接标记（独立 "bridge" prefs） |
| `WsClient` | 最小 RFC6455 客户端（连读线程 + 串行写 + 分片合并） |
| `BridgeClient` | 会话循环（指数退避重连）、配对、日志批量回补+500ms 实时批、消息分发 |
| `BridgeCommands` | 命令白名单：start/stop_connection、get_state、ping |
| `BridgeOverlay` | 可拖动悬浮提示窗（TYPE_APPLICATION_OVERLAY，文字+可选按钮） |
| `ReinstallFlow` | 下载/校验/提示/停会话/PackageInstaller |
| `BridgeSetupActivity` | 第二桌面入口：地址+配对码+状态+权限引导 |
| `InstallReceiver` | 安装结果：把系统确认 Intent 拉到手机主屏 |

## 服务端（cardeck-bridge，项目外层仓库）

MCP 工具：`bridge_status` / `logs_tail(device,lines)` / `logs_fetch(device,since)`
/ `cmd(device,name,args)` / `notify(device,text)` / `offer_apk(device,filePath,version)`。
环境变量 `PORT` / `PUBLIC_BASE` / `BRIDGE_PAIRING`；`.npmrc` 固定公有源。

## 已知代价与约定

- `.rd` 是新包名：Shizuku、通知使用权、投屏权限都要在该变体内**重新授权一次**
  （设置页有说明文案）。
- remote 变体不进 GitHub Release 主线（或明确标注实验），防日志外泄顾虑扩散。
- 日志含热点名/蓝牙地址，README 与 App 内均已向用户明示。
- 命令白名单扩充走 `BridgeCommands` 单点评审，禁止加任意 shell/反射类命令。

## 构建命令（flavor 化后）

```bash
./gradlew assembleStandardDebug    # 日常包（与旧 assembleDebug 等价）
./gradlew assembleRemoteDebug      # 远程调试包（…/remote/debug/app-remote-debug.apk）
./gradlew testStandardDebugUnitTest
./gradlew :app:lintStandardDebug   # pre-commit 钩子已同步
```

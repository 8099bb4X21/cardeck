# 车机 HID 输入：方向盘媒体键 + iDrive 旋钮（宝马 iD8.5）

> 2026-10-23。目标：方向盘切歌/暂停（全局媒体键）、iDrive 旋钮旋转与四向拨动（虚拟屏焦点导航）。

## 1. 链路（现状 = 已修好的部分）

```
车机 /info → hidDevices[] (uuid + hidDescriptor)
  → 会话建立时对**每一个** uuid 发 hidSetInputMode(mode=0)   ← 早就遍历了全部设备
  → 车机开始下发 hidSendReport（事件通道）/ 0x6802 AccessoryHIDReport（iAP2 隧道）
  → 按**设备描述符**分流（不按 uuid、不按车机型号）：
       Consumer 页 0x0C      → MediaKey（AudioManager 全局分发，无需焦点）
       Digitizer 页 0x0D     → 触摸（TouchRouter 坐标注入）
       Generic Desktop 0x01  → 旋钮/四向（本次新增，DPAD 注入虚拟屏）
```

**不要改成「按 uuid/名称猜设备」**：`uuid` 各车机任意（奥迪发 `"0"/"1"`、DiPlay 发整数），
描述符才是唯一稳定信号（`ConsumerKeys.parseDescriptor` / `HidNavigation.isNavigationDevice`）。

## 2. 媒体键（已有）

`ConsumerKeys` 解析描述符得到 Usage 数组，按 **Array/Variable 两种 HID 语义**分别解码
（Array：字节=下标；Variable：每 usage 1 bit），再映射 `KEYCODE_MEDIA_*`，
由 `SessionController.dispatchMediaKey` → `AudioManager.dispatchMediaKeyEvent` 全局分发
（焦点在哪都生效，车机界面停在导航也能切歌）。

## 3. 旋钮/四向（本次新增）

`input/HidNavigation` 判定导航设备：描述符有 Generic Desktop 页 0x01 且含
**Wheel(0x38) 或 Hat(0x39)**，且不是 Digitizer 页（触摸屏排除）。

报文解析（描述符缺失时按 2~4 字节兜底）：

| 位置 | 语义 |
|---|---|
| 末字节 | **有符号**旋钮步进（正=顺时针 → DPAD_DOWN，负 → DPAD_UP，单帧最多 5 步） |
| 前面的字节 | 按钮位图，**上升沿**触发：bit0=确认、1=上、2=下、3=左、4=右、5=返回 |
| Hat 设备（0x39，1 字节） | 低 4 位=8 向帽（0 上 / 2 右 / 4 下 / 6 左，其余=松开） |

注入走 `ICarInput.keyEvent(displayId, keyCode)`（UserService 的 `injectInputEvent`，
`KeyEvent.setDisplayId` 直指虚拟屏，毫秒级）；失败回退 `input -d <id> keyevent <code>`。
目标屏 = `VirtualLauncher.activeAppDisplayId()`（桌面网格 / 打开中的应用屏）。

**为什么不能全局分发 DPAD**：DPAD 需要焦点系统，全局分发会落到手机主屏；只有注入到
车机虚拟屏才能让车机桌面/应用响应。

## 4. 焦点高亮（旋钮可见性）

- `res/drawable/focus_ring.xml`：只在 `state_focused` 时画 3dp 青色描边；
- 网格单元（`GridActivity.PageHolder.bind`）：`isFocusable = true` +
  `foreground = focus_ring`（foreground 不覆盖代码设置的 background）；
- 按键事件会自动让窗口退出 touch mode（`focusableInTouchMode=false` 即可拿到焦点）。

**已知限制**：dock 是**独立 display**，DPAD 不会跨屏移动焦点——旋钮只能在应用屏内
移动。要支持「旋钮推到左边缘跳到 dock」需要 Launcher 侧拦截边缘 DPAD 并切屏/换焦点，
未实现（需要真机确认车机行为后再做）。

## 5. 键位校准（实车必做）

不同 iD8.5 固件的报文布局/位序可能不同（`S18A-23-xx` vs `24-xx`），也可能走
事件通道而非 0x6802。校准时：

1. 设置 → 显示 →「**HID 原始日志**」→ 开；
2. 依次按 方向盘下一首 / 上一首 / 播放暂停 / 旋钮左旋右旋 / 旋钮按下 / 四向；
3. 看 `files/main_debug.log`：

```
HID-RAW: uuid=0 bytes=05 desc=27B
EVENT: 车机媒体键 下一首（uuid=0 usage=0xb5 → keyCode=87）
HID-RAW: uuid=1 bytes=00 02 desc=34B
EVENT: 旋钮/导航键 下+下（uuid=1）
```

4. 若「下一首」打印的是 `usage=0xb5` 但没反应 → 问题在分发不在映射；
   若打印的字节与预期不符 → 按实际字节改 `ConsumerKeys.keyUsage` 映射表或
   `HidNavigation` 的位定义；若 `HID-RAW` 一条都没有 → 车机没发（多半是
   `hidSetInputMode` 未武装到该 uuid，或该车机把按键放在 iAP2 `0x6802`）。

## 6. 排错速查

| 现象 | 先看 |
|---|---|
| 完全没日志 | `/info` 有没有 hidDevices？`hidSetInputMode` 是否对每个 uuid 都发了 |
| 有 HID-RAW 但没反应 | 描述符是否被识别成 Consumer/导航（日志里的 `desc=` 字节数 + 解析结果） |
| 旋钮有日志但焦点不动 | 虚拟屏是否可聚焦视图（网格单元 `isFocusable`）、注入 displayId 是否正确 |
| 方向盘切歌无效 | `dispatchMediaKey` 是否被调用；系统当前媒体会话是否存在 |

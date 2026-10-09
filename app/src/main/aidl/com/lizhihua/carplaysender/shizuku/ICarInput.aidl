// Shizuku UserService 接口：跑在常驻 shell 进程中，以毫秒级延迟注入触摸事件。
// 相比每次 spawn `input` 命令（约 200-400ms），injectInputEvent 直连系统服务。
package com.lizhihua.carplaysender.shizuku;

interface ICarInput {
    boolean tap(int displayId, int x, int y);
    boolean swipe(int displayId, int startX, int startY, int endX, int endY, int durationMs);

    // 连续拖拽原语：整段滑动共用一次 DOWN，MOVE 逐事件直注（真实时间戳），
    // 应用侧速度跟踪器才能算出正确的滚动惯性。一次只支持单指。
    boolean pointerDown(int displayId, int x, int y);
    boolean pointerMove(int displayId, int x, int y);
    boolean pointerUp(int displayId, int x, int y);

    boolean ping();
    String diag();
}

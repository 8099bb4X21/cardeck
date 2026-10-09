// Shizuku UserService 接口：跑在常驻 shell 进程中，以毫秒级延迟注入触摸事件。
// 相比每次 spawn `input` 命令（约 200-400ms），injectInputEvent 直连系统服务。
package com.lizhihua.carplaysender.shizuku;

import android.view.Surface;

interface ICarInput {
    boolean tap(int displayId, int x, int y);
    boolean swipe(int displayId, int startX, int startY, int endX, int endY, int durationMs);

    // 连续拖拽原语：整段滑动共用一次 DOWN，MOVE 逐事件直注（真实时间戳），
    // 应用侧速度跟踪器才能算出正确的滚动惯性。一次只支持单指。
    boolean pointerDown(int displayId, int x, int y);
    boolean pointerMove(int displayId, int x, int y);
    boolean pointerUp(int displayId, int x, int y);

    // 受信虚拟屏（shell 权限）：第三方应用的新任务可落在其上，
    // 解决「应用内跳转的任务被重定向到手机主屏」问题（AOSP 安全策略）。
    int createDisplay(String name, int width, int height, int dpi, in Surface surface);
    boolean releaseDisplay(int displayId);

    boolean ping();
    String diag();
}

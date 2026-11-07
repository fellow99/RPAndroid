package com.managedfloating.app;

/**
 * 主进程暴露给 :node 进程（脚本执行器）的屏幕控制接口。
 */
interface IScreenControl {
    boolean tap(int x, int y);
    boolean swipe(int x1, int y1, int x2, int y2, int durMs);
    boolean launch(String pkg, String component);
    byte[] captureFrame();
    String listAppsJson();
}

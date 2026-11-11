package top.fellow99.rpandroid;

/**
 * 主进程暴露给 :node 进程（脚本执行器）的屏幕控制接口。
 */
interface IScreenControl {
    boolean tap(int x, int y);
    boolean swipe(int x1, int y1, int x2, int y2, int durMs);
    boolean launch(String pkg, String component);
    byte[] captureFrame();
    String listAppsJson();
    /** 注入按键（KeyEvent keycode）。 */
    boolean key(int code);
    /** 向焦点输入框写入文本（支持中文）。 */
    boolean text(String text);
    /** 屏幕/会话信息 JSON（w/h/dpi/hosted/sessionActive）。 */
    String info();
    /** 回传日志到主进程统一日志缓冲（供 REST /logs 与 MCP logs 观察）。 */
    oneway void log(String tag, String msg);
}

package com.termux.terminal;

/**
 * [Rustwright] 精简：模拟器核心只需要光标样式与日志回调；会话事件由 Rustwright 的 PtySession 自行处理。
 */
public interface TerminalSessionClient {

    Integer getTerminalCursorStyle();

    void onTerminalCursorStateChange(boolean state);

    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}

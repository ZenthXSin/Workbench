package io.github.zenthxsin.rustwright.fused

/** RuntimeServices: a separately implementable workbench capability. */
interface RuntimeServices {
    /** 环境状态：是否就绪、安装进度（0..1，未在安装为 null）、说明。 */
    fun env(): EnvStatus
    /** 还不能发任务的原因（如没填令牌），可以时为 null。 */
    fun blocker(): String?
    /** 首次启动的环境准备；不需要时为 null。 */
    fun setup(): SetupState?
    /** 后台运行要的权限（悬浮窗、电池、通知）：首次启动准备环境前先让用户打开。 */
    fun permissions(): List<io.github.zenthxsin.rustwright.core.runtime.KeepAliveItem> = emptyList()
    fun openPermission(id: String) {}

    /**
     * 启动动画里预热助手：提前拉起 Codex（app-server 进程、初始化、读模型列表，也会按配置连上 MCP），
     * 刷新福利和余额。都是异步的，立即返回。
     */
    fun preloadAgent() {}
}

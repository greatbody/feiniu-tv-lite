package ink.sunrui.feiniutv.store

/**
 * NAS 服务器配置（host + port + 可选展示名）。
 *
 * 设计原则：
 *   - 仅 LAN 直连，不与任何第三方服务交互
 *   - baseUrl 始终走 http（飞牛 NAS 内网默认无 TLS）
 *   - 一台 NAS 一份配置，切换服务器 = 覆盖写入
 */
object ServerStore {
    private const val K_HOST = "server_host"
    private const val K_PORT = "server_port"
    private const val K_NAME = "server_name"

    private const val DEFAULT_PORT = 5666  // 飞牛 NAS Web 默认端口；可被手动配置覆盖

    fun save(host: String, port: Int, displayName: String = host) {
        AppPrefs.get().edit()
            .putString(K_HOST, host.trim())
            .putInt(K_PORT, port)
            .putString(K_NAME, displayName.trim())
            .apply()
    }

    fun clear() {
        AppPrefs.get().edit()
            .remove(K_HOST)
            .remove(K_PORT)
            .remove(K_NAME)
            .apply()
    }

    fun isConfigured(): Boolean = !getHost().isNullOrBlank()

    fun getHost(): String? = AppPrefs.get().getString(K_HOST, null)
    fun getPort(): Int = AppPrefs.get().getInt(K_PORT, DEFAULT_PORT)
    fun getDisplayName(): String = AppPrefs.get().getString(K_NAME, getHost().orEmpty()) ?: ""

    // 业务接口 base：http://host:port/v
    fun getBaseUrl(): String {
        val h = getHost() ?: return ""
        return "http://$h:${getPort()}/v"
    }

    // 系统接口 root：http://host:port （不带 /v 前缀，用于 /api/v1/visit/* 但本 lite 不调用此类）
    fun getRootUrl(): String {
        val h = getHost() ?: return ""
        return "http://$h:${getPort()}"
    }
}

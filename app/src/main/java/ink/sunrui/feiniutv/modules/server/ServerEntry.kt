package ink.sunrui.feiniutv.modules.server

/**
 * 一台已发现的 NAS 设备（mDNS 或手动输入产出）。
 * 仅本地内存使用，不持久化为列表。
 */
data class ServerEntry(
    val displayName: String,
    val host: String,
    val port: Int
) {
    val address: String get() = "$host:$port"
}

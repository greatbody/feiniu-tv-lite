package ink.sunrui.feiniutv.util

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 取本机在 LAN 上的 IPv4 地址。
 *
 * 优先级：
 *   1. WifiManager（Wi-Fi 连接时最准）
 *   2. 网卡枚举（以太网盒子用，跳过 loopback、IPv6、docker0 等虚拟接口）
 *
 * 用于 [ink.sunrui.feiniutv.network.WebLoginServer] 生成 QR 中的链接。
 * 完全本地查询，不联网。
 */
object NetworkUtil {

    fun getLanIpv4(context: Context): String? {
        // 1) Wi-Fi 路径
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wifi?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                @Suppress("DEPRECATION")
                val ip = Formatter.formatIpAddress(ipInt)
                if (ip.isNotBlank() && ip != "0.0.0.0") return ip
            }
        } catch (_: Exception) {
            // ignore
        }

        // 2) 网卡枚举（盒子常走以太网）
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in ifaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name?.lowercase().orEmpty()
                // 跳过虚拟接口
                if (name.startsWith("docker") || name.startsWith("veth") ||
                    name.startsWith("tun") || name.startsWith("tap")) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
            // ignore
        }
        return null
    }
}

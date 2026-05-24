package ink.sunrui.feiniutv.modules.server

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import ink.sunrui.feiniutv.MainActivity
import ink.sunrui.feiniutv.databinding.ActivityScanDeviceBinding
import ink.sunrui.feiniutv.modules.login.LoginActivity
import ink.sunrui.feiniutv.store.ServerStore

/**
 * 扫描 NAS 设备：
 *   - 使用 Android NsdManager 监听 mDNS 服务类型 `_trim_media._tcp`（与官方一致）
 *   - 发现的设备实时塞进列表，OK 选中 → 写入 ServerStore → 跳 LoginActivity
 *   - 「手动输入」按钮 → AddDeviceActivity
 *   - 「重新扫描」按钮 → 重置列表，重新启动 discovery
 *
 * 严守约束：所有发现走本地 mDNS 广播，**不访问任何第三方服务器**。
 */
class ScanDeviceActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ScanDeviceActivity"
        private const val SERVICE_TYPE = "_trim_media._tcp"
        private const val SCAN_TIMEOUT_MS = 15_000L
    }

    private lateinit var binding: ActivityScanDeviceBinding
    private lateinit var adapter: ServerListAdapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discoveryActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ServerListAdapter { entry ->
            ServerStore.save(entry.host, entry.port, entry.displayName)
            goToLogin()
        }
        binding.serverList.apply {
            layoutManager = LinearLayoutManager(this@ScanDeviceActivity)
            adapter = this@ScanDeviceActivity.adapter
        }

        binding.manualButton.setOnClickListener {
            startActivity(Intent(this, AddDeviceActivity::class.java))
        }
        binding.rescanButton.setOnClickListener { restartDiscovery() }

        nsdManager = getSystemService(Context.NSD_SERVICE) as? NsdManager
        startDiscovery()
    }

    override fun onResume() {
        super.onResume()
        // 用户从 AddDeviceActivity 返回若已存了配置，直接进 Login
        if (ServerStore.isConfigured() && !isStateValidForLoginRouting()) {
            goToLogin()
        }
    }

    private fun isStateValidForLoginRouting(): Boolean {
        // 防御：onCreate 完成前别误跳
        return !this::binding.isInitialized
    }

    private fun startDiscovery() {
        val mgr = nsdManager ?: run {
            showSubtitleIdle("NSD 不可用（系统版本过低？）")
            return
        }
        if (discoveryActive) return

        adapter.clear()
        updateEmptyHint()
        binding.scanProgress.visibility = View.VISIBLE
        binding.subtitleText.text = getString(ink.sunrui.feiniutv.R.string.scan_subtitle)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "onStartDiscoveryFailed code=$errorCode")
                mainHandler.post { showSubtitleIdle("启动发现失败：$errorCode") }
                discoveryActive = false
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "onStopDiscoveryFailed code=$errorCode")
                discoveryActive = false
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.i(TAG, "Discovery started for $serviceType")
                discoveryActive = true
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.i(TAG, "Discovery stopped for $serviceType")
                discoveryActive = false
                mainHandler.post {
                    binding.scanProgress.visibility = View.GONE
                    if (adapter.isEmpty()) showSubtitleIdle(null)
                }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Service found: ${serviceInfo.serviceName} type=${serviceInfo.serviceType}")
                // 解析获取 host/port
                mgr.resolveService(serviceInfo, makeResolveListener())
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Service lost: ${serviceInfo.serviceName}")
                // 暂不在 UI 移除，避免发现-丢失-再发现的闪烁
            }
        }
        discoveryListener = listener
        try {
            mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "discoverServices threw", e)
            showSubtitleIdle("发现失败：${e.message}")
            discoveryActive = false
            return
        }

        // 兜底超时
        mainHandler.postDelayed({
            if (discoveryActive) stopDiscovery()
        }, SCAN_TIMEOUT_MS)
    }

    private fun makeResolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
            Log.w(TAG, "Resolve failed code=$errorCode service=${serviceInfo?.serviceName}")
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host?.hostAddress ?: return
            val port = serviceInfo.port.takeIf { it > 0 } ?: 5666
            val name = serviceInfo.serviceName.takeIf { !it.isNullOrBlank() } ?: host
            val entry = ServerEntry(name, host, port)
            Log.i(TAG, "Resolved: $entry")
            mainHandler.post {
                adapter.upsert(entry)
                updateEmptyHint()
            }
        }
    }

    private fun stopDiscovery() {
        val mgr = nsdManager ?: return
        val listener = discoveryListener ?: return
        try {
            mgr.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.w(TAG, "stopServiceDiscovery threw", e)
        }
        discoveryListener = null
        discoveryActive = false
        binding.scanProgress.visibility = View.GONE
    }

    private fun restartDiscovery() {
        stopDiscovery()
        startDiscovery()
    }

    private fun updateEmptyHint() {
        if (adapter.isEmpty()) {
            binding.emptyHint.visibility = View.VISIBLE
            binding.serverList.visibility = View.GONE
        } else {
            binding.emptyHint.visibility = View.GONE
            binding.serverList.visibility = View.VISIBLE
        }
    }

    private fun showSubtitleIdle(detail: String?) {
        binding.scanProgress.visibility = View.GONE
        binding.subtitleText.text = detail ?: getString(ink.sunrui.feiniutv.R.string.scan_subtitle_idle)
        updateEmptyHint()
    }

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        stopDiscovery()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

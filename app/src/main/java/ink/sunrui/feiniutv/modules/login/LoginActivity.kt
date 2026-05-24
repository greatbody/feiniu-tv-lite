package ink.sunrui.feiniutv.modules.login

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import ink.sunrui.feiniutv.AppConfig
import ink.sunrui.feiniutv.MainActivity
import ink.sunrui.feiniutv.R
import ink.sunrui.feiniutv.databinding.ActivityLoginBinding
import ink.sunrui.feiniutv.modules.server.ScanDeviceActivity
import ink.sunrui.feiniutv.network.NasApiClient
import ink.sunrui.feiniutv.network.WebLoginServer
import ink.sunrui.feiniutv.store.AccountStore
import ink.sunrui.feiniutv.store.ServerStore
import ink.sunrui.feiniutv.util.NetworkUtil
import ink.sunrui.feiniutv.util.QrUtil
import kotlin.concurrent.thread

/**
 * 登录页（QR 优先 + EditText 兜底）。
 *
 * 启动流程：
 *   1. onCreate 启动 [WebLoginServer] 监听 LAN 随机端口
 *   2. 取本机 LAN IPv4，拼 `http://<ip>:<port>/<nonce>`
 *   3. 调 [QrUtil.encode] 渲染 QR Bitmap → qrImage
 *   4. 状态栏显示「等待手机扫码…」
 *   5. 用户扫码 → 手机表单提交 → 服务回调 [doLogin]
 *      - 同时支持 TV 上 EditText 输入 → 「登录」按钮 → [doLogin]
 *   6. 登录成功 → 持久化 token → 跳 MainActivity
 *   7. 登录失败 → errorText 展示
 *
 * onDestroy 关闭 WebLoginServer。
 *
 * 严守约束：服务只监听 LAN，不连任何第三方。
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoginActivity"
    }

    private lateinit var binding: ActivityLoginBinding
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webServer: WebLoginServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.serverInfoText.text = getString(R.string.login_server_label) + ServerStore.getRootUrl()
        binding.usernameInput.setText(AccountStore.getUsername())
        binding.qrStatusText.text = getString(R.string.login_qr_waiting)

        binding.loginButton.setOnClickListener { onManualLoginClicked() }
        binding.changeServerButton.setOnClickListener { onChangeServerClicked() }

        startWebServer()

        // 焦点默认给 EditText 用户名（兼顾物理键盘用户）
        if (AccountStore.getUsername().isBlank()) {
            binding.usernameInput.requestFocus()
        } else {
            binding.passwordInput.requestFocus()
        }
    }

    // ============================== Web Server ==============================

    private fun startWebServer() {
        thread {
            val server = WebLoginServer(WebLoginServer.EPHEMERAL_PORT) { username, password ->
                // 来自手机表单：在主线程触发登录流程
                mainHandler.post {
                    binding.qrStatusText.text = getString(R.string.login_qr_submitted)
                    doLogin(username, password, fromQr = true)
                }
            }
            try {
                server.start(NanoHttpdTimeout.SOCKET_READ_TIMEOUT, /* daemon */ true)
                webServer = server
                val port = server.listeningPort
                val ip = NetworkUtil.getLanIpv4(this) ?: "127.0.0.1"
                val url = "http://$ip:$port/${server.currentNonce()}"
                Log.i(TAG, "WebLoginServer listening at $url")

                mainHandler.post { renderQr(url) }
            } catch (e: Exception) {
                Log.e(TAG, "WebLoginServer start failed", e)
                mainHandler.post { showQrUnavailable() }
            }
        }
    }

    private fun renderQr(url: String) {
        val bmp = QrUtil.encode(url, size = dp(240))
        if (bmp == null) {
            showQrUnavailable()
            return
        }
        binding.qrImage.setImageBitmap(bmp)
        binding.qrImage.visibility = View.VISIBLE
        binding.qrFallbackText.visibility = View.GONE
        binding.qrUrlText.text = url
        binding.qrUrlText.visibility = View.VISIBLE
    }

    private fun showQrUnavailable() {
        binding.qrImage.setImageDrawable(null)
        binding.qrImage.visibility = View.GONE
        binding.qrFallbackText.text = getString(R.string.login_qr_unavailable)
        binding.qrFallbackText.visibility = View.VISIBLE
        binding.qrUrlText.visibility = View.GONE
        binding.qrStatusText.text = ""
    }

    private fun stopWebServer() {
        webServer?.let {
            try { it.stop() } catch (_: Exception) {}
        }
        webServer = null
    }

    // ============================== Login submit ==============================

    private fun onManualLoginClicked() {
        val username = binding.usernameInput.text?.toString().orEmpty().trim()
        val password = binding.passwordInput.text?.toString().orEmpty()
        doLogin(username, password, fromQr = false)
    }

    private fun onChangeServerClicked() {
        ServerStore.clear()
        AccountStore.clearToken()
        startActivity(Intent(this, ScanDeviceActivity::class.java))
        finish()
    }

    private fun doLogin(username: String, password: String, fromQr: Boolean) {
        if (username.isBlank() || password.isBlank()) {
            showError(getString(R.string.login_empty))
            if (fromQr) binding.qrStatusText.text = getString(R.string.login_qr_waiting)
            return
        }

        AccountStore.saveUsername(username)
        AppConfig.PASSWORD_TRANSIENT = password

        setLoading(true)
        thread {
            val result = runCatching { NasApiClient.login() }
            mainHandler.post {
                setLoading(false)
                AppConfig.PASSWORD_TRANSIENT = ""
                val auth = result.getOrNull()
                if (result.isFailure) {
                    val ex = result.exceptionOrNull()
                    Log.w(TAG, "login threw", ex)
                    showError("登录异常：${ex?.javaClass?.simpleName}: ${ex?.message}")
                    if (fromQr) binding.qrStatusText.text = getString(R.string.login_qr_waiting)
                    return@post
                }
                if (auth != null && auth.ok && !auth.token.isNullOrBlank()) {
                    AccountStore.saveToken(auth.token)
                    goToMain()
                } else {
                    showError("登录失败：${auth?.error ?: "未知错误"}")
                    if (fromQr) binding.qrStatusText.text = getString(R.string.login_qr_waiting)
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.loginProgress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.loginButton.isEnabled = !loading
        binding.loginButton.alpha = if (loading) 0.6f else 1.0f
    }

    private fun showError(text: String) {
        binding.errorText.text = text
        binding.errorText.visibility = View.VISIBLE
    }

    private fun goToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        stopWebServer()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

/** NanoHTTPD socket 读超时常量本地封装，避免 import 全限定 */
private object NanoHttpdTimeout {
    const val SOCKET_READ_TIMEOUT = fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT
}

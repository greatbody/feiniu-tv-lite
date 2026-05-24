package ink.sunrui.feiniutv.modules.splash

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import ink.sunrui.feiniutv.MainActivity
import ink.sunrui.feiniutv.R
import ink.sunrui.feiniutv.modules.login.LoginActivity
import ink.sunrui.feiniutv.modules.server.ScanDeviceActivity
import ink.sunrui.feiniutv.store.AccountStore
import ink.sunrui.feiniutv.store.ServerStore

/**
 * 启动屏 + 路由：
 *   1. 未配置 NAS → ScanDeviceActivity
 *   2. 已配置 NAS、未登录（无 token） → LoginActivity
 *   3. 已配置 + 有 token → MainActivity
 *
 * 不做任何第三方网络访问，包括 update 检查、遥测、云中转等。
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // 短暂展示 Splash，给用户视觉反馈再跳转
        handler.postDelayed({ route() }, 800)
    }

    private fun route() {
        if (isFinishing) return
        val intent = when {
            !ServerStore.isConfigured() -> Intent(this, ScanDeviceActivity::class.java)
            !AccountStore.hasToken() -> Intent(this, LoginActivity::class.java)
            else -> Intent(this, MainActivity::class.java)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

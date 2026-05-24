package ink.sunrui.feiniutv.modules.server

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ink.sunrui.feiniutv.R
import ink.sunrui.feiniutv.databinding.ActivityAddDeviceBinding
import ink.sunrui.feiniutv.modules.login.LoginActivity
import ink.sunrui.feiniutv.store.ServerStore

/**
 * 手动添加 NAS：当 mDNS 扫描没结果时（路由器隔离/IPv4 多网段/盒子禁多播）的兜底。
 * 输入 host + port → 保存到 ServerStore → 跳 LoginActivity。
 *
 * 不做"测试连接"——为了减少额外网络往返；登录失败时由 LoginActivity 反馈错误。
 */
class AddDeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddDeviceBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 端口默认值
        binding.portInput.setText("5666")
        binding.hostInput.requestFocus()

        binding.saveButton.setOnClickListener { onSave() }
    }

    private fun onSave() {
        val host = binding.hostInput.text?.toString().orEmpty().trim()
        val portText = binding.portInput.text?.toString().orEmpty().trim()

        if (!isValidHost(host)) {
            Toast.makeText(this, R.string.add_invalid_host, Toast.LENGTH_SHORT).show()
            binding.hostInput.requestFocus()
            return
        }
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: 5666

        ServerStore.save(host, port, host)
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun isValidHost(host: String): Boolean {
        if (host.isBlank()) return false
        // 简单校验：IPv4 / hostname 字符；不做严格正则，免误伤
        return host.matches(Regex("^[A-Za-z0-9.\\-_]+$"))
    }
}

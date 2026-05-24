package ink.sunrui.okhttpprobe

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import ink.sunrui.okhttpprobe.databinding.ActivityMainBinding
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.testButton.isFocusable = true
        binding.testButton.isFocusableInTouchMode = true
        binding.testButton.requestFocus()

        binding.testButton.setOnClickListener {
            runProbe()
        }
        binding.testButton.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                runProbe()
                true
            } else {
                false
            }
        }

        appendLog("Ready. Press center/OK to test OkHttp.")
    }

    private fun runProbe() {
        appendLog("Starting OkHttp probe -> https://www.baidu.com")
        binding.testButton.isEnabled = false
        thread {
            val start = System.currentTimeMillis()
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .callTimeout(12, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("https://www.baidu.com")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val code = response.code()
                val body = response.body()?.string().orEmpty()
                val elapsed = System.currentTimeMillis() - start
                response.close()

                runOnUiThread {
                    appendLog("HTTP=$code elapsed=${elapsed}ms bodyLen=${body.length}")
                    appendLog("Preview: ${body.take(180)}")
                    binding.testButton.isEnabled = true
                    binding.testButton.requestFocus()
                }
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - start
                runOnUiThread {
                    appendLog("FAILED elapsed=${elapsed}ms ${e.javaClass.simpleName}: ${e.message}")
                    binding.testButton.isEnabled = true
                    binding.testButton.requestFocus()
                }
            }
        }
    }

    private fun appendLog(msg: String) {
        val line = "[${timeFmt.format(Date())}] $msg"
        val old = binding.logText.text?.toString().orEmpty()
        val combined = if (old.isEmpty()) line else "$old\n$line"
        val lines = combined.split('\n')
        val trimmed = if (lines.size > 120) lines.takeLast(120).joinToString("\n") else combined
        binding.logText.text = trimmed
        binding.logScroll.post {
            binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}

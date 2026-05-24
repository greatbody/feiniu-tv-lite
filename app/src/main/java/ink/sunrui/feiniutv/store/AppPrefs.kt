package ink.sunrui.feiniutv.store

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局 SharedPreferences 单例。
 *
 * 在 [ink.sunrui.feiniutv.LiteApplication.onCreate] 中调用 [init] 注入 Context。
 * 任何后续访问都不再需要 Context，简化 store 的 API。
 *
 * 仅本地持久化，绝不向第三方上报。
 */
object AppPrefs {
    private const val NAME = "feiniu_lite_prefs"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                }
            }
        }
    }

    fun get(): SharedPreferences =
        prefs ?: error("AppPrefs not initialized. Call AppPrefs.init(context) in Application.onCreate().")
}

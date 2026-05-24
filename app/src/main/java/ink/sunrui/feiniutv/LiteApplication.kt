package ink.sunrui.feiniutv

import android.util.Log
import androidx.multidex.MultiDexApplication
import ink.sunrui.feiniutv.store.AppPrefs

class LiteApplication : MultiDexApplication() {

    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("LiteApp", "FATAL CRASH on ${thread.name}", throwable)
        }
    }
}

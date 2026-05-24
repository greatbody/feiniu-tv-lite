package ink.sunrui.feiniutv.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ink.sunrui.feiniutv.MediaItem
import ink.sunrui.feiniutv.MediaLibrary

object MediaCacheStore {
    private const val TAG = "MediaCacheStore"
    private const val PREF = "feiniu_tv_cache"
    private const val CACHE_VERSION = 3
    private const val KEY_CACHE_VERSION = "cache_version"
    private const val KEY_LIB_TS = "libraries_ts"
    private const val KEY_LIB_DATA = "libraries_data"
    private const val KEY_ITEMS_TS_PREFIX = "items_ts_"
    private const val KEY_ITEMS_DATA_PREFIX = "items_data_"
    private const val CACHE_TTL_MS = 2 * 60 * 60 * 1000L

    private val gson = Gson()

    data class CacheResult<T>(
        val data: T?,
        val hit: Boolean
    )

    fun readLibraries(context: Context): CacheResult<List<MediaLibrary>> {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val version = prefs.getInt(KEY_CACHE_VERSION, 0)
        if (version != CACHE_VERSION) {
            Log.i(TAG, "cache miss: version mismatch stored=$version expected=$CACHE_VERSION")
            return CacheResult(null, false)
        }
        val ts = prefs.getLong(KEY_LIB_TS, 0L)
        val now = System.currentTimeMillis()
        if (ts <= 0 || now - ts > CACHE_TTL_MS) {
            Log.i(TAG, "cache miss: expired or empty ts=$ts ageMs=${now - ts}")
            return CacheResult(null, false)
        }
        val raw = prefs.getString(KEY_LIB_DATA, null) ?: return CacheResult(null, false)
        return try {
            val type = object : TypeToken<List<MediaLibrary>>() {}.type
            val list: List<MediaLibrary> = gson.fromJson(raw, type)
            Log.i(TAG, "cache hit: libraries=${list.size}")
            CacheResult(list, true)
        } catch (e: Exception) {
            Log.e(TAG, "cache parse error", e)
            CacheResult(null, false)
        }
    }

    fun writeLibraries(context: Context, libraries: List<MediaLibrary>) {
        Log.i(TAG, "cache write libraries=${libraries.size}")
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_CACHE_VERSION, CACHE_VERSION)
            .putLong(KEY_LIB_TS, System.currentTimeMillis())
            .putString(KEY_LIB_DATA, gson.toJson(libraries))
            .apply()
    }

    fun readItems(context: Context, libraryGuid: String): CacheResult<List<MediaItem>> {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val version = prefs.getInt(KEY_CACHE_VERSION, 0)
        if (version != CACHE_VERSION) {
            Log.i(TAG, "items cache miss: version mismatch stored=$version expected=$CACHE_VERSION guid=$libraryGuid")
            return CacheResult(null, false)
        }
        val ts = prefs.getLong(KEY_ITEMS_TS_PREFIX + libraryGuid, 0L)
        val now = System.currentTimeMillis()
        if (ts <= 0 || now - ts > CACHE_TTL_MS) {
            Log.i(TAG, "items cache miss: expired or empty guid=$libraryGuid ts=$ts ageMs=${now - ts}")
            return CacheResult(null, false)
        }
        val raw = prefs.getString(KEY_ITEMS_DATA_PREFIX + libraryGuid, null) ?: return CacheResult(null, false)
        return try {
            val type = object : TypeToken<List<MediaItem>>() {}.type
            val list: List<MediaItem> = gson.fromJson(raw, type)
            Log.i(TAG, "items cache hit: guid=$libraryGuid size=${list.size}")
            CacheResult(list, true)
        } catch (e: Exception) {
            Log.e(TAG, "items cache parse error guid=$libraryGuid", e)
            CacheResult(null, false)
        }
    }

    fun writeItems(context: Context, libraryGuid: String, items: List<MediaItem>) {
        Log.i(TAG, "items cache write guid=$libraryGuid size=${items.size}")
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_CACHE_VERSION, CACHE_VERSION)
            .putLong(KEY_ITEMS_TS_PREFIX + libraryGuid, System.currentTimeMillis())
            .putString(KEY_ITEMS_DATA_PREFIX + libraryGuid, gson.toJson(items))
            .apply()
    }
}

package ink.sunrui.feiniutv.ui.home

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ink.sunrui.feiniutv.AppConfig
import ink.sunrui.feiniutv.MediaItem
import ink.sunrui.feiniutv.MediaLibrary
import ink.sunrui.feiniutv.network.NasApiClient
import ink.sunrui.feiniutv.store.AccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 主页 ViewModel。
 *
 * 数据源：仅当前已配置的 NAS，绝不访问任何第三方服务。
 *
 * 启动策略：
 *   1. 若 [AccountStore.hasToken]，直接用缓存 token 拉媒体库（跳过登录请求）
 *   2. 若任意请求 401/无效 → 清 token，发 [tokenExpired] 信号，由 Activity 路由回登录
 *   3. 登录成功后把 token 持久化到 [AccountStore]
 */
class HomeViewModel : ViewModel() {

    private val TAG = "HomeViewModel"

    val loginStatus = MutableLiveData<Boolean>()
    val libraryList = MutableLiveData<List<MediaLibrary>>()
    val mediaItemList = MutableLiveData<List<MediaItem>>()
    val errorMessage = MutableLiveData<String>()
    val logEvent = MutableLiveData<String>()

    /** token 失效（401 或登录拒绝）；Activity 观察并跳回 LoginActivity */
    val tokenExpired = MutableLiveData<Boolean>()

    /** 用于 BrowseActivity（已删）的全库聚合，暂保留接口以防外部依赖 */
    val allLibraryItems = MutableLiveData<List<Pair<MediaLibrary, List<MediaItem>>>>()

    private var token: String = ""

    fun getToken(): String = token

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logEvent.postValue(msg)
    }

    /**
     * 主入口：
     *   - 有缓存 token → 直接拉库
     *   - 无 token → 走 login（凭 AppConfig.USERNAME + PASSWORD_TRANSIENT）
     */
    fun start() {
        val cached = AccountStore.getToken()
        if (cached.isNotBlank()) {
            log("Using cached token len=${cached.length}")
            token = cached
            viewModelScope.launch(Dispatchers.IO) { fetchLibraries() }
            return
        }
        if (AppConfig.PASSWORD_TRANSIENT.isBlank()) {
            log("No token, no password — should not happen if LoginActivity flow is followed")
            tokenExpired.postValue(true)
            return
        }
        login()
    }

    /** 兼容老的 MainActivity.initData() 直接调 login() 的写法 */
    fun login() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val startMs = System.currentTimeMillis()
                log("login() calling NasApiClient.login()")
                val auth = withTimeoutOrNull(20000L) {
                    NasApiClient.loginSuspend()
                }
                val elapsedMs = System.currentTimeMillis() - startMs
                if (auth == null) {
                    postError("Login timeout after ${elapsedMs}ms")
                    return@launch
                }
                log("login result in ${elapsedMs}ms ok=${auth.ok} tokenLen=${auth.token?.length ?: 0} error=${auth.error}")
                if (auth.ok && !auth.token.isNullOrBlank()) {
                    token = auth.token
                    AccountStore.saveToken(auth.token)
                    // 密码用完即弃
                    AppConfig.PASSWORD_TRANSIENT = ""
                    withContext(Dispatchers.Main) {
                        loginStatus.value = true
                    }
                    fetchLibraries()
                } else {
                    AppConfig.PASSWORD_TRANSIENT = ""
                    postError("Login failed: ${auth.error ?: "unknown"}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login exception", e)
                AppConfig.PASSWORD_TRANSIENT = ""
                postError("Login exception: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private suspend fun fetchLibraries() {
        try {
            log("fetchLibraries via NasApiClient")
            val result = NasApiClient.fetchMediaLibraries(token)
            result.onSuccess { libs ->
                log("Libraries loaded: ${libs.size} -> ${libs.map { it.name }}")
                withContext(Dispatchers.Main) {
                    libraryList.value = libs
                    loginStatus.value = true
                }
            }.onFailure { err ->
                if (isAuthError(err.message)) {
                    handleTokenExpired("fetchLibraries: ${err.message}")
                } else {
                    postError("fetchLibraries failed: ${err.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchLibraries exception", e)
            postError("fetchLibraries exception: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun fetchMediaItems(ancestorGuid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                log("fetchMediaItems guid=$ancestorGuid via NasApiClient")
                val result = NasApiClient.fetchMediaItems(token, ancestorGuid)
                result.onSuccess { items ->
                    log("MediaItems loaded: ${items.size}")
                    withContext(Dispatchers.Main) {
                        mediaItemList.value = items
                    }
                }.onFailure { err ->
                    if (isAuthError(err.message)) {
                        handleTokenExpired("fetchMediaItems: ${err.message}")
                    } else {
                        postError("fetchMediaItems failed: ${err.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchMediaItems exception", e)
                postError("fetchMediaItems exception: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    fun fetchAllLibraryItems() {
        viewModelScope.launch(Dispatchers.IO) {
            val libs = libraryList.value
            if (libs.isNullOrEmpty()) {
                log("fetchAllLibraryItems: no libraries loaded yet")
                return@launch
            }
            log("fetchAllLibraryItems: fetching items for ${libs.size} libraries in parallel")
            val results = libs.map { library ->
                async {
                    val result = NasApiClient.fetchMediaItems(token, library.guid)
                    val items = result.getOrNull() ?: emptyList()
                    Pair(library, items)
                }
            }.awaitAll()

            val nonEmpty = results.filter { it.second.isNotEmpty() }
            withContext(Dispatchers.Main) {
                allLibraryItems.value = nonEmpty
            }
        }
    }

    /**
     * 简单识别 401 / unauthorized / invalid token —— NasApiClient 把 HTTP code 拼进 message。
     */
    private fun isAuthError(msg: String?): Boolean {
        if (msg.isNullOrBlank()) return false
        val lower = msg.lowercase()
        return lower.contains("401") ||
            lower.contains("unauthorized") ||
            lower.contains("invalid token") ||
            lower.contains("token") && lower.contains("expired")
    }

    private suspend fun handleTokenExpired(detail: String) {
        log("Token expired: $detail")
        AccountStore.clearToken()
        token = ""
        withContext(Dispatchers.Main) {
            tokenExpired.value = true
            errorMessage.value = "登录已过期，请重新登录"
        }
    }

    private suspend fun postError(msg: String) {
        log("ERROR: $msg")
        withContext(Dispatchers.Main) {
            errorMessage.value = msg
            loginStatus.value = false
        }
    }
}

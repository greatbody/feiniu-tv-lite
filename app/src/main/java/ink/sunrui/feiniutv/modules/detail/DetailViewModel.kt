package ink.sunrui.feiniutv.modules.detail

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ink.sunrui.feiniutv.Episode
import ink.sunrui.feiniutv.ItemDetail
import ink.sunrui.feiniutv.Season
import ink.sunrui.feiniutv.network.NasApiClient
import ink.sunrui.feiniutv.store.AccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 详情页 ViewModel。
 *
 * 加载流程（按 type 分叉）：
 *   1. fetchItemDetail(itemGuid) → detail
 *   2. 根据 detail.type 决定后续：
 *      - Movie / Video / Episode → 不再拉，DetailActivity 显示 Play 按钮即可
 *      - TV → fetchSeasons(itemGuid) → seasons；若仅 1 季自动 selectSeason(0)；多季由 UI 触发
 *      - Season → fetchEpisodes(itemGuid) 一步到位
 *
 * Token 直接从 AccountStore 取。
 */
class DetailViewModel : ViewModel() {

    companion object {
        private const val TAG = "DetailViewModel"
    }

    // 所有 LiveData 标记为可空：MutableLiveData 在 Java 层 value 始终可能为 null（初始未赋值时）。
    // 这同时绕开 lint 的 NullSafeMutableLiveData 误报（lintVitalRelease 会因此 fail）。
    val detail = MutableLiveData<ItemDetail?>()
    val seasons = MutableLiveData<List<Season>?>()
    val episodes = MutableLiveData<List<Episode>?>()      // 当前展示的季的剧集
    val selectedSeasonGuid = MutableLiveData<String?>()   // 当前选中的季 guid（多季选择器用）
    val errorMessage = MutableLiveData<String?>()
    val loading = MutableLiveData<Boolean?>()

    // 收藏 / 已看：与服务器同步的乐观本地状态。每次切换调 API，失败回滚。
    val isFavorite = MutableLiveData<Boolean?>()
    val isWatched = MutableLiveData<Boolean?>()
    val toggleBusy = MutableLiveData<Boolean?>()  // 防止狂按
    val toggleError = MutableLiveData<String?>()

    fun load(itemGuid: String) {
        loading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val token = AccountStore.getToken()
            if (token.isBlank()) {
                postError("未登录")
                return@launch
            }
            val detailResult = NasApiClient.fetchItemDetail(token, itemGuid)
            val d = detailResult.getOrNull()
            if (d == null) {
                postError(detailResult.exceptionOrNull()?.message ?: "加载详情失败")
                return@launch
            }
            withContext(Dispatchers.Main) {
                detail.value = d
                isFavorite.value = d.isFavorite
                isWatched.value = d.isWatched
                loading.value = false
            }

            // 类型分叉
            when (d.type) {
                "TV" -> loadSeasons(token, itemGuid)
                "Season" -> loadEpisodes(token, itemGuid, asSingleSeason = true)
                // Movie / Video / Episode → 无需追加 API
            }
        }
    }

    private suspend fun loadSeasons(token: String, tvGuid: String) {
        val result = NasApiClient.fetchSeasons(token, tvGuid)
        val list = result.getOrNull().orEmpty()
        withContext(Dispatchers.Main) {
            seasons.value = list
        }
        if (list.isNotEmpty()) {
            // 默认选第一季
            selectSeason(list.first().guid)
        }
    }

    /** UI 切换季时调用 */
    fun selectSeason(seasonGuid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val token = AccountStore.getToken()
            if (token.isBlank()) {
                postError("未登录")
                return@launch
            }
            withContext(Dispatchers.Main) {
                selectedSeasonGuid.value = seasonGuid
            }
            loadEpisodes(token, seasonGuid, asSingleSeason = false)
        }
    }

    private suspend fun loadEpisodes(token: String, seasonGuid: String, asSingleSeason: Boolean) {
        val result = NasApiClient.fetchEpisodes(token, seasonGuid)
        val list = result.getOrNull().orEmpty()
        withContext(Dispatchers.Main) {
            episodes.value = list
            if (asSingleSeason) {
                // type=Season 直接进时，没有 season 列表；标记当前 season 为 selected 便于 UI 兜底
                selectedSeasonGuid.value = seasonGuid
            }
        }
    }

    private suspend fun postError(msg: String) {
        Log.w(TAG, "ERROR: $msg")
        withContext(Dispatchers.Main) {
            loading.value = false
            errorMessage.value = msg
        }
    }

    /** 切换收藏：乐观更新本地状态，失败回滚并通知 UI */
    fun toggleFavorite(itemGuid: String) {
        if (toggleBusy.value == true) return
        val current = isFavorite.value ?: false
        toggleBusy.value = true
        isFavorite.value = !current  // 乐观更新
        viewModelScope.launch(Dispatchers.IO) {
            val token = AccountStore.getToken()
            val result = if (current) NasApiClient.removeFavorite(token, itemGuid)
            else NasApiClient.addFavorite(token, itemGuid)
            withContext(Dispatchers.Main) {
                toggleBusy.value = false
                if (result.isFailure) {
                    isFavorite.value = current  // 回滚
                    toggleError.value = "收藏失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                }
            }
        }
    }

    /** 切换已看 */
    fun toggleWatched(itemGuid: String) {
        if (toggleBusy.value == true) return
        val current = isWatched.value ?: false
        toggleBusy.value = true
        isWatched.value = !current
        viewModelScope.launch(Dispatchers.IO) {
            val token = AccountStore.getToken()
            val result = if (current) NasApiClient.unmarkWatched(token, itemGuid)
            else NasApiClient.markWatched(token, itemGuid)
            withContext(Dispatchers.Main) {
                toggleBusy.value = false
                if (result.isFailure) {
                    isWatched.value = current
                    toggleError.value = "标记已看失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                }
            }
        }
    }
}

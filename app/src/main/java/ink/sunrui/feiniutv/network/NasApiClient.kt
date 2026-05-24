package ink.sunrui.feiniutv.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import ink.sunrui.feiniutv.AppConfig
import ink.sunrui.feiniutv.MediaItem
import ink.sunrui.feiniutv.MediaLibrary
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

object NasApiClient {
    private const val TAG = "NasApiClient"
    private val gson = Gson()
    private val JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8")

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    data class AuthResult(val ok: Boolean, val token: String?, val error: String?)

    data class Quality(val resolution: String, val bitrate: Long)

    fun login(): AuthResult {
        return try {
            val request = buildLoginRequest()

            val response = httpClient.newCall(request).execute()
            parseLoginResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "login exception", e)
            authError(e)
        }
    }

    suspend fun loginSuspend(): AuthResult {
        return suspendCancellableCoroutine { cont ->
            val request = buildLoginRequest()
            val call = httpClient.newCall(request)

            cont.invokeOnCancellation {
                call.cancel()
            }

            call.enqueue(object : Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (!cont.isCancelled) {
                        Log.e(TAG, "login async exception", e)
                        cont.resume(authError(e))
                    }
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    if (cont.isCancelled) {
                        response.close()
                        return
                    }
                    val result = try {
                        parseLoginResponse(response)
                    } catch (e: Exception) {
                        Log.e(TAG, "login async parse exception", e)
                        authError(e)
                    }
                    cont.resume(result)
                }
            })
        }
    }

    private fun buildLoginRequest(): Request {
        val url = "${normalize(AppConfig.BASE_URL)}/api/v1/login"
        Log.i(TAG, "login request url=$url user=${AppConfig.USERNAME}")

        val body = JsonObject().apply {
            addProperty("username", AppConfig.USERNAME)
            addProperty("password", AppConfig.PASSWORD)
        }.toString()

        return Request.Builder()
            .url(url)
            .post(RequestBody.create(JSON_MEDIA_TYPE, body))
            .build()
    }

    private fun parseLoginResponse(response: Response): AuthResult {
        response.use {
            val code = it.code()
            val raw = it.body()?.string() ?: ""
            Log.i(TAG, "login response http=$code bodyPreview=${raw.take(300)}")
            if (code !in 200..299) {
                return AuthResult(false, null, "HTTP $code")
            }

            val root = gson.fromJson(raw, JsonObject::class.java)
            val bizCode = root.get("code")?.takeUnless { item -> item.isJsonNull }?.asInt ?: -1
            if (bizCode != 0) {
                val msg = root.get("msg")?.takeUnless { item -> item.isJsonNull }?.asString ?: "Auth Failed"
                return AuthResult(false, null, msg)
            }

            val dataElem = root.get("data")
            val data = if (dataElem == null || dataElem.isJsonNull) null else dataElem.asJsonObject
            val token = data?.get("token")?.takeUnless { item -> item.isJsonNull }?.asString
            return if (token.isNullOrBlank()) {
                AuthResult(false, null, "Empty token")
            } else {
                AuthResult(true, token, null)
            }
        }
    }

    private fun authError(e: Exception): AuthResult {
        val detail = buildString {
            append(e.javaClass.name).append(": ").append(e.message)
            e.cause?.let { cause -> append(" caused by ").append(cause.javaClass.name).append(": ").append(cause.message) }
            append(" | BASE_URL=").append(AppConfig.BASE_URL)
            append(" USER=").append(AppConfig.USERNAME)
        }
        return AuthResult(false, null, detail)
    }

    // 拉取条目详情（用于详情页）。GET /v/api/v1/item/{guid}
    //
    // 对刮削过的条目返回完整元数据；原始视频很多字段为空——全部做空安全处理。
    // 海报/背景图路径已由 posterLink() 拼成绝对 URL，UI 层直接喂给 Glide（仍需 token header）。
    fun fetchItemDetail(token: String, itemGuid: String): Result<ink.sunrui.feiniutv.ItemDetail> {
        return try {
            Log.i(TAG, "fetchItemDetail start guid=$itemGuid")
            val raw = getJson("${normalize(AppConfig.BASE_URL)}/api/v1/item/$itemGuid", token)
            val root = gson.fromJson(raw, JsonObject::class.java)
            val code = root.get("code")?.takeUnless { it.isJsonNull }?.asInt ?: -1
            if (code != 0) {
                val msg = root.get("msg")?.takeUnless { it.isJsonNull }?.asString ?: "item/{guid} failed"
                return Result.failure(IllegalStateException("item/$itemGuid: $msg (code=$code)"))
            }
            val data = root.getAsJsonObject("data")
                ?: return Result.failure(IllegalStateException("item/$itemGuid: empty data"))

            fun s(k: String): String =
                data.get(k)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            fun iv(k: String, default: Int = 0): Int =
                data.get(k)?.takeUnless { it.isJsonNull }?.asInt ?: default
            fun dv(k: String, default: Double = 0.0): Double =
                try { s(k).toDoubleOrNull() ?: default } catch (_: Exception) { default }
            fun arr(k: String): List<String> =
                data.getAsJsonArray(k)?.mapNotNull { e ->
                    e?.takeUnless { it.isJsonNull }?.asString
                } ?: emptyList()

            val poster = s("posters")
            val backdrop = s("backdrops")
            val mediaStream = data.getAsJsonObject("media_stream")
            val resolutions = mediaStream?.getAsJsonArray("resolutions")
                ?.mapNotNull { e -> e?.takeUnless { it.isJsonNull }?.asString } ?: emptyList()
            val audioTypes = mediaStream?.getAsJsonArray("audio_type")
                ?.mapNotNull { e -> e?.takeUnless { it.isJsonNull }?.asString } ?: emptyList()

            // 详情页标题：scraped 条目（有 imdb/tmdb/overview）保留原 title；未刮削的视频清洗文件名
            val rawDetailTitle = s("title").ifBlank { s("original_title") }
            val hasScrapedDetail = s("imdb_id").isNotBlank() ||
                s("tmdb_id").isNotBlank() ||
                s("tvdb_id").isNotBlank() ||
                s("overview").isNotBlank()
            val cleanTitle = if (hasScrapedDetail) rawDetailTitle else cleanFilenameTitle(rawDetailTitle)

            val detail = ink.sunrui.feiniutv.ItemDetail(
                guid = s("guid").ifBlank { itemGuid },
                // 注意：title/original_title 可能都空——保持空串让 UI 用 MediaItem 兜底
                title = cleanTitle,
                originalTitle = s("original_title"),
                overview = s("overview"),
                posterUrl = if (poster.isNotBlank()) posterLink(poster) else "",
                backdropUrl = if (backdrop.isNotBlank()) posterLink(backdrop) else "",
                voteAverage = dv("vote_average"),
                releaseDate = s("release_date").ifBlank { s("air_date") },
                runtimeMinutes = iv("runtime"),
                resolutions = resolutions,
                audioTypes = audioTypes,
                countries = arr("production_countries"),
                isFavorite = iv("is_favorite") == 1,
                isWatched = iv("is_watched") == 1,
                canPlay = iv("can_play", 1) == 1,
                type = s("type"),
                ancestorName = s("ancestor_name")
            )
            Log.i(TAG, "fetchItemDetail success guid=$itemGuid title=${detail.title} hasBackdrop=${detail.backdropUrl.isNotBlank()}")
            Result.success(detail)
        } catch (e: Exception) {
            Log.e(TAG, "fetchItemDetail exception guid=$itemGuid", e)
            Result.failure(e)
        }
    }

    // 拉某 TV 的所有季。GET /v/api/v1/season/list/{tv_guid}
    // 返回的每一项是一个 Season（type=Season），其 guid 用于 fetchEpisodes。
    fun fetchSeasons(token: String, tvGuid: String): Result<List<ink.sunrui.feiniutv.Season>> {
        return try {
            Log.i(TAG, "fetchSeasons start tvGuid=$tvGuid")
            val raw = getJson("${normalize(AppConfig.BASE_URL)}/api/v1/season/list/$tvGuid", token)
            val root = gson.fromJson(raw, JsonObject::class.java)
            val code = root.get("code")?.takeUnless { it.isJsonNull }?.asInt ?: -1
            if (code != 0) {
                val msg = root.get("msg")?.takeUnless { it.isJsonNull }?.asString ?: "season/list failed"
                return Result.failure(IllegalStateException("season/list: $msg (code=$code)"))
            }
            val arr = root.getAsJsonArray("data")
            val result = mutableListOf<ink.sunrui.feiniutv.Season>()
            arr?.forEach { elem ->
                val obj = elem.asJsonObject
                val guid = obj.get("guid")?.takeUnless { it.isJsonNull }?.asString ?: return@forEach
                val seasonNumber = obj.get("season_number")?.takeUnless { it.isJsonNull }?.asInt ?: 0
                val titleRaw = obj.get("title")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                val title = titleRaw.ifBlank { if (seasonNumber > 0) "第 $seasonNumber 季" else "未命名" }
                val episodeCount = obj.get("local_number_of_episodes")?.takeUnless { it.isJsonNull }?.asInt
                    ?: obj.get("number_of_episodes")?.takeUnless { it.isJsonNull }?.asInt ?: 0
                val poster = obj.get("poster")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                result += ink.sunrui.feiniutv.Season(
                    guid = guid,
                    title = title,
                    seasonNumber = seasonNumber,
                    episodeCount = episodeCount,
                    posterUrl = if (poster.isNotBlank()) posterLink(poster) else ""
                )
            }
            // 按季号排序
            result.sortBy { it.seasonNumber }
            Log.i(TAG, "fetchSeasons success size=${result.size}")
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "fetchSeasons exception tvGuid=$tvGuid", e)
            Result.failure(e)
        }
    }

    // 拉某季的所有集。GET /v/api/v1/episode/list/{season_guid}
    fun fetchEpisodes(token: String, seasonGuid: String): Result<List<ink.sunrui.feiniutv.Episode>> {
        return try {
            Log.i(TAG, "fetchEpisodes start seasonGuid=$seasonGuid")
            val raw = getJson("${normalize(AppConfig.BASE_URL)}/api/v1/episode/list/$seasonGuid", token)
            val root = gson.fromJson(raw, JsonObject::class.java)
            val code = root.get("code")?.takeUnless { it.isJsonNull }?.asInt ?: -1
            if (code != 0) {
                val msg = root.get("msg")?.takeUnless { it.isJsonNull }?.asString ?: "episode/list failed"
                return Result.failure(IllegalStateException("episode/list: $msg (code=$code)"))
            }
            val arr = root.getAsJsonArray("data")
            val result = mutableListOf<ink.sunrui.feiniutv.Episode>()
            arr?.forEach { elem ->
                val obj = elem.asJsonObject
                val guid = obj.get("guid")?.takeUnless { it.isJsonNull }?.asString ?: return@forEach
                val title = obj.get("title")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                val seasonNum = obj.get("season_number")?.takeUnless { it.isJsonNull }?.asInt ?: 0
                val epNum = obj.get("episode_number")?.takeUnless { it.isJsonNull }?.asInt ?: 0
                val overview = obj.get("overview")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                val poster = obj.get("poster")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                val duration = obj.get("duration")?.takeUnless { it.isJsonNull }?.asInt ?: 0
                val airDate = obj.get("air_date")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                val watched = obj.get("watched")?.takeUnless { it.isJsonNull }?.asInt ?: 0
                val watchedTs = obj.get("watched_ts")?.takeUnless { it.isJsonNull }?.asInt ?: 0
                result += ink.sunrui.feiniutv.Episode(
                    guid = guid,
                    title = title,
                    seasonNumber = seasonNum,
                    episodeNumber = epNum,
                    overview = overview,
                    posterUrl = if (poster.isNotBlank()) posterLink(poster) else "",
                    duration = duration,
                    airDate = airDate,
                    watched = watched == 1,
                    watchedTs = watchedTs
                )
            }
            // 按集号排序
            result.sortBy { it.episodeNumber }
            Log.i(TAG, "fetchEpisodes success size=${result.size}")
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "fetchEpisodes exception seasonGuid=$seasonGuid", e)
            Result.failure(e)
        }
    }

    fun fetchMediaLibraries(token: String): Result<List<MediaLibrary>> {
        return try {
            Log.i(TAG, "fetchMediaLibraries start tokenLen=${token.length}")
            val albumsRaw = getJson("${normalize(AppConfig.BASE_URL)}/api/v1/mediadb/list", token)
            val albumsRoot = gson.fromJson(albumsRaw, JsonObject::class.java)
            val albumsCode = albumsRoot.get("code")?.takeUnless { it.isJsonNull }?.asInt ?: -1
            if (albumsCode != 0) {
                val msg = albumsRoot.get("msg")?.takeUnless { it.isJsonNull }?.asString ?: "mediadb/list failed"
                Log.e(TAG, "fetchMediaLibraries mediadb/list bizError code=$albumsCode msg=$msg")
                return Result.failure(IllegalStateException("mediadb/list: $msg (code=$albumsCode)"))
            }

            val sumRaw = getJson("${normalize(AppConfig.BASE_URL)}/api/v1/mediadb/sum", token)
            val sumRoot = gson.fromJson(sumRaw, JsonObject::class.java)
            val sumCode = sumRoot.get("code")?.takeUnless { it.isJsonNull }?.asInt ?: -1
            val sumMap = mutableMapOf<String, Int>()
            if (sumCode == 0) {
                val sumData = sumRoot.getAsJsonObject("data")
                sumData?.entrySet()?.forEach { entry ->
                    val value = entry.value
                    if (value != null && !value.isJsonNull) {
                        val intValue = try {
                            value.asInt
                        } catch (_: Exception) {
                            null
                        }
                        if (intValue != null) {
                            sumMap[entry.key] = intValue
                        }
                    }
                }
            } else {
                Log.w(TAG, "fetchMediaLibraries mediadb/sum bizCode=$sumCode raw=${sumRaw.take(400)}")
            }

            val albums = albumsRoot.getAsJsonArray("data")
            val libraries = mutableListOf<MediaLibrary>()
            albums?.forEach { elem ->
                val obj = elem.asJsonObject
                val guid = obj.get("guid")?.takeUnless { it.isJsonNull }?.asString ?: return@forEach
                val name = obj.get("title")?.takeUnless { it.isJsonNull }?.asString
                    ?: obj.get("name")?.takeUnless { it.isJsonNull }?.asString
                    ?: guid
                val count = sumMap[guid]
                    ?: obj.get("item_count")?.takeUnless { it.isJsonNull }?.asInt
                    ?: obj.get("media_count")?.takeUnless { it.isJsonNull }?.asInt
                    ?: obj.get("count")?.takeUnless { it.isJsonNull }?.asInt
                    ?: 0
                libraries.add(MediaLibrary(guid = guid, name = name, itemCount = count))
            }
            if (libraries.isEmpty()) Result.failure(IllegalStateException("No media libraries"))
            else {
                Log.i(TAG, "fetchMediaLibraries success size=${libraries.size}")
                Result.success(libraries)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchMediaLibraries exception", e)
            Result.failure(e)
        }
    }

    fun fetchMediaItems(token: String, ancestorGuid: String): Result<List<MediaItem>> {
        return try {
            Log.i(TAG, "fetchMediaItems start ancestorGuid=$ancestorGuid tokenLen=${token.length}")
            val listBody = JsonObject().apply {
                addProperty("ancestor_guid", ancestorGuid)
                // 复刻官方网页前端调用（chrome-devtools 抓包确认 2026-05-24）：
                //   tags.type=["Movie","TV","Directory","Video"] —— 服务端按类型白名单过滤
                //   关键：明确不要 Episode/Season，让 TV 整剧聚合显示
                //   Season 通过点 TV 进 DetailActivity → fetchSeasons 显示
                //   exclude_grouped_video=1：再保险
                //   sort_type 必须大写 "DESC"（小写也行但官方用大写）
                add("tags", JsonObject().apply {
                    add("type", com.google.gson.JsonArray().apply {
                        add("Movie"); add("TV"); add("Directory"); add("Video")
                    })
                })
                addProperty("exclude_grouped_video", 1)
                addProperty("sort_column", "create_time")
                addProperty("sort_type", "DESC")
                addProperty("page", 1)
                addProperty("page_size", 50)
            }.toString()

            val itemsRaw = postJson("${normalize(AppConfig.BASE_URL)}/api/v1/item/list", listBody, token)
            val itemsRoot = gson.fromJson(itemsRaw, JsonObject::class.java)
            val itemsCode = itemsRoot.get("code")?.takeUnless { it.isJsonNull }?.asInt ?: -1
            if (itemsCode != 0) {
                val msg = itemsRoot.get("msg")?.takeUnless { it.isJsonNull }?.asString ?: "item/list failed"
                Log.e(TAG, "fetchMediaItems item/list bizError guid=$ancestorGuid code=$itemsCode msg=$msg")
                return Result.failure(IllegalStateException("item/list: $msg (code=$itemsCode)"))
            }
            val itemList = itemsRoot.getAsJsonObject("data")?.getAsJsonArray("list")
            Log.i(TAG, "fetchMediaItems item/list success guid=$ancestorGuid listSize=${itemList?.size() ?: 0}")
            // 先建 guid → 显示名 映射（用于 Season 找 parent TV 名字补前缀）
            val guidToTitle = mutableMapOf<String, String>()
            itemList?.forEach { elem ->
                val obj = elem.asJsonObject
                val g = obj.get("guid")?.takeUnless { it.isJsonNull }?.asString ?: return@forEach
                val t = obj.get("title")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                if (t.isNotBlank()) guidToTitle[g] = t
            }
            val results = mutableListOf<MediaItem>()
            itemList?.forEach { elem ->
                val obj = elem.asJsonObject
                val guid = obj.get("guid")?.takeUnless { it.isJsonNull }?.asString ?: return@forEach
                val type = obj.get("type")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                // 散落的 Episode 不在主页显示——剧集应只在 TV/Season 详情里出现
                // 飞牛后端 exclude_grouped_video=1 对孤儿 Episode 不生效（实测），需前端过滤
                if (type == "Episode") return@forEach
                val titleField = obj.get("title")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                val tvTitleField = obj.get("tv_title")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                val parentGuid = obj.get("parent_guid")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                val grandTitle = obj.get("grand_title")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                // Season 显示为「剧名 第一季」形式（更符合直觉）
                // 优先级：grand_title > tv_title > 查同批 guidToTitle[parent_guid] > 单独 title
                val rawTitle = when {
                    type == "Season" -> {
                        val showName = grandTitle.ifBlank {
                            tvTitleField.ifBlank {
                                guidToTitle[parentGuid].orEmpty()
                            }
                        }
                        when {
                            showName.isNotBlank() && titleField.isNotBlank() && titleField != showName ->
                                "$showName $titleField"
                            showName.isNotBlank() -> showName
                            titleField.isNotBlank() -> titleField
                            else -> guid
                        }
                    }
                    titleField.isNotBlank() -> titleField
                    tvTitleField.isNotBlank() -> tvTitleField
                    else -> guid
                }
                // 判刮削证据：有 imdb_id / tmdb_id / overview 之一 = 已刮削，title 通常已是干净的电影名
                val scraped = listOf("imdb_id", "tmdb_id", "tvdb_id").any { k ->
                    obj.get(k)?.takeUnless { it.isJsonNull }?.asString?.isNotBlank() == true
                } || (obj.get("overview")?.takeUnless { it.isJsonNull }?.asString?.isNotBlank() == true)
                val title = if (scraped) rawTitle else cleanFilenameTitle(rawTitle)
                val duration = obj.get("duration")?.takeUnless { it.isJsonNull }?.asInt ?: 0
                val overview = obj.get("overview")?.takeUnless { it.isJsonNull }?.asString ?: ""
                // 飞牛 NAS 海报字段随类型变化：
                //   type=Video     → "poster"      (字符串，单张)
                //   type=Directory → "poster_list" (数组，多张候选)
                // 取首张可用；图片接口无需 token，BASE_URL（含 /v）直接拼即可
                val singlePoster = obj.get("poster")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                val posterPath = if (singlePoster.isNotBlank()) {
                    singlePoster
                } else {
                    obj.getAsJsonArray("poster_list")
                        ?.takeIf { it.size() > 0 }
                        ?.get(0)
                        ?.takeUnless { it.isJsonNull }
                        ?.asString
                        .orEmpty()
                }
                val posterUrl = if (posterPath.isNotBlank()) posterLink(posterPath) else ""
                results.add(MediaItem(
                    title = title,
                    itemGuid = guid,
                    posterUrl = posterUrl,
                    duration = duration,
                    overview = overview
                ))
            }
            Log.i(TAG, "fetchMediaItems finished guid=$ancestorGuid count=${results.size}")
            // 空结果不再当失败——空库就是空库
            Result.success(results)
        } catch (e: Exception) {
            Log.e(TAG, "fetchMediaItems exception guid=$ancestorGuid", e)
            Result.failure(e)
        }
    }

    fun resolvePlayUrl(token: String, itemGuid: String, preferLowestQuality: Boolean = true): String? {
        return refetchPlayableUrlByItemGuid(token, itemGuid, preferLowestQuality)
    }

    fun fetchQualities(token: String, mediaGuid: String): Result<List<Quality>> {
        return try {
            val qualityBody = JsonObject().apply { addProperty("media_guid", mediaGuid) }.toString()
            val qualityRaw = postJson("${normalize(AppConfig.BASE_URL)}/api/v1/play/quality", qualityBody, token)
            val qualityRoot = gson.fromJson(qualityRaw, JsonObject::class.java)
            val code = qualityRoot.get("code")?.takeUnless { it.isJsonNull }?.asInt ?: -1
            if (code != 0) {
                val msg = qualityRoot.get("msg")?.takeUnless { it.isJsonNull }?.asString ?: "play/quality failed"
                return Result.failure(IllegalStateException("play/quality: $msg (code=$code)"))
            }
            val qualityList = qualityRoot.getAsJsonArray("data")
            val results = mutableListOf<Quality>()
            if (qualityList != null && qualityList.size() > 0) {
                qualityList.forEach { qElem ->
                    val qObj = qElem.asJsonObject
                    val br = qObj.get("bitrate")?.takeUnless { it.isJsonNull }?.asLong ?: return@forEach
                    val rs = qObj.get("resolution")?.takeUnless { it.isJsonNull }?.asString ?: return@forEach
                    results.add(Quality(rs, br))
                }
            }
            if (results.isEmpty()) Result.failure(IllegalStateException("No qualities found")) else Result.success(results)
        } catch (e: Exception) {
            Log.e(TAG, "fetchQualities exception mediaGuid=$mediaGuid", e)
            Result.failure(e)
        }
    }

    fun getPlayUrl(token: String, mediaGuid: String, resolution: String, bitrate: Long, videoGuid: String?, audioGuid: String?, subtitleGuid: String?): String? {
        return try {
            val playPlayBody = JsonObject().apply {
                addProperty("media_guid", mediaGuid)
                addProperty("resolution", resolution)
                addProperty("bitrate", bitrate)
                addProperty("startTimestamp", 0)
                if (!videoGuid.isNullOrBlank()) addProperty("video_guid", videoGuid)
                if (!audioGuid.isNullOrBlank()) addProperty("audio_guid", audioGuid)
                if (!subtitleGuid.isNullOrBlank()) addProperty("subtitle_guid", subtitleGuid)
                addProperty("forced_sdr", 0)
                addProperty("without_stream", false)
            }.toString()

            val playPlayRaw = postJsonWithRetry("${normalize(AppConfig.BASE_URL)}/api/v1/play/play", playPlayBody, token)
            val playPlayRoot = gson.fromJson(playPlayRaw, JsonObject::class.java)
            val code = playPlayRoot.get("code")?.takeUnless { it.isJsonNull }?.asInt ?: -1
            if (code != 0) {
                val msg = playPlayRoot.get("msg")?.takeUnless { it.isJsonNull }?.asString ?: "play/play failed"
                Log.w(TAG, "getPlayUrl bizError mediaGuid=$mediaGuid resolution=$resolution bitrate=$bitrate code=$code msg=$msg")
                return null
            }
            val link = playPlayRoot.getAsJsonObject("data")?.get("play_link")?.takeUnless { it.isJsonNull }?.asString
            if (link.isNullOrBlank()) null else absoluteLink(link)
        } catch (e: Exception) {
            Log.e(TAG, "getPlayUrl exception mediaGuid=$mediaGuid", e)
            null
        }
    }

    fun getPlayUrlByItemGuid(token: String, itemGuid: String, resolution: String, bitrate: Long): String? {
        return try {
            val playInfoBody = JsonObject().apply { addProperty("item_guid", itemGuid) }.toString()
            val playInfoRaw = postJsonWithRetry("${normalize(AppConfig.BASE_URL)}/api/v1/play/info", playInfoBody, token)
            val playInfoRoot = gson.fromJson(playInfoRaw, JsonObject::class.java)
            val code = playInfoRoot.get("code")?.takeUnless { it.isJsonNull }?.asInt ?: -1
            if (code != 0) {
                val msg = playInfoRoot.get("msg")?.takeUnless { it.isJsonNull }?.asString ?: "play/info failed"
                Log.w(TAG, "getPlayUrlByItemGuid play/info bizError itemGuid=$itemGuid code=$code msg=$msg")
                return null
            }
            val playData = playInfoRoot.getAsJsonObject("data") ?: return null
            val mediaGuid = playData.get("media_guid")?.takeUnless { it.isJsonNull }?.asString ?: return null
            val videoGuid = playData.get("video_guid")?.takeUnless { it.isJsonNull }?.asString
            val audioGuid = playData.get("audio_guid")?.takeUnless { it.isJsonNull }?.asString
            val subtitleGuid = playData.get("subtitle_guid")?.takeUnless { it.isJsonNull }?.asString
            getPlayUrl(token, mediaGuid, resolution, bitrate, videoGuid, audioGuid, subtitleGuid)
        } catch (e: Exception) {
            Log.e(TAG, "getPlayUrlByItemGuid exception itemGuid=$itemGuid", e)
            null
        }
    }

    // 当前播放会话状态（用于设置弹窗一次性了解全部默认 GUID）
    data class PlayInfoState(
        val mediaGuid: String,
        val videoGuid: String?,
        val audioGuid: String?,
        val subtitleGuid: String?
    )

    // POST /v/api/v1/play/info → 提取 mediaGuid + video/audio/subtitle guid
    // 设置弹窗首次打开时调用一次，缓存结果作为后续切换的"当前选择"基线
    fun fetchPlayInfoState(token: String, itemGuid: String): Result<PlayInfoState> {
        return try {
            val body = JsonObject().apply { addProperty("item_guid", itemGuid) }.toString()
            val raw = postJsonWithRetry("${normalize(AppConfig.BASE_URL)}/api/v1/play/info", body, token)
            val root = gson.fromJson(raw, JsonObject::class.java)
            val code = root.get("code")?.takeUnless { it.isJsonNull }?.asInt ?: -1
            if (code != 0) {
                val msg = root.get("msg")?.takeUnless { it.isJsonNull }?.asString ?: "play/info failed"
                return Result.failure(IllegalStateException(msg))
            }
            val data = root.getAsJsonObject("data") ?: return Result.failure(IllegalStateException("play/info no data"))
            val mediaGuid = data.get("media_guid")?.takeUnless { it.isJsonNull }?.asString
                ?: return Result.failure(IllegalStateException("play/info no media_guid"))
            Result.success(
                PlayInfoState(
                    mediaGuid = mediaGuid,
                    videoGuid = data.get("video_guid")?.takeUnless { it.isJsonNull }?.asString,
                    audioGuid = data.get("audio_guid")?.takeUnless { it.isJsonNull }?.asString,
                    subtitleGuid = data.get("subtitle_guid")?.takeUnless { it.isJsonNull }?.asString
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchPlayInfoState exception itemGuid=$itemGuid", e)
            Result.failure(e)
        }
    }

    // GET /v/api/v1/stream/list/{item_guid} → 解析所有音轨/字幕轨道
    // 注意：路径参数是 item_guid 而非 media_guid（虽然命名容易让人误会）；实测 media_guid 会返回空数组
    // 字段映射参考反编译 AudioStream.java / SubtitleStream.java
    fun fetchMediaStreams(token: String, itemGuid: String): Result<MediaStreams> {
        return try {
            val url = "${normalize(AppConfig.BASE_URL)}/api/v1/stream/list/$itemGuid"
            val raw = getJson(url, token)
            val root = gson.fromJson(raw, JsonObject::class.java)
            val code = root.get("code")?.takeUnless { it.isJsonNull }?.asInt ?: -1
            if (code != 0) {
                val msg = root.get("msg")?.takeUnless { it.isJsonNull }?.asString ?: "stream/list failed"
                return Result.failure(IllegalStateException(msg))
            }
            val data = root.getAsJsonObject("data") ?: return Result.success(MediaStreams(emptyList(), emptyList()))

            fun s(o: com.google.gson.JsonObject, k: String): String =
                o.get(k)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            fun i(o: com.google.gson.JsonObject, k: String): Int =
                o.get(k)?.takeUnless { it.isJsonNull }?.asInt ?: 0

            val audios = data.getAsJsonArray("audio_streams")?.mapNotNull { elem ->
                val o = elem?.takeUnless { it.isJsonNull }?.asJsonObject ?: return@mapNotNull null
                val guid = s(o, "guid").ifBlank { return@mapNotNull null }
                AudioTrack(
                    guid = guid,
                    mediaGuid = s(o, "media_guid"),
                    languageName = s(o, "language_name"),
                    title = s(o, "title"),
                    codecName = s(o, "codec_name"),
                    channelLayout = s(o, "channel_layout"),
                    channels = i(o, "channels"),
                    isDefault = i(o, "is_default") == 1,
                    isSelected = o.get("is_selected")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
                )
            } ?: emptyList()

            val subtitles = data.getAsJsonArray("subtitle_streams")?.mapNotNull { elem ->
                val o = elem?.takeUnless { it.isJsonNull }?.asJsonObject ?: return@mapNotNull null
                val guid = s(o, "guid").ifBlank { return@mapNotNull null }
                SubtitleTrack(
                    guid = guid,
                    mediaGuid = s(o, "media_guid"),
                    languageName = s(o, "language_name"),
                    title = s(o, "title"),
                    codecName = s(o, "codec_name"),
                    isExternal = i(o, "is_external") == 1,
                    isDefault = i(o, "is_default") == 1
                )
            } ?: emptyList()

            Log.i(TAG, "fetchMediaStreams itemGuid=$itemGuid audios=${audios.size} subtitles=${subtitles.size}")
            Result.success(MediaStreams(audios, subtitles))
        } catch (e: Exception) {
            Log.e(TAG, "fetchMediaStreams exception itemGuid=$itemGuid", e)
            Result.failure(e)
        }
    }

    fun refetchPlayableUrlByItemGuid(token: String, itemGuid: String, preferLowestQuality: Boolean = false): String? {
        return try {
            val playInfoBody = JsonObject().apply { addProperty("item_guid", itemGuid) }.toString()
            val playInfoRaw = postJson("${normalize(AppConfig.BASE_URL)}/api/v1/play/info", playInfoBody, token)
            val playInfoRoot = gson.fromJson(playInfoRaw, JsonObject::class.java)
            val playData = playInfoRoot.getAsJsonObject("data") ?: return null
            val mediaGuid = playData.get("media_guid")?.takeUnless { it.isJsonNull }?.asString ?: itemGuid
            val videoGuid = playData.get("video_guid")?.takeUnless { it.isJsonNull }?.asString
            val audioGuid = playData.get("audio_guid")?.takeUnless { it.isJsonNull }?.asString
            val subtitleGuid = playData.get("subtitle_guid")?.takeUnless { it.isJsonNull }?.asString

            val qualityBody = JsonObject().apply { addProperty("media_guid", mediaGuid) }.toString()
            val qualityRaw = postJson("${normalize(AppConfig.BASE_URL)}/api/v1/play/quality", qualityBody, token)
            val qualityRoot = gson.fromJson(qualityRaw, JsonObject::class.java)
            val qualityList = qualityRoot.getAsJsonArray("data")

            var selectedResolution = "360"
            var selectedBitrate = 200000L
            if (qualityList != null && qualityList.size() > 0) {
                var pickedBitrate = if (preferLowestQuality) Long.MAX_VALUE else Long.MIN_VALUE
                qualityList.forEach { qElem ->
                    val qObj = qElem.asJsonObject
                    val br = qObj.get("bitrate")?.takeUnless { it.isJsonNull }?.asLong ?: return@forEach
                    val rs = qObj.get("resolution")?.takeUnless { it.isJsonNull }?.asString ?: return@forEach
                    if (preferLowestQuality) {
                        if (br in 1 until pickedBitrate) {
                            pickedBitrate = br
                            selectedBitrate = br
                            selectedResolution = rs
                        }
                    } else {
                        if (br > pickedBitrate) {
                            pickedBitrate = br
                            selectedBitrate = br
                            selectedResolution = rs
                        }
                    }
                }
            }

            val playPlayBody = JsonObject().apply {
                addProperty("media_guid", mediaGuid)
                addProperty("resolution", selectedResolution)
                addProperty("bitrate", selectedBitrate)
                addProperty("startTimestamp", 0)
                if (!videoGuid.isNullOrBlank()) addProperty("video_guid", videoGuid)
                if (!audioGuid.isNullOrBlank()) addProperty("audio_guid", audioGuid)
                if (!subtitleGuid.isNullOrBlank()) addProperty("subtitle_guid", subtitleGuid)
                addProperty("forced_sdr", 1)
                addProperty("without_stream", false)
            }.toString()

            val playPlayRaw = postJson("${normalize(AppConfig.BASE_URL)}/api/v1/play/play", playPlayBody, token)
            val playPlayRoot = gson.fromJson(playPlayRaw, JsonObject::class.java)
            val code = playPlayRoot.get("code")?.takeUnless { it.isJsonNull }?.asInt ?: -1
            if (code != 0) return null
            val link = playPlayRoot.getAsJsonObject("data")?.get("play_link")?.takeUnless { it.isJsonNull }?.asString
            if (link.isNullOrBlank()) null else absoluteLink(link)
        } catch (e: Exception) {
            Log.e(TAG, "refetchPlayableUrlByItemGuid exception itemGuid=$itemGuid", e)
            null
        }
    }

    fun buildOriginalRangeUrl(mediaGuid: String): String {
        val base = normalize(AppConfig.BASE_URL)
        return "$base/api/v1/media/range/$mediaGuid"
    }

    private fun getJson(url: String, token: String): String {
        Log.d(TAG, "GET $url tokenLen=${token.length}")
        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", token)
                .build()
            val response = httpClient.newCall(request).execute()
            val code = response.code()
            val body = response.body()?.string() ?: ""
            Log.d(TAG, "GET done url=$url http=$code bodyPreview=${body.take(300)}")
            if (code !in 200..299) throw IllegalStateException("GET $url HTTP $code body=${body.take(200)}")
            return body
        } catch (e: Exception) {
            Log.e(TAG, "getJson exception url=$url", e)
            throw RuntimeException("GET $url failed: ${e.javaClass.name}: ${e.message}", e)
        }
    }

    fun postJsonForPlayer(url: String, body: String, token: String): String = postJson(url, body, token)

    private fun postJson(url: String, body: String, token: String): String {
        Log.d(TAG, "POST $url tokenLen=${token.length} body=${body.take(400)}")
        try {
            val request = Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON_MEDIA_TYPE, body))
                .header("Authorization", token)
                .header("cookie", "mode=relay")
                .build()
            val response = httpClient.newCall(request).execute()
            val code = response.code()
            val text = response.body()?.string() ?: ""
            Log.d(TAG, "POST done url=$url http=$code bodyPreview=${text.take(400)}")
            if (code !in 200..299) throw IllegalStateException("POST $url HTTP $code body=${text.take(200)}")
            return text
        } catch (e: Exception) {
            Log.e(TAG, "postJson exception url=$url", e)
            throw RuntimeException("POST $url failed: ${e.javaClass.name}: ${e.message}", e)
        }
    }

    /**
     * 收藏 / 已看 API（反编译 FavoriteRequest/WatchedRequest 揭示）：
     *   - body 字段名是 item_guid（不是 guid）
     *   - PUT favorite 添加 / DELETE favorite 移除
     *   - POST watched 标记 / DELETE watched 取消
     *   - DELETE 带 body 是 OkHttp 3.12.x 默认拒绝的，需 .method() 强制
     */
    fun addFavorite(token: String, itemGuid: String): Result<Unit> = bodyCall(
        "PUT", "${normalize(AppConfig.BASE_URL)}/api/v1/item/favorite",
        """{"item_guid":"$itemGuid"}""", token, "addFavorite"
    )

    fun removeFavorite(token: String, itemGuid: String): Result<Unit> = bodyCall(
        "DELETE", "${normalize(AppConfig.BASE_URL)}/api/v1/item/favorite",
        """{"item_guid":"$itemGuid"}""", token, "removeFavorite"
    )

    fun markWatched(token: String, itemGuid: String): Result<Unit> = bodyCall(
        "POST", "${normalize(AppConfig.BASE_URL)}/api/v1/item/watched",
        """{"item_guid":"$itemGuid"}""", token, "markWatched"
    )

    fun unmarkWatched(token: String, itemGuid: String): Result<Unit> = bodyCall(
        "DELETE", "${normalize(AppConfig.BASE_URL)}/api/v1/item/watched",
        """{"item_guid":"$itemGuid"}""", token, "unmarkWatched"
    )

    private fun bodyCall(method: String, url: String, body: String, token: String, tag: String): Result<Unit> {
        return try {
            Log.d(TAG, "$tag $method $url body=${body.take(200)}")
            val request = Request.Builder()
                .url(url)
                .header("Authorization", token)
                .header("cookie", "mode=relay")
                .method(method, RequestBody.create(JSON_MEDIA_TYPE, body))
                .build()
            val response = httpClient.newCall(request).execute()
            val httpCode = response.code()
            val text = response.body()?.string().orEmpty()
            Log.d(TAG, "$tag done http=$httpCode body=${text.take(300)}")
            if (httpCode !in 200..299) {
                return Result.failure(IllegalStateException("$tag HTTP $httpCode body=${text.take(200)}"))
            }
            val root = gson.fromJson(text, JsonObject::class.java)
            val code = root?.get("code")?.takeUnless { it.isJsonNull }?.asInt
            if (code != 0) {
                val msg = root?.get("msg")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                return Result.failure(IllegalStateException("$tag biz code=$code msg=$msg"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "$tag exception url=$url", e)
            Result.failure(e)
        }
    }

    private fun postJsonWithRetry(url: String, body: String, token: String, maxAttempts: Int = 2): String {
        var lastError: RuntimeException? = null
        for (attempt in 1..maxAttempts) {
            try {
                return postJson(url, body, token)
            } catch (e: RuntimeException) {
                lastError = e
                val root = rootCause(e)
                val isTimeout = root is SocketTimeoutException
                val canRetry = isTimeout && attempt < maxAttempts
                if (!canRetry) {
                    throw e
                }
                Log.w(TAG, "postJsonWithRetry timeout url=$url attempt=$attempt/$maxAttempts, retrying")
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        throw lastError ?: RuntimeException("POST $url failed: unknown")
    }

    private fun rootCause(e: Throwable): Throwable {
        var cur: Throwable = e
        while (cur.cause != null && cur.cause !== cur) {
            cur = cur.cause!!
        }
        return cur
    }

    private fun normalize(base: String): String = base.trimEnd('/')

    private fun absoluteLink(link: String): String {
        if (link.startsWith("http://") || link.startsWith("https://")) return link
        val root = normalize(AppConfig.BASE_URL).removeSuffix("/v")
        return if (link.startsWith('/')) "$root$link" else "$root/$link"
    }

    // 拼海报图片绝对 URL。
    //
    // 飞牛 NAS 的图片走专门的 sys/img 端点（参照官方反编译 x74.F()）：
    //   URL = <root>/v/api/v1/sys/img<poster-path>
    // 注意：
    //   - root = http://host:port（不含 /v 业务前缀）
    //   - 必须带 Authorization + cookie: mode=relay 才返回真图，否则 501
    //
    // 例：path = "/19/16/poster-xxx.webp"
    //      → "http://host:port/v/api/v1/sys/img/19/16/poster-xxx.webp"
    private fun posterLink(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val root = normalize(AppConfig.BASE_URL).removeSuffix("/v")
        val p = if (path.startsWith('/')) path else "/$path"
        return "$root/v/api/v1/sys/img$p"
    }

    // 未刮削视频标题清洗：从原始文件名里提取干净的展示标题
    // 策略 1：先把 . _ 替换成空格，折叠多空格
    // 策略 2：找到第一个 4 位年份 (19xx/20xx) → 截到年份末尾（含），后面技术标签全丢
    //   "The Wiz 1978 1080p AUS Blu-ray AVC..." → "The Wiz 1978"
    // 策略 3：没年份则在常见技术标签（1080p/720p/4K/2160p/BluRay/WEB-DL/x264/HEVC 等）首次出现前截断
    //   "Some.Movie.x264.AAC" → "Some Movie"
    // 策略 4：保留原标题作为兜底
    private val yearRegex = Regex("\\b(19|20)\\d{2}\\b")
    private val techTagRegex = Regex(
        "(?i)\\b(1080p|720p|480p|2160p|4k|8k|bluray|blu-ray|bdrip|brrip|dvdrip|webrip|web-dl|hdrip|hdtv|" +
            "x264|x265|h\\.?264|h\\.?265|hevc|avc|aac|ac3|dts|ddp|atmos|truehd|flac|mp3|" +
            "remux|hdr|hdr10|dolby|imax|extended|directors?\\.cut|repack|proper|internal|" +
            "yify|yts|rarbg|aus|usa|uk|jpn|chi|eng|cantonese|mandarin)\\b"
    )
    internal fun cleanFilenameTitle(raw: String): String {
        if (raw.isBlank()) return raw
        // 去扩展名 (.mkv/.mp4/.avi etc.)
        val noExt = raw.replace(Regex("\\.(mkv|mp4|avi|m2ts|ts|mov|wmv|flv|webm|m4v)$", RegexOption.IGNORE_CASE), "")
        // 替换分隔符
        val spaced = noExt.replace('.', ' ').replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        if (spaced.isBlank()) return raw
        // 策略 2：年份截断（包含年份）
        val yearMatch = yearRegex.find(spaced)
        if (yearMatch != null) {
            val end = yearMatch.range.last + 1
            return spaced.substring(0, end).trim().ifBlank { raw }
        }
        // 策略 3：技术标签截断（不含标签）
        val tagMatch = techTagRegex.find(spaced)
        if (tagMatch != null) {
            val cut = spaced.substring(0, tagMatch.range.first).trim().trimEnd('-', '(', '[', '{').trim()
            if (cut.isNotBlank()) return cut
        }
        return spaced
    }
}

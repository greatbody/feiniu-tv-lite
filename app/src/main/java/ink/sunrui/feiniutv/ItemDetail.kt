package ink.sunrui.feiniutv

/**
 * 媒体详情（来自 /v/api/v1/item/{guid}）。
 *
 * 飞牛 NAS 对"刮削过"的条目（带 imdb_id）会返回完整元数据，
 * "原始视频"（如 YouTube 频道录制）很多字段为空——所有字段都做 nullable/empty 处理。
 */
data class ItemDetail(
    val guid: String,
    val title: String,
    val originalTitle: String = "",
    val overview: String = "",
    val posterUrl: String = "",      // 已拼好的绝对 URL，由 NasApiClient.posterLink 生成
    val backdropUrl: String = "",    // 同上
    val voteAverage: Double = 0.0,   // 评分 0-10
    val releaseDate: String = "",    // YYYY-MM-DD
    val runtimeMinutes: Int = 0,     // 时长（分）
    val resolutions: List<String> = emptyList(),  // 1080p, 720p, ...
    val audioTypes: List<String> = emptyList(),   // DTS, DolbySurround, Stereo, ...
    val countries: List<String> = emptyList(),    // GB, US, ...
    val isFavorite: Boolean = false,
    val isWatched: Boolean = false,
    val canPlay: Boolean = true,
    val type: String = "Video",      // Video / Movie / TV / Episode / Season
    val ancestorName: String = ""    // 所属库名
)

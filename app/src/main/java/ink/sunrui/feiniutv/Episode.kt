package ink.sunrui.feiniutv

/**
 * 一集（来自 /v/api/v1/episode/list/{season_guid}）。
 *
 * 字段命名：episodeNumber 是该季内的第 N 集；seasonNumber 是所属季编号。
 * title 可能为空（飞牛对很多剧没刮到分集标题）—— UI 用 "第 N 集" 兜底。
 */
data class Episode(
    val guid: String,           // 用于 PlayerActivity 的 itemGuid
    val title: String = "",     // 分集标题（可能空）
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val overview: String = "",
    val posterUrl: String = "", // 分集封面（已拼好的绝对 URL）
    val duration: Int = 0,      // 秒
    val airDate: String = "",
    val watched: Boolean = false,
    val watchedTs: Int = 0      // 已看到的秒数
) {
    /** 给 UI 用：标题为空时用"第 N 集"兜底 */
    val displayTitle: String
        get() = if (title.isNotBlank()) title
        else if (episodeNumber > 0) "第 $episodeNumber 集"
        else "未命名"
}

/**
 * 一季（来自 /v/api/v1/season/list/{tv_guid}）。
 *
 * 实际是 Item 的子类，但只保留 UI 需要的最小字段。
 */
data class Season(
    val guid: String,            // 用于 fetchEpisodes 的入参
    val title: String,           // 如 "第一季"
    val seasonNumber: Int = 0,
    val episodeCount: Int = 0,   // local_number_of_episodes：本地已有的集数
    val posterUrl: String = ""
)

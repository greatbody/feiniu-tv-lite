package ink.sunrui.feiniutv

data class MediaItem(
    val title: String,
    val itemGuid: String,
    val posterUrl: String = "",
    val duration: Int = 0,
    val overview: String = "",
    val url: String = "",
    val token: String? = null,
    val mediaGuid: String? = null
)

package ink.sunrui.feiniutv.network

/**
 * 媒体流轨道（音轨/字幕/视轨）来自 GET /v/api/v1/stream/list/{media_guid}
 *
 * 设计要点：
 *   - guid 是流自身 GUID，play/play 接口的 audio_guid / subtitle_guid 参数用这个
 *   - mediaGuid 是父媒体 GUID（视频本体），所有同源流共享
 *   - displayLabel() 整合 language + codec + channelLayout 给出"中文 5.1 AAC"形式
 */

data class AudioTrack(
    val guid: String,
    val mediaGuid: String,
    val languageName: String,
    val title: String,
    val codecName: String,
    val channelLayout: String,
    val channels: Int,
    val isDefault: Boolean,
    val isSelected: Boolean
) {
    fun displayLabel(): String {
        val parts = mutableListOf<String>()
        if (languageName.isNotBlank()) parts += languageName
        else if (title.isNotBlank()) parts += title
        else parts += "音轨"
        if (channelLayout.isNotBlank()) parts += channelLayout
        else if (channels > 0) parts += when (channels) {
            1 -> "单声道"
            2 -> "2.0"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${channels}ch"
        }
        if (codecName.isNotBlank()) parts += codecName.uppercase()
        return parts.joinToString(" · ")
    }
}

data class SubtitleTrack(
    val guid: String,
    val mediaGuid: String,
    val languageName: String,
    val title: String,
    val codecName: String,
    val isExternal: Boolean,
    val isDefault: Boolean
) {
    fun displayLabel(): String {
        val parts = mutableListOf<String>()
        if (languageName.isNotBlank()) parts += languageName
        else if (title.isNotBlank()) parts += title
        else parts += "字幕"
        if (isExternal) parts += "外挂"
        if (codecName.isNotBlank()) parts += codecName.uppercase()
        return parts.joinToString(" · ")
    }
}

data class MediaStreams(
    val audioTracks: List<AudioTrack>,
    val subtitleTracks: List<SubtitleTrack>
)

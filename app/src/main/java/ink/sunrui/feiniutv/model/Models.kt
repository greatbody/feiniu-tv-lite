package ink.sunrui.feiniutv.model

import com.google.gson.annotations.SerializedName

data class BaseResponse<T>(
    @SerializedName("code") val code: Int,
    @SerializedName("msg") val msg: String,
    @SerializedName("data") val data: T?
)

data class LoginData(
    @SerializedName("token") val token: String
)

data class MediaLibraryModel(
    @SerializedName("guid") val guid: String,
    @SerializedName("title") val title: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("item_count") val itemCount: Int?,
    @SerializedName("media_count") val mediaCount: Int?,
    @SerializedName("count") val count: Int?
)

data class MediaItemModel(
    @SerializedName("guid") val guid: String,
    @SerializedName("title") val title: String?,
    @SerializedName("tv_title") val tvTitle: String?
)

data class MediaItemListResponse(
    @SerializedName("list") val list: List<MediaItemModel>?
)

data class PlayInfoData(
    @SerializedName("media_guid") val mediaGuid: String,
    @SerializedName("video_guid") val videoGuid: String?,
    @SerializedName("audio_guid") val audioGuid: String?,
    @SerializedName("subtitle_guid") val subtitleGuid: String?
)

data class QualityData(
    @SerializedName("resolution") val resolution: String,
    @SerializedName("bitrate") val bitrate: Long
)

data class PlayPlayData(
    @SerializedName("play_link") val playLink: String?
)

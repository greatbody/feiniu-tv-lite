package ink.sunrui.feiniutv.api

import ink.sunrui.feiniutv.model.*
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface FeiniuApiService {

    @POST("api/v1/login")
    suspend fun login(@Body body: Map<String, String>): BaseResponse<LoginData>

    @GET("api/v1/mediadb/list")
    suspend fun getMediaDbList(): BaseResponse<List<MediaLibraryModel>>

    @GET("api/v1/mediadb/sum")
    suspend fun getMediaDbSum(): BaseResponse<Map<String, Int>>

    @POST("api/v1/item/list")
    suspend fun getItemList(@Body body: Map<String, Any>): BaseResponse<MediaItemListResponse>

    @POST("api/v1/play/info")
    suspend fun getPlayInfo(@Body body: Map<String, String>): BaseResponse<PlayInfoData>

    @POST("api/v1/play/quality")
    suspend fun getPlayQuality(@Body body: Map<String, String>): BaseResponse<List<QualityData>>

    @POST("api/v1/play/play")
    suspend fun getPlayLink(@Body body: Map<String, Any>): BaseResponse<PlayPlayData>
}

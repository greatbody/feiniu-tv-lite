package ink.sunrui.feiniutv.api

import ink.sunrui.feiniutv.AppConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    var token: String = ""

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val requestBuilder = original.newBuilder()
            .header("cookie", "mode=relay")

        if (token.isNotEmpty() && !original.url().encodedPath().contains("login")) {
            requestBuilder.header("Authorization", token)
        }

        chain.proceed(requestBuilder.build())
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(if (AppConfig.BASE_URL.endsWith("/")) AppConfig.BASE_URL else "${AppConfig.BASE_URL}/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val apiService: FeiniuApiService = retrofit.create(FeiniuApiService::class.java)
}

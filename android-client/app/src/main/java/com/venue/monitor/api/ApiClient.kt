package com.venue.monitor.api

import com.google.gson.GsonBuilder
import com.venue.monitor.MonitorApplication
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit 客户端 - 根据用户配置的服务器地址动态构建
 * 每次服务器地址变更时调用 rebuild() 重建实例
 */
object ApiClient {

    private var retrofit: Retrofit? = null
    private var service: ApiService? = null
    private var currentBaseUrl: String = ""

    private val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    private val gson by lazy { GsonBuilder().setLenient().create() }

    init {
        rebuild()
    }

    /** 根据当前配置的 serverUrl 重建 Retrofit 实例 */
    fun rebuild() {
        val url = MonitorApplication.prefs.serverUrl
        if (url.isBlank()) return
        if (url == currentBaseUrl && service != null) return

        currentBaseUrl = url
        retrofit = Retrofit.Builder()
            .baseUrl(if (url.endsWith("/")) url else "$url/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        service = retrofit?.create(ApiService::class.java)
    }

    fun get(): ApiService =
        service ?: throw IllegalStateException("ApiService 未初始化，请先配置服务器地址")
}

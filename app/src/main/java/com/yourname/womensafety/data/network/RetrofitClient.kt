package com.yourname.womensafety.data.network

import com.yourname.womensafety.data.local.TokenManager
import com.yourname.womensafety.data.network.api.AuthApiService
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    @Volatile
    private var retrofit: Retrofit? = null

    /** Authenticated Retrofit instance (with AuthInterceptor). */
    fun getInstance(tokenManager: TokenManager): Retrofit {
        return retrofit ?: synchronized(this) {
            retrofit ?: buildRetrofit(tokenManager).also { retrofit = it }
        }
    }

    private fun buildRetrofit(tokenManager: TokenManager): Retrofit {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenManager))
            .addInterceptor(loggingInterceptor)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(ApiConstants.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /** Create an authenticated service. */
    inline fun <reified T> createService(tokenManager: TokenManager): T =
        getInstance(tokenManager).create(T::class.java)

    /**
     * Refresh-only Retrofit instance — no AuthInterceptor to avoid infinite loops.
     * Used by TokenManager.refreshAccessToken().
     */
    fun createRefreshService(): AuthApiService {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(ApiConstants.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthApiService::class.java)
    }

    /** Reset the singleton (e.g., after logout). */
    fun reset() {
        retrofit = null
    }
}

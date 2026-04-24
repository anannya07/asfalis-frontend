package com.yourname.womensafety.data.repository

import com.google.gson.Gson
import com.yourname.womensafety.data.network.dto.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import retrofit2.Response
import android.util.Log

abstract class BaseRepository {

    /**
     * Safely execute a Retrofit API call and wrap the result in [NetworkResult].
     */
    protected suspend fun <T> safeApiCall(
        apiCall: suspend () -> Response<ApiResponse<T>>
    ): NetworkResult<T> = withContext(Dispatchers.IO) {
        return@withContext try {
            var response: Response<ApiResponse<T>>? = null
            var retryCount = 0
            val maxRetries = 3 // Up to 15 seconds total wait for Render cold starts

            // Retry loop to handle 503 Service Unavailable (Render Free Tier Cold Starts)
            while (retryCount < maxRetries) {
                response = apiCall()
                if (response.code() == 503) {
                    retryCount++
                    Log.d("BaseRepository", "503 Cold Start Wakeup - Retry $retryCount/$maxRetries")
                    delay(5000L) // Wait 5 seconds for the server to wake up
                    continue
                }
                break // Success or non-503 error
            }

            // Safe unwrap since it will run at least once
            val finalResponse = response ?: apiCall()

            if (finalResponse.isSuccessful) {
                val body = finalResponse.body()
                if (body != null && body.isSuccess) {
                    @Suppress("UNCHECKED_CAST")
                    val data = body.data ?: (Unit as T) // Unit endpoints have no data field
                    NetworkResult.Success(data, body.message)
                } else {
                    val errorCode = body?.resolvedErrorCode ?: "UNKNOWN"
                    val errorMsg  = body?.resolvedErrorMessage ?: "Unknown error"
                    Log.e("BaseRepository", "API Error: $errorCode - $errorMsg")
                    NetworkResult.Error(errorCode, errorMsg)
                }
            } else {
                // 4xx / 5xx — parse error from the JSON body
                val errorBody = finalResponse.errorBody()?.string()
                val apiError = try {
                    Gson().fromJson(errorBody, ApiResponse::class.java)
                } catch (e: Exception) { null }

                val code = apiError?.resolvedErrorCode ?: "HTTP_${finalResponse.code()}"
                val message = apiError?.resolvedErrorMessage ?: finalResponse.message()
                Log.e("BaseRepository", "HTTP Error: $code - $message")
                NetworkResult.Error(code, message)
            }
        } catch (e: java.net.UnknownHostException) {
            Log.e("BaseRepository", "Network offline", e)
            NetworkResult.Error("NETWORK_ERROR", "No internet connection")
        } catch (e: java.net.SocketTimeoutException) {
            Log.e("BaseRepository", "Timeout - Server may be starting up", e)
            NetworkResult.Error("TIMEOUT", "Request timed out. The server might be waking up.")
        } catch (e: Exception) {
            Log.e("BaseRepository", "Unexpected Exception in safeApiCall", e)
            NetworkResult.Error("UNKNOWN", e.localizedMessage ?: "An unexpected error occurred")
        }
    }
}

package com.yourname.womensafety.data.repository

import com.yourname.womensafety.data.local.TokenManager
import com.yourname.womensafety.data.network.api.AuthApiService
import com.yourname.womensafety.data.network.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import android.util.Log

class AuthRepository(
    private val authApi: AuthApiService,
    private val tokenManager: TokenManager
) : BaseRepository() {

    suspend fun loginWithPhone(
        phoneNumber: String,
        password: String,
        confirmHandover: Boolean = false
    ): NetworkResult<AuthData> {
        val deviceImei = tokenManager.getOrCreateDeviceId()
        val result = safeApiCall {
            authApi.loginWithPhone(
                PhoneLoginRequest(
                    phoneNumber = phoneNumber,
                    password = password,
                    deviceImei = deviceImei,
                    confirmHandover = confirmHandover
                )
            )
        }
        if (result is NetworkResult.Success) {
            tokenManager.saveTokens(
                accessToken = result.data.accessToken,
                refreshToken = result.data.refreshToken,
                userId = result.data.userId,
                sosToken = result.data.sosToken,
                expiresIn = result.data.expiresIn
            )
        }
        return result
    }

    suspend fun getHandsetChangeStatus(phoneNumber: String): NetworkResult<HandsetChangeStatusData> {
        val deviceImei = tokenManager.getOrCreateDeviceId()
        return safeAuthApiCall {
            authApi.handsetChangeStatus(
                HandsetChangeStatusRequest(
                    phoneNumber = phoneNumber,
                    deviceImei = deviceImei
                )
            )
        }
    }

    /**
     * Step 1: Register with phone. Returns { phone_number, expires_in }.
     * Twilio sends the OTP directly to the user's phone via SMS — the frontend does NOT send SMS.
     */
    suspend fun registerWithPhone(
        name: String, phoneNumber: String, password: String, country: String
    ): NetworkResult<PhoneRegisterData> {
        return safeAuthApiCall {
            authApi.registerWithPhone(PhoneRegisterRequest(name, phoneNumber, password, country))
        }
    }

    /**
     * Step 2: Verify the OTP that was sent by the app. Returns JWT tokens on success.
     */
    suspend fun verifyPhoneOtp(phoneNumber: String, otpCode: String): NetworkResult<AuthData> {
        val result = safeApiCall {
            authApi.verifyPhoneOtp(VerifyPhoneOtpRequest(phoneNumber, otpCode))
        }
        if (result is NetworkResult.Success) {
            tokenManager.saveTokens(
                accessToken = result.data.accessToken,
                refreshToken = result.data.refreshToken,
                userId = result.data.userId,
                sosToken = result.data.sosToken,
                expiresIn = result.data.expiresIn
            )
        }
        return result
    }

    /** Resend OTP — Twilio re-sends the SMS to the user. Rate-limited 3x/15 min. */
    suspend fun resendOtp(phoneNumber: String): NetworkResult<ResendOtpData> {
        return safeAuthApiCall { authApi.resendOtp(ResendOtpRequest(phoneNumber)) }
    }

    /** Forgot password — Twilio sends OTP to phone; response contains no code. */
    suspend fun forgotPassword(phoneNumber: String): NetworkResult<ForgotPasswordData> {
        return safeAuthApiCall { authApi.forgotPassword(ForgotPasswordRequest(phoneNumber)) }
    }

    /** Reset password — submit Twilio OTP + new password. */
    suspend fun resetPassword(phoneNumber: String, otpCode: String, newPassword: String): NetworkResult<Unit> {
        return safeApiCall { authApi.resetPassword(ResetPasswordRequest(phoneNumber, otpCode, newPassword)) }
    }

    suspend fun logout(): NetworkResult<Unit> {
        val refreshToken = tokenManager.getRefreshToken().first() ?: ""
        val result = safeApiCall { authApi.logout(LogoutRequest(refreshToken)) }
        tokenManager.clearTokens()
        return result
    }

    suspend fun validateToken(): NetworkResult<ValidateData> {
        return safeAuthApiCall { authApi.validateToken() }
    }

    /**
     * Direct API executor for unwrapped endpoints.
     */
    protected suspend fun <T> safeAuthApiCall(
        apiCall: suspend () -> retrofit2.Response<T>
    ): NetworkResult<T> = withContext(Dispatchers.IO) {
        return@withContext try {
            var response: retrofit2.Response<T>? = null
            var retryCount = 0
            val maxRetries = 3 // Up to 15 seconds total wait for Render cold starts

            // Retry loop to handle 503 Service Unavailable (Render Free Tier Cold Starts)
            while (retryCount < maxRetries) {
                response = apiCall()
                if (response.code() == 503) {
                    retryCount++
                    Log.d("AuthRepository", "503 Cold Start Wakeup - Retry $retryCount/$maxRetries")
                    delay(5000L) // Wait 5 seconds for the server to wake up
                    continue
                }
                break // Success or non-503 error
            }

            // Safe unwrap since it will run at least once
            val finalResponse = response ?: apiCall()

            if (finalResponse.isSuccessful) {
                val body = finalResponse.body()
                if (body != null) {
                    NetworkResult.Success(body)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    NetworkResult.Success(Unit as T)
                }
            } else {
                val errorBody = finalResponse.errorBody()?.string()
                val apiError = try {
                    com.google.gson.Gson().fromJson(errorBody, ApiResponse::class.java)
                } catch (e: Exception) { null }
                val code = apiError?.resolvedErrorCode ?: "HTTP_${finalResponse.code()}"
                val message = apiError?.resolvedErrorMessage ?: finalResponse.message()
                Log.e("AuthRepository", "HTTP Error: $code - $message")
                NetworkResult.Error(code, message)
            }
        } catch (e: java.net.UnknownHostException) {
            Log.e("AuthRepository", "Network offline", e)
            NetworkResult.Error("NETWORK_ERROR", "No internet connection")
        } catch (e: java.net.SocketTimeoutException) {
            Log.e("AuthRepository", "Timeout - Server may be starting up", e)
            NetworkResult.Error("TIMEOUT", "Request timed out. The server might be waking up.")
        } catch (e: Exception) {
            Log.e("AuthRepository", "Unexpected Exception in safeAuthApiCall", e)
            NetworkResult.Error("UNKNOWN", e.localizedMessage ?: "An unexpected error occurred")
        }
    }
}

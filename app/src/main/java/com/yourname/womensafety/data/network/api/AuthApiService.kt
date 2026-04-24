package com.yourname.womensafety.data.network.api

import com.yourname.womensafety.data.network.dto.*
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface AuthApiService {

    /** Step 1: Register with phone. Twilio sends OTP SMS to user. Returns { phone_number, expires_in }. */
    @POST("auth/register/phone")
    suspend fun registerWithPhone(
        @Body request: PhoneRegisterRequest
    ): Response<PhoneRegisterData>

    /** Step 2: Verify the Twilio OTP the user received via SMS. Returns JWT tokens. */
    @POST("auth/verify-phone-otp")
    suspend fun verifyPhoneOtp(
        @Body request: VerifyPhoneOtpRequest
    ): Response<ApiResponse<AuthData>>

    @POST("auth/login/phone")
    suspend fun loginWithPhone(
        @Body request: PhoneLoginRequest
    ): Response<ApiResponse<AuthData>>

    @POST("auth/handset-change/status")
    suspend fun handsetChangeStatus(
        @Body request: HandsetChangeStatusRequest
    ): Response<HandsetChangeStatusData>

    /** Resend OTP — Twilio re-sends the SMS. Rate-limited 3×/15 min. */
    @POST("auth/resend-otp")
    suspend fun resendOtp(
        @Body request: ResendOtpRequest
    ): Response<ResendOtpData>

    @POST("auth/refresh")
    suspend fun refreshToken(
        @Body request: RefreshRequest
    ): Response<ApiResponse<RefreshData>>

    @POST("auth/logout")
    suspend fun logout(
        @Body request: LogoutRequest
    ): Response<ApiResponse<Unit>>

    @GET("auth/validate")
    suspend fun validateToken(): Response<ValidateData>

    /** Forgot password — Twilio sends reset OTP to phone. */
    @POST("auth/forgot-password")
    suspend fun forgotPassword(
        @Body request: ForgotPasswordRequest
    ): Response<ForgotPasswordData>

    /** Reset password — verify Twilio OTP and set a new password. */
    @POST("auth/reset-password")
    suspend fun resetPassword(
        @Body request: ResetPasswordRequest
    ): Response<ApiResponse<Unit>>

    @POST("auth/google")
    suspend fun googleSignIn(
        @Body request: GoogleSignInRequest
    ): Response<AuthData>
}

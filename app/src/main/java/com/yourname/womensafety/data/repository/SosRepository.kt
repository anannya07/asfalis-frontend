package com.yourname.womensafety.data.repository

import com.yourname.womensafety.data.network.api.SosApiService
import com.yourname.womensafety.data.network.dto.*

class SosRepository(
    private val sosApi: SosApiService
) : BaseRepository() {

    suspend fun triggerSos(
        triggerType: String,
        latitude: Double,
        longitude: Double,
        accuracy: Float? = null
    ): NetworkResult<SosAlertData> {
        return safeApiCall {
            sosApi.triggerSos(SosTriggerRequest(triggerType, latitude, longitude, accuracy))
        }
    }

    suspend fun sendSosNow(alertId: String): NetworkResult<Unit> {
        return safeApiCall { sosApi.sendSosNow(SosSendNowRequest(alertId)) }
    }

    /**
     * Cancel an active SOS alert.
     *
     * [alertId] may be null — the backend will look up the caller's most-recent
     * active countdown alert automatically, returning 200 / "no_active_countdown"
     * if there is nothing to cancel.  This covers the IoT double-tap path where
     * the app may have navigated away before the cancel arrives.
     */
    suspend fun cancelSos(alertId: String? = null): NetworkResult<Unit> {
        return safeApiCall { sosApi.cancelSos(SosCancelRequest(alertId)) }
    }

    suspend fun markUserSafe(alertId: String): NetworkResult<SosSafeData> {
        return safeApiCall { sosApi.markUserSafe(SosSafeRequest(alertId)) }
    }

    suspend fun getSosHistory(): NetworkResult<List<SosHistoryItem>> {
        return safeApiCall { sosApi.getSosHistory() }
    }

    suspend fun getSosCountdown(alertId: String): NetworkResult<SosCountdownData> {
        return safeApiCall { sosApi.getSosCountdown(alertId) }
    }

    suspend fun testWhatsApp(toNumber: String, message: String): NetworkResult<Unit> {
        return safeApiCall { sosApi.testWhatsApp(TestWhatsAppRequest(toNumber, message)) }
    }
}

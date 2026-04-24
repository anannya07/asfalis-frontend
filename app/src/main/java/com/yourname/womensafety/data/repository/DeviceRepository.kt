package com.yourname.womensafety.data.repository

import com.yourname.womensafety.data.network.api.DeviceApiService
import com.yourname.womensafety.data.network.dto.*

class DeviceRepository(
    private val deviceApi: DeviceApiService
) : BaseRepository() {

    suspend fun registerDevice(
        deviceName: String,
        deviceMac: String,
        firmwareVersion: String? = "1.0.0"
    ): NetworkResult<DeviceData> = safeApiCall {
        deviceApi.registerDevice(RegisterDeviceRequest(deviceName, deviceMac, firmwareVersion))
    }

    suspend fun getDeviceStatus(): NetworkResult<DeviceData> = safeApiCall {
        deviceApi.getDeviceStatus()
    }

    suspend fun updateDeviceStatus(
        deviceId: String,
        isConnected: Boolean
    ): NetworkResult<DeviceData> = safeApiCall {
        deviceApi.updateDeviceStatus(deviceId, UpdateDeviceStatusRequest(isConnected))
    }

    suspend fun removeDevice(deviceId: String): NetworkResult<Unit> = safeApiCall {
        deviceApi.removeDevice(deviceId)
    }

    suspend fun sendButtonEvent(deviceMac: String, latitude: Double? = null, longitude: Double? = null): NetworkResult<Unit> = safeApiCall {
        deviceApi.sendButtonEvent(DeviceButtonEventRequest(deviceMac, latitude, longitude))
    }

    suspend fun sendDeviceAlert(deviceMac: String): NetworkResult<Unit> = safeApiCall {
        deviceApi.sendDeviceAlert(DeviceAlertRequest(deviceMac))
    }
}

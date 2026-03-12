package com.yourname.womensafety.data.network.dto

import com.google.gson.annotations.SerializedName

data class RegisterDeviceRequest(
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("device_mac") val deviceMac: String,
    @SerializedName("firmware_version") val firmwareVersion: String? = null
)

data class UpdateDeviceStatusRequest(
    @SerializedName("is_connected") val isConnected: Boolean
)

data class DeviceData(
    @SerializedName("device_id")             val deviceId: String,
    @SerializedName("device_name")           val deviceName: String,
    @SerializedName("device_mac")            val deviceMac: String? = null,
    @SerializedName("is_connected")          val isConnected: Boolean,
    @SerializedName("battery_level")         val batteryLevel: Int? = null,
    @SerializedName("firmware_version")      val firmwareVersion: String? = null,
    @SerializedName("signal_strength")       val signalStrength: String? = null,
    @SerializedName("last_seen")             val lastSeen: String? = null,
    @SerializedName("last_button_press_at")  val lastButtonPressAt: String? = null
)

// ── Help / Support ────────────────────────────────────────────────────────────

data class FaqItem(
    @SerializedName("id") val id: Int,
    @SerializedName("question") val question: String,
    @SerializedName("answer") val answer: String,
    @SerializedName("category") val category: String,
    @SerializedName("icon") val icon: String
)

data class CreateTicketRequest(
    @SerializedName("subject") val subject: String,
    @SerializedName("message") val message: String
)

data class TicketData(
    @SerializedName("ticket_id") val ticketId: String,
    @SerializedName("subject") val subject: String? = null,
    @SerializedName("status") val status: String,
    @SerializedName("created_at") val createdAt: String
)

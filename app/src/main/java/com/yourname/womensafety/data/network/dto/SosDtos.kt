package com.yourname.womensafety.data.network.dto

import com.google.gson.annotations.SerializedName

data class SosTriggerRequest(
    @SerializedName("trigger_type") val triggerType: String,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("accuracy") val accuracy: Float? = null
)

data class SosSendNowRequest(
    @SerializedName("alert_id") val alertId: String
)

data class SosCancelRequest(
    // Nullable: backend /cancel accepts an omitted alert_id and looks up the
    // caller's most-recent active countdown alert automatically.
    @SerializedName("alert_id") val alertId: String? = null
)

data class SosSafeRequest(
    @SerializedName("alert_id") val alertId: String
)

data class SosAlertData(
    @SerializedName("alert_id")         val alertId: String,
    @SerializedName("trigger_type")     val triggerType: String? = null,
    @SerializedName("address")          val address: String? = null,
    @SerializedName("status")           val status: String,
    @SerializedName("triggered_at")     val triggeredAt: String? = null,
    @SerializedName("sent_at")          val sentAt: String? = null,
    @SerializedName("resolved_at")      val resolvedAt: String? = null,
    @SerializedName("resolution_type")  val resolutionType: String? = null,
    @SerializedName("timezone")         val timezone: String? = null,
    @SerializedName("countdown_seconds") val countdownSeconds: Int? = null,
    @SerializedName("contacts_to_notify") val contactsToNotify: Int? = null
)

// Returned by POST /api/sos/safe
data class SosSafeData(
    @SerializedName("alert_id")        val alertId: String,
    @SerializedName("trigger_type")    val triggerType: String? = null,
    @SerializedName("status")          val status: String,
    @SerializedName("resolution_type") val resolutionType: String? = null,
    @SerializedName("triggered_at")    val triggeredAt: String? = null,
    @SerializedName("resolved_at")     val resolvedAt: String? = null,
    @SerializedName("contacts_notified") val contactsNotified: Int? = null,
    @SerializedName("timezone")        val timezone: String? = null
)

data class SosHistoryItem(
    // History endpoint returns "id", trigger returns "alert_id" — handle both
    @SerializedName("alert_id")        val alertId: String? = null,
    @SerializedName("id")              val id: String? = null,
    @SerializedName("trigger_type")    val triggerType: String,
    @SerializedName("address")         val address: String? = null,
    @SerializedName("status")          val status: String,
    @SerializedName("triggered_at")    val triggeredAt: String,
    @SerializedName("sent_at")         val sentAt: String? = null,
    @SerializedName("resolved_at")     val resolvedAt: String? = null,
    @SerializedName("resolution_type") val resolutionType: String? = null,
    @SerializedName("timezone")        val timezone: String? = null
) {
    /** Resolved display ID — prefers alert_id, falls back to id. */
    val displayId: String get() = alertId ?: id ?: ""
}

// Returned by GET /api/sos/countdown/{alertId}
data class SosCountdownData(
    @SerializedName("alert_id")             val alertId: String,
    @SerializedName("status")               val status: String,
    @SerializedName("trigger_type")         val triggerType: String? = null,
    @SerializedName("triggered_at")         val triggeredAt: String? = null,
    @SerializedName("countdown_seconds")    val countdownSeconds: Int? = null,
    @SerializedName("seconds_remaining")    val secondsRemaining: Float? = null,
    @SerializedName("countdown_expires_at") val countdownExpiresAt: String? = null,
    @SerializedName("is_active")            val isActive: Boolean = false
)

data class TestWhatsAppRequest(
    @SerializedName("to_number") val toNumber: String,
    @SerializedName("message")   val message: String
)

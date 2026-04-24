package com.yourname.womensafety.ui.screens

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.tasks.CancellationTokenSource
import com.yourname.womensafety.data.IotAction
import com.yourname.womensafety.data.IotEventBus
import com.yourname.womensafety.ui.viewmodels.SosViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@Suppress("MissingPermission")
@Composable
fun SOSAlertScreen(
    triggerType: String = "manual",
    /** Pre-existing alert ID from Auto SOS (predict API). When set, skips triggerSos(). */
    existingAlertId: String? = null,
    onSafe: () -> Unit
) {
    val context = LocalContext.current
    val sosViewModel: SosViewModel = viewModel(factory = SosViewModel.Factory)
    val uiState by sosViewModel.uiState.collectAsStateWithLifecycle()
    // Countdown starts ONLY after the backend confirms the alert (alertId set, isTriggering=false)
    var ticks by remember { mutableIntStateOf(10) }
    var countdownStarted by remember { mutableStateOf(false) }
    var pendingHomeNavigation by remember { mutableStateOf(false) }
    // Set to true when IotWearableManager cancels via wearable double-tap.
    // IotWearableManager calls the cancel API directly (bypasses SosViewModel),
    // so uiState.isCancelled never becomes true in that path — we need this flag
    // to stop the countdown and prevent sendNow() from firing.
    var wearableCancelled by remember { mutableStateOf(false) }

    // Determine if this is an automatic trigger
    val isAutomatic = triggerType != "manual"

    // For Auto SOS: the alert was already created by predict API — init with existing alertId.
    // For manual SOS: trigger a new alert. The ViewModel handles GPS internally to survive decomposition.
    LaunchedEffect(Unit) {
        if (existingAlertId != null) {
            sosViewModel.initWithExistingAlert(existingAlertId)
        } else {
            Log.d("SOSAlertScreen", "Triggering SOS: type=$triggerType")
            sosViewModel.triggerSos(triggerType = triggerType)
        }
    }

    // Collect wearable cancel events.
    // IotWearableManager bypasses SosViewModel, so uiState.isCancelled is never
    // set in the wearable double-tap path. This LaunchedEffect sets wearableCancelled
    // so the countdown stops immediately and sendNow() is not fired.
    LaunchedEffect(Unit) {
        IotEventBus.events.collect { action ->
            if (action is IotAction.Cancelled) {
                wearableCancelled = true
            }
        }
    }

    // Start countdown once trigger succeeds (alertId received, isTriggering=false)
    // This prevents sendNow() from firing with a null alertId (race condition fix).
    LaunchedEffect(uiState.alertId, uiState.isTriggering) {
        if (uiState.alertId != null && !uiState.isTriggering && !countdownStarted) {
            countdownStarted = true
            Log.d("SOSAlertScreen", "Trigger confirmed alertId=${uiState.alertId} — starting countdown")
            while (ticks > 0 && !uiState.isCancelled && !wearableCancelled) {
                delay(1000L)
                ticks--
            }
            // Only dispatch if we are genuinely un-cancelled at the moment ticks hit 0.
            if (!uiState.isCancelled && !uiState.isSent && !uiState.isSending && !wearableCancelled) {
                sosViewModel.sendNow()
            }
        }
    }

    fun handleBackToHome() {
        val isActiveAndUnsent = !uiState.isCancelled && !uiState.isSent && !uiState.isSending
        val shouldSendBeforeLeaving = isActiveAndUnsent && uiState.alertId != null

        when {
            // Trigger still in-flight — abort it and go home safely
            uiState.isTriggering || uiState.isConnectionTimeout -> {
                sosViewModel.abortTrigger()
                // isCancelled will become true via abortTrigger(), navigation handled by LaunchedEffect
            }
            shouldSendBeforeLeaving -> {
                pendingHomeNavigation = true
                sosViewModel.sendNow()
            }
            isActiveAndUnsent && uiState.alertId == null -> {
                // Trigger failed entirely — safe to exit
                onSafe()
            }
            else -> {
                // Already sent or cancelled — submit feedback then navigate
                if (isAutomatic && uiState.isSent) {
                    uiState.alertId?.let { sosViewModel.submitFeedback(it, isFalseAlarm = false) }
                }
                onSafe()
            }
        }
    }

    BackHandler(onBack = { handleBackToHome() })

    // If user chose to go home during active alert, wait for send completion then navigate.
    LaunchedEffect(pendingHomeNavigation, uiState.isSent) {
        if (pendingHomeNavigation && uiState.isSent) {
            pendingHomeNavigation = false
            // Submit feedback for auto SOS: dispatched = real danger
            if (isAutomatic) {
                uiState.alertId?.let { sosViewModel.submitFeedback(it, isFalseAlarm = false) }
            }
            onSafe()
        }
    }

    // If Home was requested before alert creation completed, dispatch as soon as alertId becomes available.
    LaunchedEffect(pendingHomeNavigation, uiState.alertId, uiState.isSent, uiState.isCancelled, uiState.isSending) {
        if (pendingHomeNavigation
            && uiState.alertId != null
            && !uiState.isSent
            && !uiState.isCancelled
            && !uiState.isSending
        ) {
            sosViewModel.sendNow()
        }
    }

    // Stop pending navigation if send failed.
    LaunchedEffect(pendingHomeNavigation, uiState.errorMessage) {
        if (pendingHomeNavigation && uiState.errorMessage != null) {
            pendingHomeNavigation = false
        }
    }

    // Navigate back when cancelled or sent
    LaunchedEffect(uiState.isCancelled) {
        if (uiState.isCancelled) {
            if (isAutomatic) {
                uiState.alertId?.let { sosViewModel.submitFeedback(it, isFalseAlarm = true) }
            }
            onSafe()
        }
    }

    // Navigate back when wearable double-tap cancel confirmed
    LaunchedEffect(wearableCancelled) {
        if (wearableCancelled) {
            if (isAutomatic) {
                uiState.alertId?.let { sosViewModel.submitFeedback(it, isFalseAlarm = true) }
            }
            onSafe()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "alert")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.15f, targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "glow"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black, Color(0xFF2D0000).copy(alpha = alpha))
                )
            )
    ) {
        // --- BACK TO HOME BUTTON (TOP LEFT) ---
        IconButton(
            onClick = { handleBackToHome() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Back to Home",
                tint = Color.White.copy(0.8f),
                modifier = Modifier.size(28.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // --- ALERT ICON ---
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(Color.Red.copy(0.08f), CircleShape)
                    .border(1.5.dp, Color.Red.copy(0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color.Red,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "ALERT TRIGGERED",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )

            Text(
                text = if (isAutomatic) "Unusual Movement Detected" else "Emergency SOS Triggered",
                color = Color.Red.copy(0.9f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp)
            )

            Text(
                text = if (isAutomatic) 
                    "A sudden impact or fall was detected by\nyour device sensors."
                else
                    "You have manually triggered an\nemergency SOS alert.",
                color = Color.Gray,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(50.dp))

            // --- COUNTDOWN ---
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { ticks / 10f },
                    modifier = Modifier.size(180.dp),
                    color = Color.Red,
                    strokeWidth = 6.dp,
                    trackColor = Color.White.copy(0.05f),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = ticks.toString(),
                        color = Color.White,
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "SECONDS",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(30.dp))

            // --- STATUS TEXT ---
            Text(
                text = when {
                    uiState.isConnectionTimeout -> "⚠️ Server starting up… you can cancel safely"
                    uiState.isTriggering        -> "Connecting to server..."
                    uiState.isCancelled         -> "False Alarm — Alert Cancelled"
                    uiState.isSent              -> "SOS Dispatched!"
                    uiState.isSending || pendingHomeNavigation || (ticks == 0 && countdownStarted) -> "Dispatching SOS..."
                    countdownStarted            -> "SOS Pending..."
                    else                        -> "Connecting to server..."
                },
                color = when {
                    uiState.isConnectionTimeout -> Color(0xFFFFAA00)
                    uiState.isCancelled         -> Color(0xFF4CAF50)
                    uiState.isSent              -> Color(0xFF00E676)
                    else                        -> Color.White.copy(0.7f)
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(50.dp))

            // --- I'M SAFE BUTTON ---
            Button(
                onClick = { sosViewModel.cancelSos() },
                // Always enabled when connection timed out — user must never be trapped.
                // Otherwise disabled while: isCancelled (already resolved), isSending (race condition guard),
                // isCancelling (API already in-flight — double-tap guard)
                enabled = uiState.isConnectionTimeout ||
                    (!uiState.isCancelled && !uiState.isSending && !uiState.isCancelling),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1111)),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.1f))
            ) {
                if (uiState.isCancelling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("I'M SAFE", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // --- SEND NOW BUTTON ---
            Button(
                onClick = { sosViewModel.sendNow() },
                enabled = !uiState.isSent && !uiState.isSending,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC60000)),
                shape = RoundedCornerShape(18.dp)
            ) {
                if (uiState.isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("SEND SOS NOW", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // --- BACK TO HOME BUTTON ---
            OutlinedButton(
                onClick = { handleBackToHome() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.25f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text("BACK TO HOME", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }

            // Error + Retry
            uiState.errorMessage?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(msg, color = Color.Red, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                // Only show Retry for trigger-phase errors (no alertId yet)
                if (uiState.alertId == null) {
                    OutlinedButton(
                        onClick = { sosViewModel.triggerSos(triggerType) },
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Red.copy(0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Retry", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // --- FOOTER ---
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 30.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.GpsFixed,
                contentDescription = null,
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Location sharing is active",
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}


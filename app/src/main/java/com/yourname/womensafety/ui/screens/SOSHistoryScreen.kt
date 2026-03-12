package com.yourname.womensafety.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.yourname.womensafety.data.IotAction
import com.yourname.womensafety.data.IotEventBus
import com.yourname.womensafety.data.SecurityPolicyManager
import com.yourname.womensafety.data.network.dto.SosHistoryItem
import com.yourname.womensafety.ui.components.SecureScreen
import com.yourname.womensafety.ui.viewmodels.SosHistoryUiState
import com.yourname.womensafety.ui.viewmodels.SosHistoryViewModel
import kotlinx.coroutines.delay

@Composable
fun SOSHistoryScreen(navController: NavController) {
    val securityPolicy by SecurityPolicyManager.state.collectAsState()
    SecureScreen(
        enabled = securityPolicy.screenshotProtectionEnabled &&
            "sos_history" in securityPolicy.protectedScreens
    )

    val historyViewModel: SosHistoryViewModel = viewModel(
        factory = SosHistoryViewModel.Factory
    )
    val uiState by historyViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        historyViewModel.loadHistory()
    }

    // Re-load history when an IoT SOS event resolves so that wearable-triggered
    // alerts appear immediately, even if the user is already on this screen.
    LaunchedEffect(Unit) {
        IotEventBus.events.collect { action ->
            if (action is IotAction.Triggered || action is IotAction.Cancelled) {
                delay(2_000L) // brief pause for the backend to record the event
                historyViewModel.loadHistory()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black, Color(0xFF1a0000), Color(0xFF2d0000))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(16.dp))
            IconButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.clip(CircleShape).background(Color.White.copy(0.08f))
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("ASFALIS", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Alert History", color = Color.Gray, fontSize = 16.sp)
                }
                Surface(
                    modifier = Modifier.size(44.dp).clip(CircleShape),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.08f)
                ) {
                    Icon(Icons.Outlined.History, contentDescription = null, tint = Color.White, modifier = Modifier.padding(10.dp))
                }
            }

            Spacer(Modifier.height(32.dp))

            when (val state = uiState) {
                is SosHistoryUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFFE10600))
                    }
                }
                is SosHistoryUiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.message, color = Color(0xFFE10600), fontSize = 15.sp)
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { historyViewModel.loadHistory() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE10600))
                            ) { Text("Retry") }
                        }
                    }
                }
                is SosHistoryUiState.Success -> {
                    if (state.items.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No SOS alerts triggered yet.", color = Color.Gray, fontSize = 16.sp)
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(bottom = 32.dp)
                        ) {
                            items(state.items) { item ->
                                SosHistoryCard(item)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SosHistoryCard(item: SosHistoryItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White.copy(0.05f),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.1f))
    ) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(50.dp).clip(CircleShape).background(Color(0xFFE10600).copy(0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.LocationOn, null, tint = Color(0xFFE10600), modifier = Modifier.size(26.dp))
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (item.triggerType) {
                        "iot_button" -> "IoT Button"
                        "auto_sos"   -> "Auto SOS"
                        else         -> item.triggerType.replaceFirstChar { it.uppercase() }
                    },
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = item.triggeredAt,
                    color = Color.Gray,
                    fontSize = 13.sp
                )
                item.address?.let {
                    Text(text = it, color = Color.Gray, fontSize = 12.sp)
                }
            }

            Surface(
                color = when (item.status) {
                    // Resolved states — muted grey
                    "cancelled", "expired" -> Color.Gray.copy(0.2f)
                    // Active / dispatched states — red accent
                    else                   -> Color(0xFFE10600).copy(0.2f)
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = item.status.uppercase(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = when (item.status) {
                        "cancelled", "expired" -> Color.Gray
                        else                   -> Color(0xFFE10600)
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}


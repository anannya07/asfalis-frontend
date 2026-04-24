package com.yourname.womensafety.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
import com.yourname.womensafety.data.network.dto.UpdateSettingsRequest
import com.yourname.womensafety.ui.viewmodels.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory
    )
    val isLoading by settingsViewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by settingsViewModel.errorMessage.collectAsStateWithLifecycle()
    val loadedSettings by settingsViewModel.settings.collectAsStateWithLifecycle()

    // Local editable state — populated from API on load
    var sensitivity by remember { mutableStateOf("Medium") }
    var sosMessage by remember {
        mutableStateOf("Emergency! I need help. This is an automated SOS alert from ASFALIS. My live location is attached.")
    }
    var autoSosEnabled by remember { mutableStateOf(false) }

    // Populate local state when settings arrive from API.
    // Using loadedSettings != null as key ensures this seeds only once on first load,
    // not on every recomposition — prevents the toggle fighting user input.
    var settingsSeeded by remember { mutableStateOf(false) }
    LaunchedEffect(loadedSettings) {
        if (loadedSettings != null && !settingsSeeded) {
            settingsSeeded = true
            loadedSettings?.let { s ->
                sensitivity = when (s.shakeSensitivity) {
                    "low"  -> "Low"
                    "high" -> "High"
                    else   -> "Medium"
                }
                sosMessage = s.sosMessage
                autoSosEnabled = s.autoSosEnabled
            }
        }
    }

    // Load settings from API
    LaunchedEffect(Unit) {
        settingsViewModel.loadSettings()
    }

    // Collect save success as a one-shot SharedFlow event.
    // SharedFlow does NOT retain last value, so this never fires on screen re-entry.
    LaunchedEffect(Unit) {
        settingsViewModel.saveSuccess.collect {
            navController.popBackStack()
        }
    }

    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(Color.Black, Color(0xFF1A0000), Color(0xFF2D0000))
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient)
            .statusBarsPadding()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(20.dp))

        // --- Header ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.clip(CircleShape).background(Color.White.copy(0.08f))
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                }
                Spacer(Modifier.width(16.dp))
                Text("App Settings", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }

            // Save Checkmark Icon
            IconButton(
                onClick = {
                    val request = UpdateSettingsRequest(
                        sosMessage = sosMessage.trim().take(500),
                        shakeSensitivity = sensitivity.lowercase(),
                        autoSosEnabled = autoSosEnabled
                    )
                    settingsViewModel.saveSettings(request)
                },
                modifier = Modifier.clip(CircleShape).background(Color(0xFFE10600)),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Check, null, tint = Color.White)
                }
            }
        }

        // Error message
        errorMessage?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Color(0xFFE10600), fontSize = 13.sp)
        }

        Spacer(Modifier.height(40.dp))

        // --- SECTION: SOS MESSAGE ---
        SettingsHeader(Icons.Default.Message, "SOS Message Content")
        Text(
            "This message will be sent to all your trusted contacts when SOS is triggered.",
            color = Color.Gray,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        OutlinedTextField(
            value = sosMessage,
            onValueChange = { if (it.length <= 500) sosMessage = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = Color.White.copy(0.05f),
                unfocusedContainerColor = Color.White.copy(0.05f),
                focusedBorderColor = Color(0xFFE10600),
                unfocusedBorderColor = Color.White.copy(0.1f)
            ),
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 20.sp),
            supportingText = { Text("${sosMessage.length}/500", color = Color.Gray, fontSize = 11.sp) }
        )

        Spacer(Modifier.height(32.dp))

        // --- SECTION: SHAKE SENSITIVITY ---
        SettingsHeader(Icons.Default.GraphicEq, "Shake Sensitivity")
        Text(
            "Adjust the intensity of the shake required to automatically trigger an SOS alert.",
            color = Color.Gray,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White.copy(0.03f),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.05f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    listOf("Low", "Medium", "High").forEach { level ->
                        val isSelected = sensitivity == level
                        Button(
                            onClick = { sensitivity = level },
                            modifier = Modifier
                                .weight(1f)
                                .height(45.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) Color(0xFFE10600) else Color.White.copy(0.05f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                text = level,
                                color = if (isSelected) Color.White else Color.Gray,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Status Description
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(0.05f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = when (sensitivity) {
                            "Low" -> "Optimized for high-activity environments. Requires a very vigorous shake."
                            "High" -> "Maximum sensitivity. Triggers easily; recommended for emergency use only."
                            else -> "Balanced detection. Triggers on a deliberate, strong shake."
                        },
                        color = Color.White.copy(0.7f),
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(40.dp))

        // --- SECTION: AUTO SOS ---
        SettingsHeader(Icons.Default.Sensors, "Auto SOS")
        Text(
            "When enabled, your device sensors continuously monitor for sudden impacts or falls. " +
                "A danger prediction from our ML model will automatically start an SOS countdown.",
            color = Color.Gray,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White.copy(0.03f),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (autoSosEnabled) Color(0xFFE10600).copy(0.3f) else Color.White.copy(0.05f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (autoSosEnabled) "Auto SOS Active" else "Auto SOS Disabled",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = if (autoSosEnabled)
                            "Sensor monitoring will start when shield is armed"
                        else
                            "Toggle on to enable motion-based SOS",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = autoSosEnabled,
                    onCheckedChange = { autoSosEnabled = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFFE10600),
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color.White.copy(0.1f)
                    )
                )
            }
        }

        Spacer(Modifier.height(40.dp))

        // Help Note
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Info, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Motion data is analyzed locally for your privacy.", color = Color.Gray, fontSize = 12.sp)
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun SettingsHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Icon(icon, null, tint = Color(0xFFE10600), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

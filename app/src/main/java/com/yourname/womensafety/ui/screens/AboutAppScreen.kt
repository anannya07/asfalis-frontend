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
import androidx.compose.material.icons.outlined.Shield // Matches Figma Outline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

@Composable
fun AboutAppScreen(navController: NavController) {
    // Figma Background: Black to deep Red blend
    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            Color.Black,
            Color(0xFF1A0000), // Very dark red
            Color(0xFF330000)  // Deep red at the bottom
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        // --- Header ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(0.08f))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Spacer(Modifier.width(16.dp))
            Text(
                "About App",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))

            // --- App Logo with Glow ---
            Box(contentAlignment = Alignment.Center) {
                // Outer Glow effect
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .background(Color(0xFFE10600).copy(0.25f), CircleShape)
                        .blur(40.dp)
                )
                // Shield Logo Container
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFFE10600), Color(0xFF8B0000))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // Corrected to Outlined Shield per Figma
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(55.dp)
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            Text("Asfalis", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("Women Safety", color = Color(0xFF60A5FA), fontSize = 16.sp) // Light blueish gray

            Spacer(Modifier.height(32.dp))

            // --- Description ---
            Surface(
                color = Color.White.copy(0.05f),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.1f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Asfalis is a personal safety companion designed to protect women using smart technology and real-time monitoring.",
                    color = Color.White.copy(0.8f),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp,
                    modifier = Modifier.padding(24.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            // --- Info Rows ---
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AboutInfoRow("Version", "1.0.0")
                AboutInfoRow("Developer", "Asfalis Technologies")
                AboutInfoRow("Release Date", "January 2026")
                AboutInfoRow("Platform", "Android")
            }

            Spacer(Modifier.height(48.dp))

            Text(
                "© 2026 Asfalis. All rights reserved.",
                color = Color.Gray,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)
            )
        }
    }
}

@Composable
fun AboutInfoRow(label: String, value: String) {
    Surface(
        color = Color.White.copy(0.03f),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.Gray, fontSize = 14.sp)
            Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
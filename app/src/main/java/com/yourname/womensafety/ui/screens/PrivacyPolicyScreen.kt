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
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

@Composable
fun PrivacyPolicyScreen(navController: NavController) {
    // --- GRADIENT DEFINITION (Consistent with your brand) ---
    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            Color.Black,
            Color(0xFF1A0000), // Dark red blend
            Color(0xFF330000)  // Deep red base
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient)
            .statusBarsPadding()
    ) {
        // --- Header (Sticky effect achieved via Column layout) ---
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
                "Privacy Policy",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // --- Scrollable Content ---
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Surface(
                color = Color.White.copy(0.05f),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.1f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        "Last Updated: January 4, 2026",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )

                    Spacer(Modifier.height(24.dp))

                    PolicySection("1. Introduction", "Welcome to Asfalis. We are committed to protecting your personal information and your right to privacy. This Privacy Policy explains how we collect, use, disclose, and safeguard your information when you use our mobile application.")

                    PolicySection("2. Information We Collect", "We collect the following types of information:")
                    BulletPoint("Location Data: Real-time GPS coordinates for emergency services")
                    BulletPoint("Device Information: Motion sensor data, device model, and OS version")
                    BulletPoint("Contact Information: Emergency contacts you provide")
                    BulletPoint("Account Data: Name, email, and phone number")

                    PolicySection("3. How We Use Your Information", "Your information is used to:")
                    BulletPoint("Provide emergency alerts and location sharing")
                    BulletPoint("Monitor unusual movements and trigger automatic SOS")
                    BulletPoint("Improve app functionality and user experience")
                    BulletPoint("Communicate important safety updates")

                    PolicySection("4. Data Security", "We implement industry-standard security measures to protect your personal information. All location data is encrypted end-to-end and is only shared with your designated emergency contacts when an alert is triggered.")

                    PolicySection("5. Your Rights", "You have the right to access, update, or delete your personal information at any time. You can also opt-out of location tracking, though this may limit the app's safety features.")

                    PolicySection("6. Third-Party Services", "We do not sell or share your personal information with third parties for marketing purposes. We may share data with emergency services when required by law or when you trigger an SOS alert.")

                    PolicySection("7. Changes to This Policy", "We may update this Privacy Policy from time to time. We will notify you of any changes by posting the new policy on this page and updating the \"Last Updated\" date.")

                    PolicySection("8. Contact Us", "If you have any questions about this Privacy Policy, please contact us at:")
                    Text(
                        "privacy@asfalis.com",
                        color = Color(0xFFE10600),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
fun PolicySection(title: String, content: String) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(content, color = Color.Gray, fontSize = 15.sp, lineHeight = 22.sp)
    }
}

@Composable
fun BulletPoint(text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)) {
        Text("•", color = Color(0xFFE10600), fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.Gray, fontSize = 14.sp, lineHeight = 20.sp)
    }
}
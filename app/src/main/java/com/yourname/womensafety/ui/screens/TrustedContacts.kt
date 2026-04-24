package com.yourname.womensafety.ui.screens

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.yourname.womensafety.data.SecurityPolicyManager
import com.yourname.womensafety.data.network.dto.TrustedContact
import com.yourname.womensafety.ui.components.SecureScreen
import com.yourname.womensafety.ui.viewmodels.ContactsViewModel

// ─── Country dial-code support ───────────────────────────────────────────────

data class CountryDialCode(val name: String, val dialCode: String, val isoCode: String)

/** Converts a 2-letter ISO code to its flag emoji (Unicode regional indicators). */
private fun isoToFlag(iso: String): String = iso.uppercase()
    .map { char -> String(Character.toChars(0x1F1E6 + (char.code - 'A'.code))) }
    .joinToString("")

val CountryDialCode.flag: String get() = isoToFlag(isoCode)

val ALL_COUNTRIES: List<CountryDialCode> = listOf(
    CountryDialCode("India", "+91", "IN"),
    CountryDialCode("United States", "+1", "US"),
    CountryDialCode("United Kingdom", "+44", "GB"),
    CountryDialCode("Canada", "+1", "CA"),
    CountryDialCode("Australia", "+61", "AU"),
    CountryDialCode("Germany", "+49", "DE"),
    CountryDialCode("France", "+33", "FR"),
    CountryDialCode("Italy", "+39", "IT"),
    CountryDialCode("Spain", "+34", "ES"),
    CountryDialCode("Netherlands", "+31", "NL"),
    CountryDialCode("Sweden", "+46", "SE"),
    CountryDialCode("Norway", "+47", "NO"),
    CountryDialCode("Denmark", "+45", "DK"),
    CountryDialCode("Finland", "+358", "FI"),
    CountryDialCode("Switzerland", "+41", "CH"),
    CountryDialCode("Austria", "+43", "AT"),
    CountryDialCode("Belgium", "+32", "BE"),
    CountryDialCode("Portugal", "+351", "PT"),
    CountryDialCode("Russia", "+7", "RU"),
    CountryDialCode("China", "+86", "CN"),
    CountryDialCode("Japan", "+81", "JP"),
    CountryDialCode("South Korea", "+82", "KR"),
    CountryDialCode("Singapore", "+65", "SG"),
    CountryDialCode("Malaysia", "+60", "MY"),
    CountryDialCode("Indonesia", "+62", "ID"),
    CountryDialCode("Thailand", "+66", "TH"),
    CountryDialCode("Philippines", "+63", "PH"),
    CountryDialCode("Vietnam", "+84", "VN"),
    CountryDialCode("Bangladesh", "+880", "BD"),
    CountryDialCode("Pakistan", "+92", "PK"),
    CountryDialCode("Sri Lanka", "+94", "LK"),
    CountryDialCode("Nepal", "+977", "NP"),
    CountryDialCode("Bhutan", "+975", "BT"),
    CountryDialCode("Maldives", "+960", "MV"),
    CountryDialCode("Myanmar", "+95", "MM"),
    CountryDialCode("UAE", "+971", "AE"),
    CountryDialCode("Saudi Arabia", "+966", "SA"),
    CountryDialCode("Qatar", "+974", "QA"),
    CountryDialCode("Kuwait", "+965", "KW"),
    CountryDialCode("Bahrain", "+973", "BH"),
    CountryDialCode("Oman", "+968", "OM"),
    CountryDialCode("Israel", "+972", "IL"),
    CountryDialCode("Turkey", "+90", "TR"),
    CountryDialCode("South Africa", "+27", "ZA"),
    CountryDialCode("Nigeria", "+234", "NG"),
    CountryDialCode("Kenya", "+254", "KE"),
    CountryDialCode("Ghana", "+233", "GH"),
    CountryDialCode("Egypt", "+20", "EG"),
    CountryDialCode("Brazil", "+55", "BR"),
    CountryDialCode("Mexico", "+52", "MX"),
    CountryDialCode("Argentina", "+54", "AR"),
    CountryDialCode("Colombia", "+57", "CO"),
    CountryDialCode("Chile", "+56", "CL"),
    CountryDialCode("Peru", "+51", "PE"),
    CountryDialCode("New Zealand", "+64", "NZ"),
    CountryDialCode("Ireland", "+353", "IE"),
    CountryDialCode("Poland", "+48", "PL"),
    CountryDialCode("Ukraine", "+380", "UA"),
    CountryDialCode("Greece", "+30", "GR"),
    CountryDialCode("Czech Republic", "+420", "CZ"),
    CountryDialCode("Hungary", "+36", "HU"),
    CountryDialCode("Romania", "+40", "RO"),
)

// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun TrustedContactsScreen(navController: NavController) {
    val securityPolicy by SecurityPolicyManager.state.collectAsState()
    SecureScreen(
        enabled = securityPolicy.screenshotProtectionEnabled &&
            "trusted_contacts" in securityPolicy.protectedScreens
    )

    val contactsViewModel: ContactsViewModel = viewModel(
        factory = ContactsViewModel.Factory
    )
    val contacts by contactsViewModel.contacts.collectAsStateWithLifecycle()
    val isLoading by contactsViewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by contactsViewModel.errorMessage.collectAsStateWithLifecycle()
    val pendingOtpVerification by contactsViewModel.pendingOtpVerification.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Navigate to OTP verification screen when contact is added
    LaunchedEffect(pendingOtpVerification) {
        Log.d("TrustedContacts", "LaunchedEffect triggered: pendingOtpVerification=$pendingOtpVerification")
        pendingOtpVerification?.let { otpData ->
            Log.d("TrustedContacts", "Navigating to OTP screen: contactId=${otpData.contactId}, phone=${otpData.phone}")
            navController.navigate(
                "contact_otp_verification/${otpData.contactId}/${otpData.phone}/${otpData.name}/${otpData.expiresInSeconds}"
            )
            contactsViewModel.clearOtpVerification()
        }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var selectedCountry by remember { mutableStateOf(ALL_COUNTRIES.first()) } // India (+91) default
    var nameInput by remember { mutableStateOf("") }
    var phoneInput by remember { mutableStateOf("") }
    var relationInput by remember { mutableStateOf("") }

    // Validation error messages (null = no error)
    var nameError by remember { mutableStateOf<String?>(null) }
    var phoneError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        contactsViewModel.loadContacts()
    }

    val emergencyContacts = contacts.filter { it.isPrimary }
    val otherContacts = contacts.filter { !it.isPrimary }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black, Color(0xFF1a0000), Color(0xFF2d0000))
                )
            )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                ) {
                    Button(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC60000)),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Default.Add, null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Add New Contact", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(0.08f))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Text("Trusted Contacts", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }

                Text(
                    text = "Add people who will receive emergency alerts and your live location",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
                    lineHeight = 20.sp
                )

                errorMessage?.let {
                    Text(it, color = Color(0xFFE10600), fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                }

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFFE10600), modifier = Modifier.size(32.dp))
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding() + 20.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (emergencyContacts.isNotEmpty()) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, null, tint = Color(0xFFC60000), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Primary Contacts", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        items(emergencyContacts, key = { it.id }) { contact ->
                            ContactApiItem(
                                contact = contact,
                                isEmergency = true,
                                onDelete = { contactsViewModel.deleteContact(contact.id) },
                                onSetPrimary = { contactsViewModel.setPrimaryContact(contact.id) }
                            )
                        }
                    }

                    if (otherContacts.isNotEmpty()) {
                        item {
                            Text(
                                "Other Contacts",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                        }
                        items(otherContacts, key = { it.id }) { contact ->
                            ContactApiItem(
                                contact = contact,
                                isEmergency = false,
                                onDelete = { contactsViewModel.deleteContact(contact.id) },
                                onSetPrimary = { contactsViewModel.setPrimaryContact(contact.id) }
                            )
                        }
                    }

                    if (!isLoading && contacts.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillParentMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                                Text("No contacts yet. Add your first trusted contact.", color = Color.Gray, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        }

        // --- Add Contact Dialog ---
        if (showAddDialog) {
            // Validates the local part only (digits, optionally starting with 0, 6–15 digits)
            val localPhoneRegex = remember { Regex("^[0-9]{6,14}$") }
            var countryExpanded by remember { mutableStateOf(false) }
            var countrySearch by remember { mutableStateOf("") }

            fun validate(): Boolean {
                var valid = true
                nameError = if (nameInput.isBlank()) {
                    valid = false; "Name is required"
                } else null
                phoneError = if (phoneInput.isBlank()) {
                    valid = false; "Phone number is required"
                } else if (!localPhoneRegex.matches(phoneInput.trim())) {
                    valid = false; "Enter digits only, e.g. 9876543210"
                } else null
                return valid
            }

            fun resetDialog() {
                showAddDialog = false
                selectedCountry = ALL_COUNTRIES.first()
                nameInput = ""; phoneInput = ""; relationInput = ""
                nameError = null; phoneError = null
                countrySearch = ""
            }

            Dialog(onDismissRequest = { resetDialog() }) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .width(340.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF1A0000))
                            .padding(24.dp)
                    ) {
                        Text("Add Contact", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(20.dp))

                        // --- Name ---
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it; nameError = null },
                            label = { Text("Full Name *", color = Color.Gray) },
                            isError = nameError != null,
                            supportingText = nameError?.let { { Text(it, color = Color(0xFFE10600), fontSize = 11.sp) } },
                            singleLine = true,
                            colors = outlinedFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))

                        // --- Phone: country dropdown + local number ---
                        Text("Phone Number *", color = Color.Gray, fontSize = 12.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            // Country code button
                            Box {
                                OutlinedButton(
                                    onClick = { countryExpanded = true; countrySearch = "" },
                                    modifier = Modifier.height(56.dp),
                                    shape = RoundedCornerShape(4.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = Color.White.copy(0.05f)
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (phoneError != null) Color(0xFFE10600) else Color.White.copy(0.2f)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp)
                                ) {
                                    Text(
                                        text = "${selectedCountry.flag} ${selectedCountry.dialCode}",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        Icons.Default.ArrowDropDown, null,
                                        tint = Color.Gray, modifier = Modifier.size(16.dp)
                                    )
                                }

                                // Country picker dropdown
                                if (countryExpanded) {
                                    Dialog(onDismissRequest = { countryExpanded = false }) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(0.6f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth(0.85f)
                                                    .heightIn(max = 480.dp)
                                                    .clip(RoundedCornerShape(20.dp))
                                                    .background(Color(0xFF1A0000))
                                                    .padding(16.dp)
                                            ) {
                                                Text(
                                                    "Select Country",
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 16.sp
                                                )
                                                Spacer(Modifier.height(12.dp))
                                                OutlinedTextField(
                                                    value = countrySearch,
                                                    onValueChange = { countrySearch = it },
                                                    placeholder = { Text("Search country...", color = Color.Gray) },
                                                    singleLine = true,
                                                    colors = outlinedFieldColors(),
                                                    leadingIcon = {
                                                        Icon(Icons.Default.Search, null,
                                                            tint = Color.Gray, modifier = Modifier.size(18.dp))
                                                    },
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                Spacer(Modifier.height(8.dp))
                                                val filtered = ALL_COUNTRIES.filter {
                                                    it.name.contains(countrySearch, ignoreCase = true) ||
                                                    it.dialCode.contains(countrySearch)
                                                }
                                                LazyColumn(
                                                    state = rememberLazyListState(),
                                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                                ) {
                                                    items(filtered, key = { it.isoCode }) { country ->
                                                        val isSelected = country.isoCode == selectedCountry.isoCode
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .clip(RoundedCornerShape(10.dp))
                                                                .background(
                                                                    if (isSelected) Color(0xFFE10600).copy(0.15f)
                                                                    else Color.Transparent
                                                                )
                                                                .then(Modifier.clickableNoRipple {
                                                                    selectedCountry = country
                                                                    countryExpanded = false
                                                                    phoneError = null
                                                                })
                                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                        ) {
                                                            Text(country.flag, fontSize = 22.sp)
                                                            Text(
                                                                country.name,
                                                                color = if (isSelected) Color(0xFFE10600) else Color.White,
                                                                fontSize = 14.sp,
                                                                modifier = Modifier.weight(1f)
                                                            )
                                                            Text(
                                                                country.dialCode,
                                                                color = Color.Gray,
                                                                fontSize = 13.sp
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Local number field
                            OutlinedTextField(
                                value = phoneInput,
                                onValueChange = { phoneInput = it.filter { c -> c.isDigit() }; phoneError = null },
                                placeholder = { Text("9876543210", color = Color.Gray.copy(0.5f), fontSize = 13.sp) },
                                isError = phoneError != null,
                                supportingText = phoneError?.let { { Text(it, color = Color(0xFFE10600), fontSize = 11.sp) } },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = outlinedFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(12.dp))

                        // --- Relationship ---
                        OutlinedTextField(
                            value = relationInput,
                            onValueChange = { relationInput = it },
                            label = { Text("Relationship (optional)", color = Color.Gray) },
                            singleLine = true,
                            colors = outlinedFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(24.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { resetDialog() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.1f))
                            ) { Text("Cancel", color = Color.White) }

                            Button(
                                onClick = {
                                    if (validate()) {
                                        // Assemble full E.164 number: dialCode + local digits
                                        val fullPhone = selectedCountry.dialCode + phoneInput.trim()
                                        contactsViewModel.addContact(
                                            name = nameInput.trim(),
                                            phone = fullPhone,
                                            relationship = relationInput.trim().ifEmpty { null }
                                        )
                                        resetDialog()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE10600))
                            ) { Text("Add", color = Color.White) }
                        }
                    }
                }
            }
        }
    }
}

/** Clickable modifier without ripple — used for country list rows. */
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
}

// ─── Invite Dialog — shown after successfully adding a contact ────────────────
@Composable
fun InviteContactDialog(
    contact: TrustedContact,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val inviteText = buildString {
        appendLine(contact.inviteMessage ?: "I've added you as a trusted contact in Asfalis.")
        contact.whatsappJoinInfo?.let { wa ->
            appendLine()
            appendLine("To receive WhatsApp emergency alerts, save ${wa.twilioNumber} and send: \"${wa.sandboxCode}\"")
            appendLine("Or tap: ${wa.whatsappLink}")
        }
    }.trimEnd()

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(0.75f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(320.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF1A0000))
                    .padding(24.dp)
            ) {
                Text(
                    "Notify ${contact.name}?",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Send an invite so they can receive WhatsApp emergency alerts.",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Send SMS
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("smsto:${contact.phone}")
                                putExtra("sms_body", inviteText)
                            }
                            context.startActivity(intent)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE10600)),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Send SMS", color = Color.White, fontSize = 13.sp) }

                    // Share
                    Button(
                        onClick = {
                            val shareIntent = Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, inviteText)
                                },
                                "Invite ${contact.name}"
                            )
                            context.startActivity(shareIntent)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.1f)),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Share", color = Color.White, fontSize = 13.sp) }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Skip", color = Color.Gray, fontSize = 14.sp)
                }
            }
        }
    }
}

/** Shared color scheme for dialog OutlinedTextFields. */
@Composable
private fun outlinedFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedContainerColor = Color.White.copy(0.05f), unfocusedContainerColor = Color.White.copy(0.05f),
    focusedBorderColor = Color(0xFFE10600), unfocusedBorderColor = Color.White.copy(0.2f),
    errorBorderColor = Color(0xFFE10600), errorTextColor = Color.White,
    errorContainerColor = Color(0xFFE10600).copy(0.05f)
)

@Composable
fun ContactApiItem(
    contact: TrustedContact,
    isEmergency: Boolean,
    onDelete: () -> Unit,
    onSetPrimary: () -> Unit
) {
    val initials = contact.name.split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifEmpty { "?" }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF140808).copy(alpha = 0.7f),
        shape = RoundedCornerShape(18.dp),
        border = if (isEmergency) androidx.compose.foundation.BorderStroke(1.dp, Color.Red.copy(0.2f)) else null
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar circle
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (isEmergency) Color(0xFFC60000) else Color.White.copy(0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Text(initials, color = Color.White, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.width(14.dp))

            // Name + badge + phone + relationship  (fills available width)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // --- Row 1: Name + badge (single line, badge never wraps vertically) ---
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = contact.name,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // Verified / Pending badge — fixed height so it NEVER stretches
                    val badgeContainerColor = if (contact.isVerified)
                        Color(0xFF1A4D1A) else Color(0xFF4D2E00)
                    val badgeTextColor = if (contact.isVerified)
                        Color(0xFF80FF80) else Color(0xFFFFAA00)
                    val badgeLabel = if (contact.isVerified) "✓ Verified" else "⚠️ Pending"

                    Surface(
                        color = badgeContainerColor,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = badgeLabel,
                            color = badgeTextColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }

                // --- Row 2: Phone + relationship chip ---
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = contact.phone,
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                    contact.relationship?.let { rel ->
                        Surface(
                            color = if (isEmergency) Color.Red.copy(0.12f) else Color.White.copy(0.07f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = rel,
                                color = if (isEmergency) Color(0xFFFF8080) else Color.Gray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // Action buttons column — kept slim
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!isEmergency) {
                    IconButton(onClick = onSetPrimary, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "Set Primary",
                            tint = Color(0xFFFFD700).copy(0.5f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = Color.White.copy(0.35f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}


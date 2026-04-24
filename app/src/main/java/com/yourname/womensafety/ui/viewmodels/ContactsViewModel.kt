package com.yourname.womensafety.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yourname.womensafety.data.AppServiceLocator
import com.yourname.womensafety.data.network.dto.AddContactRequest
import com.yourname.womensafety.data.network.dto.AddContactResponse
import com.yourname.womensafety.data.network.dto.TrustedContact
import com.yourname.womensafety.data.network.dto.UpdateContactRequest
import com.yourname.womensafety.data.network.dto.VerifyContactOtpRequest
import com.yourname.womensafety.data.network.dto.ResendContactOtpRequest
import com.yourname.womensafety.data.repository.ContactsRepository
import com.yourname.womensafety.data.repository.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ContactsViewModel(
    private val contactsRepository: ContactsRepository
) : ViewModel() {

    private val _contacts = MutableStateFlow<List<TrustedContact>>(emptyList())
    val contacts: StateFlow<List<TrustedContact>> = _contacts

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    /** Set after a successful addContact — UI should navigate to OTP verification. */
    data class OtpVerificationData(
        val contactId: String,
        val phone: String,
        val name: String,
        val expiresInSeconds: Int
    )
    private val _pendingOtpVerification = MutableStateFlow<OtpVerificationData?>(null)
    val pendingOtpVerification: StateFlow<OtpVerificationData?> = _pendingOtpVerification

    /** Load contacts immediately on ViewModel creation — no LaunchedEffect dependency. */
    init {
        loadContacts()
    }

    fun loadContacts() {
        viewModelScope.launch {
            _isLoading.value = true
            when (val result = contactsRepository.getContacts()) {
                is NetworkResult.Success -> {
                    _contacts.value = result.data
                    _isLoading.value = false
                }
                is NetworkResult.Error -> {
                    _errorMessage.value = result.message
                    _isLoading.value = false
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun addContact(name: String, phone: String, relationship: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            Log.d("ContactsViewModel", "addContact called: name=$name, phone=$phone, relationship=$relationship")
            val request = AddContactRequest(
                name = name,
                phone = phone,
                email = null,
                relationship = relationship,
                isPrimary = false
            )
            when (val result = contactsRepository.addContact(request)) {
                is NetworkResult.Success -> {
                    Log.d("ContactsViewModel", "addContact success: contactId=${result.data.contactId}, phone=${result.data.phone}, expiresIn=${result.data.expiresInSeconds}")
                    // Trigger navigation to OTP verification screen
                    _pendingOtpVerification.value = OtpVerificationData(
                        contactId = result.data.contactId,
                        phone = result.data.phone,
                        name = name,
                        expiresInSeconds = result.data.expiresInSeconds
                    )
                    Log.d("ContactsViewModel", "pendingOtpVerification set: ${_pendingOtpVerification.value}")
                    _isLoading.value = false
                }
                is NetworkResult.Error -> {
                    Log.e("ContactsViewModel", "addContact error: ${result.message}")
                    _errorMessage.value = result.message
                    _isLoading.value = false
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun verifyContactOtp(contactId: String, otpCode: String, isPrimary: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            Log.d("ContactsViewModel", "verifyContactOtp: contactId=$contactId, otpCode=$otpCode, isPrimary=$isPrimary")
            val request = VerifyContactOtpRequest(contactId, otpCode, isPrimary)
            when (val result = contactsRepository.verifyContactOtp(request)) {
                is NetworkResult.Success -> {
                    Log.d("ContactsViewModel", "verifyContactOtp success: contact=${result.data}, isVerified=${result.data.isVerified}")
                    // Reload all contacts from server to get latest state
                    loadContacts()
                }
                is NetworkResult.Error -> {
                    Log.e("ContactsViewModel", "verifyContactOtp error: ${result.message}")
                    _errorMessage.value = result.message
                    _isLoading.value = false
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun resendContactOtp(contactId: String, onSuccess: (Int) -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            val request = ResendContactOtpRequest(contactId)
            when (val result = contactsRepository.resendContactOtp(request)) {
                is NetworkResult.Success -> {
                    onSuccess(result.data.expiresInSeconds)
                    _isLoading.value = false
                }
                is NetworkResult.Error -> {
                    _errorMessage.value = result.message
                    _isLoading.value = false
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun clearOtpVerification() { _pendingOtpVerification.value = null }

    fun dismissInvite() { /* No longer used */ }

    fun deleteContact(contactId: String) {
        viewModelScope.launch {
            when (contactsRepository.deleteContact(contactId)) {
                is NetworkResult.Success -> {
                    _contacts.value = _contacts.value.filter { it.id != contactId }
                }
                is NetworkResult.Error -> {
                    _errorMessage.value = "Failed to delete contact"
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun setPrimaryContact(contactId: String) {
        viewModelScope.launch {
            when (val result = contactsRepository.setPrimaryContact(contactId)) {
                is NetworkResult.Success -> {
                    // Update local list: set the returned contact as primary, clear others
                    _contacts.value = _contacts.value.map { contact ->
                        contact.copy(isPrimary = contact.id == contactId)
                    }
                }
                is NetworkResult.Error -> {
                    _errorMessage.value = result.message
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun updateContact(contactId: String, name: String?, phone: String?, relationship: String?) {
        viewModelScope.launch {
            val request = UpdateContactRequest(name, phone, relationship)
            when (val result = contactsRepository.updateContact(contactId, request)) {
                is NetworkResult.Success -> {
                    _contacts.value = _contacts.value.map {
                        if (it.id == contactId) result.data else it
                    }
                }
                is NetworkResult.Error -> _errorMessage.value = result.message
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun clearError() { _errorMessage.value = null }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ContactsViewModel(AppServiceLocator.contactsRepository) as T
            }
        }
    }
}

package com.yourname.womensafety.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yourname.womensafety.data.AppServiceLocator
import com.yourname.womensafety.data.network.dto.UpdateProfileRequest
import com.yourname.womensafety.data.network.dto.UserProfile
import com.yourname.womensafety.data.repository.AuthRepository
import com.yourname.womensafety.data.repository.NetworkResult
import com.yourname.womensafety.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class ProfileUiState {
    data object Loading : ProfileUiState()
    data class Success(val profile: UserProfile) : ProfileUiState()
    data class Error(val message: String) : ProfileUiState()
    data object LoggedOut : ProfileUiState()
    data object AccountDeleted : ProfileUiState()
}

class ProfileViewModel(
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _profileState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val profileState: StateFlow<ProfileUiState> = _profileState

    private val _updateState = MutableStateFlow<String?>(null)
    val updateError: StateFlow<String?> = _updateState

    fun loadProfile(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _profileState.value = ProfileUiState.Loading
            when (val result = userRepository.getProfile(forceRefresh)) {
                is NetworkResult.Success -> _profileState.value = ProfileUiState.Success(result.data)
                is NetworkResult.Error -> _profileState.value = ProfileUiState.Error(result.message)
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun updateProfile(request: UpdateProfileRequest) {
        viewModelScope.launch {
            when (val result = userRepository.updateProfile(request)) {
                is NetworkResult.Success -> _profileState.value = ProfileUiState.Success(result.data)
                is NetworkResult.Error -> _updateState.value = result.message
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun updateSosMessage(message: String) {
        viewModelScope.launch {
            when (val result = userRepository.updateSosMessage(message)) {
                is NetworkResult.Success -> {
                    // Reload profile to reflect updated message
                    loadProfile(forceRefresh = true)
                }
                is NetworkResult.Error -> _updateState.value = result.message
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            userRepository.clearCache()
            // Reset the Retrofit singleton so the next login gets a fresh OkHttpClient
            // without any stale token state from the previous session.
            com.yourname.womensafety.data.network.RetrofitClient.reset()
            _profileState.value = ProfileUiState.LoggedOut
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            when (userRepository.deleteAccount()) {
                is NetworkResult.Success -> {
                    authRepository.logout()   // clears all local tokens
                    userRepository.clearCache()
                    com.yourname.womensafety.data.network.RetrofitClient.reset()
                    _profileState.value = ProfileUiState.AccountDeleted
                }
                is NetworkResult.Error -> {
                    _updateState.value = "Failed to delete account. Please try again."
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun clearUpdateError() { _updateState.value = null }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ProfileViewModel(
                    AppServiceLocator.userRepository,
                    AppServiceLocator.authRepository
                ) as T
            }
        }
    }
}

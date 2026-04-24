package com.yourname.womensafety.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yourname.womensafety.data.AppServiceLocator
import com.yourname.womensafety.data.local.TokenManager
import com.yourname.womensafety.data.repository.AuthRepository
import com.yourname.womensafety.data.repository.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SplashDestination(
    val route: String? = null  // null = still loading
)

class SplashViewModel(
    private val tokenManager: TokenManager,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _destination = MutableStateFlow(SplashDestination())
    val destination: StateFlow<SplashDestination> = _destination

    fun resolveStartDestination() {
        viewModelScope.launch {
            val onboardingDone = tokenManager.isOnboardingComplete().first()
            val permissionsGranted = tokenManager.arePermissionsGranted().first()
            val loggedIn = tokenManager.isLoggedIn().first()

            // Navigate immediately based on local DataStore state — do NOT block on
            // a network call during splash. Render cold-start can take 10-30 seconds
            // which would leave the user stuck on the splash screen.
            val route = when {
                !onboardingDone     -> "onboarding"
                !permissionsGranted -> "permissions"
                loggedIn            -> "dashboard"
                else                -> "login"
            }
            _destination.value = SplashDestination(route)

            // Silently validate the token in the background AFTER navigating.
            // If the token is truly expired/invalid, AuthInterceptor will catch the
            // 401 on the first real API call and trigger SessionManager.onSessionExpired()
            // which forces the user back to login gracefully.
            if (loggedIn) {
                try {
                    val result = authRepository.validateToken()
                    if (result is NetworkResult.Error &&
                        (result.code == "UNAUTHORIZED" || result.code == "TOKEN_INVALID")) {
                        tokenManager.clearTokens()
                        com.yourname.womensafety.data.SessionManager.onSessionExpired()
                    }
                } catch (_: Exception) {
                    // Network error during background validation — keep user logged in.
                    // The AuthInterceptor will handle any real auth failures on subsequent calls.
                }
            }
        }
    }


    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SplashViewModel(
                    AppServiceLocator.tokenManager,
                    AppServiceLocator.authRepository
                ) as T
            }
        }
    }
}

package com.yourname.womensafety

import android.app.Application
import com.yourname.womensafety.data.AppServiceLocator
import com.google.firebase.FirebaseApp
import com.yourname.womensafety.data.network.ApiConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class AsfalisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        AppServiceLocator.init(this)

        // Fire-and-forget health ping to wake the Render free-tier server before
        // the user finishes the splash animation (~1.6s). This reduces cold-start
        // lag for all subsequent API calls (login, dashboard, etc.)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val healthUrl = ApiConstants.BASE_URL_PRODUCTION
                    .removeSuffix("api/")
                    .plus("health")
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(healthUrl).get().build()
                client.newCall(request).execute().close()
            } catch (_: Exception) {
                // Silent — ping is best-effort. Any failure is acceptable.
            }
        }
    }
}


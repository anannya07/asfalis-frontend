package com.yourname.womensafety.data.network

object ApiConstants {
    // Emulator → 10.0.2.2 (maps to host machine localhost)
    // Physical device → your machine's local IP (e.g., 192.168.1.100)
    // Production → your deployed server URL
    const val BASE_URL_LOCAL_EMULATOR = "http://10.0.2.2:5000/api/"
    const val BASE_URL_LOCAL_DEVICE   = "http://192.168.31.214:5000/api/"
    const val BASE_URL_PRODUCTION     = "https://asfalis-backend.onrender.com/api/"

    // Toggle this for dev vs prod
    const val BASE_URL = BASE_URL_PRODUCTION

    // WebSocket
    const val WS_URL_LOCAL_EMULATOR = "http://10.0.2.2:5000"
    const val WS_URL_LOCAL_DEVICE   = "http://192.168.31.214:5000"
    const val WS_URL_PRODUCTION     = "https://asfalis-backend.onrender.com"
    const val WS_URL = WS_URL_PRODUCTION
}

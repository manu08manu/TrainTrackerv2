package com.traintracker

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class TrainTrackerApp : Application() {

    companion object {
        lateinit var httpClient: OkHttpClient
            private set
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(360, TimeUnit.SECONDS)
            .build()
        appScope.launch {
            launch(Dispatchers.IO) { StationData.init(this@TrainTrackerApp) }
        }
    }
}

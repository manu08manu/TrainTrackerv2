package com.traintracker

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TrainTrackerApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            // Station list and CORPUS load in parallel on background thread
            launch { StationData.init(this@TrainTrackerApp) }
            launch { CorpusData.init(this@TrainTrackerApp) }
        }
    }
}

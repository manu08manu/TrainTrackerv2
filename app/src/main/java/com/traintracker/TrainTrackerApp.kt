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
            val corpusJob = launch(Dispatchers.IO) { CorpusData.init(this@TrainTrackerApp) }
            launch(Dispatchers.IO) { StationData.init(this@TrainTrackerApp) }
            corpusJob.join()

            // If a server URL is configured, the server handles CIF — skip the local 60MB download.
            if (Constants.SERVER_BASE_URL.isNotBlank()) {
                CifRepository.skipToReady()
            } else {
                launch(Dispatchers.IO) { CifRepository.init(this@TrainTrackerApp) }
                CifUpdateWorker.schedule(this@TrainTrackerApp)
            }
        }
    }
}
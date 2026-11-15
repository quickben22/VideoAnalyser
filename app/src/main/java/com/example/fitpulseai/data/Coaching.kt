package com.example.fitpulseai.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

// Old persisted jobs can be instantiated after an app update, but must never generate coaching.
class CoachingWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = Result.success()
}

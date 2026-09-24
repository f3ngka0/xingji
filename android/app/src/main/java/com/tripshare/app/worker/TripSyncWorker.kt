package com.tripshare.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.Data
import com.tripshare.app.TripShareApplication
import kotlinx.coroutines.CancellationException

class TripSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val tripId = inputData.getString(KEY_TRIP_ID) ?: return Result.failure()
        val repository = (applicationContext as TripShareApplication).trips
        return try {
            repository.syncEnd(tripId)
            repository.uploadPending(tripId)
            if (repository.pendingCount(tripId) > 0) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val KEY_TRIP_ID = "trip_id"

        fun enqueue(context: Context, tripId: String) {
            val request = OneTimeWorkRequestBuilder<TripSyncWorker>()
                .setInputData(Data.Builder().putString(KEY_TRIP_ID, tripId).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("trip-sync-$tripId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

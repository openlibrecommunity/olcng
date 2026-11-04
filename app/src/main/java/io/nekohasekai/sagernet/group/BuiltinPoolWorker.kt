package io.nekohasekai.sagernet.group

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

class BuiltinPoolWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            // retries bypass the minimum interval of the original attempt
            BuiltinPool.refresh(inputData.getBoolean("force", false) || runAttemptCount > 0)
            if (BuiltinPool.state.value.lastError != null && runAttemptCount < 4) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        }
    }
}

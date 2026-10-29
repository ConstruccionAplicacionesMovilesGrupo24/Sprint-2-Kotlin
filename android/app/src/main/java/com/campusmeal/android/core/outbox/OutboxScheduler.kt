package com.campusmeal.android.core.outbox

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.campusmeal.android.app.CampusMealApplication
import com.campusmeal.android.core.session.AccountWork
import java.util.concurrent.TimeUnit

/** Starts the background delivery of one account's outbox. Cancellation belongs to [com.campusmeal.android.core.session.AccountWorkCanceller]. */
fun interface OutboxScheduler {
    fun schedule(accountId: String)
}

/**
 * One chain of unique work per account, named [AccountWork.outboxSyncName] so the session code can
 * cancel it on logout or account change. APPEND_OR_REPLACE queues a new run after the current one:
 * two drains of the same account never overlap, and a trigger that arrives while a drain is finishing
 * is not lost. Android decides when the work runs; the only constraint is a connected network.
 */
class WorkManagerOutboxScheduler(context: Context) : OutboxScheduler {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun schedule(accountId: String) {
        val request = OneTimeWorkRequestBuilder<OutboxSyncWorker>()
            .setInputData(workDataOf(OutboxSyncWorker.KEY_ACCOUNT_ID to accountId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(AccountWork.tag(accountId))
            .build()
        workManager.enqueueUniqueWork(AccountWork.outboxSyncName(accountId), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val BACKOFF_SECONDS = 30L
    }
}

/** Delivers one account's pending events. Rows left pending are re-scheduled the next time that account is activated. */
class OutboxSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID) ?: return Result.failure()
        val container = (applicationContext as CampusMealApplication).container
        return when (container.outboxRepository.drain(accountId)) {
            DrainResult.DONE -> Result.success()
            DrainResult.RETRY -> if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val KEY_ACCOUNT_ID = "accountId"
        private const val MAX_ATTEMPTS = 8
    }
}

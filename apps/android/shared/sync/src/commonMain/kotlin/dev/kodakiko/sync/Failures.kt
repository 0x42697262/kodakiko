package dev.kodakiko.sync

import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.OutboxKind
import kotlin.math.min
import kotlin.math.pow

sealed interface PushFailure {
    data object Network : PushFailure

    data class Http(val status: Int, val code: ErrorCode, val retryAfterSeconds: Double?) : PushFailure
}

sealed interface FailureDecision {
    data class Retry(val at: Double) : FailureDecision

    data object Conflict : FailureDecision

    data object Drop : FailureDecision

    data object Dead : FailureDecision

    data object SignedOut : FailureDecision
}

private val DROP_ON_NOT_FOUND: Set<OutboxKind> = setOf(
    OutboxKind.UPDATE_ENTRY_META,
    OutboxKind.TRASH_ENTRY,
    OutboxKind.RESTORE_ENTRY,
    OutboxKind.REMOVE_MEDIA,
    OutboxKind.DELETE_ENTRY,
)

fun nextAttemptAt(attempts: Int, now: Double, random: () -> Double): Double =
    now + random() * min(BACKOFF_CAP_MS, BACKOFF_BASE_MS * BACKOFF_FACTOR.pow(attempts))

private fun retryOrDead(attempts: Int, at: Double): FailureDecision =
    if (attempts >= MAX_ATTEMPTS) FailureDecision.Dead else FailureDecision.Retry(at)

private fun classifyHttp(kind: OutboxKind, failure: PushFailure.Http, attempts: Int, now: Double, random: () -> Double): FailureDecision {
    if (failure.status == UNAUTHORIZED_STATUS) return FailureDecision.SignedOut
    if (failure.code == ErrorCode.REVISION_CONFLICT) return FailureDecision.Conflict
    if (failure.status == NOT_FOUND_STATUS) {
        return if (kind in DROP_ON_NOT_FOUND) FailureDecision.Drop else FailureDecision.Dead
    }
    if (failure.status == TOO_MANY_REQUESTS_STATUS) {
        val retryAfterSeconds = failure.retryAfterSeconds
        val at = if (retryAfterSeconds == null) nextAttemptAt(attempts, now, random) else now + retryAfterSeconds * MS_PER_SECOND
        return FailureDecision.Retry(at)
    }
    if (failure.status >= SERVER_ERROR_STATUS_MIN || failure.code == ErrorCode.STORAGE_UNAVAILABLE) {
        return retryOrDead(attempts, nextAttemptAt(attempts, now, random))
    }
    return FailureDecision.Dead
}

fun classifyPushFailure(kind: OutboxKind, failure: PushFailure, attempts: Int, now: Double, random: () -> Double): FailureDecision =
    when (failure) {
        PushFailure.Network -> retryOrDead(attempts, nextAttemptAt(attempts, now, random))
        is PushFailure.Http -> classifyHttp(kind, failure, attempts, now, random)
    }

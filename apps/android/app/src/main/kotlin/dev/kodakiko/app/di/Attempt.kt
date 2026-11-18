package dev.kodakiko.app.di

import kotlinx.coroutines.CancellationException

internal suspend fun <T> attempt(block: suspend () -> T): T? =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        null
    }

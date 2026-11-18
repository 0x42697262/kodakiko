package dev.kodakiko.e2e

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class TestFetcher : Interceptor {
    private val lock = Any()
    private val seen = CopyOnWriteArrayList<String>()
    private var dropMethod: String? = null
    private var dropRemaining = 0
    private var dropped = 0

    @Volatile
    private var offline = false

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        seen += request.method + E2eConstants.REQUEST_SEPARATOR + request.url
        if (offline) throw IOException(E2eConstants.OFFLINE_MESSAGE)
        val response = chain.proceed(request)
        val drop = synchronized(lock) {
            if (dropMethod == request.method && dropRemaining > 0) {
                dropRemaining -= 1
                if (dropRemaining == 0) dropMethod = null
                dropped += 1
                true
            } else {
                false
            }
        }
        if (!drop) return response
        response.use { it.body.bytes() }
        throw IOException(E2eConstants.DROPPED_RESPONSE_MESSAGE)
    }

    fun requests(): List<String> = seen.toList()

    fun clearRequests() {
        seen.clear()
    }

    fun dropNextResponse(method: String, count: Int = 1) {
        synchronized(lock) {
            dropMethod = method
            dropRemaining = count
        }
    }

    fun droppedCount(): Int = synchronized(lock) { dropped }

    fun setOffline(value: Boolean) {
        offline = value
    }
}

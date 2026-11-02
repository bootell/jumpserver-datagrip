package dev.jumpserverbridge.datagrip.core

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse as JdkResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

const val MAX_RESPONSE_BYTES = 1024 * 1024
data class HttpResponse(val status: Int, val body: ByteArray)

fun interface HttpTransport {
    fun execute(method: String, uri: URI, headers: Map<String, String>, body: ByteArray?): HttpResponse
}

class JdkHttpTransport : HttpTransport {
    override fun execute(method: String, uri: URI, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
            .method(method, body?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody())
        headers.forEach { (key, value) -> request.header(key, value) }
        val future = client.sendAsync(request.build(), JdkResponse.BodyHandler { LimitedSubscriber() })
        try {
            val response = future.get(20, TimeUnit.SECONDS)
            return HttpResponse(response.statusCode(), response.body())
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw ApiFailure(FailureKind.TIMEOUT)
        } catch (e: InterruptedException) {
            future.cancel(true)
            throw e
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is ApiFailure) throw cause
            if (cause is java.net.http.HttpTimeoutException) throw ApiFailure(FailureKind.TIMEOUT)
            throw ApiFailure(FailureKind.NETWORK)
        }
    }

    private class LimitedSubscriber : JdkResponse.BodySubscriber<ByteArray> {
        private val result = CompletableFuture<ByteArray>()
        private val bytes = ByteArrayOutputStream()
        private lateinit var subscription: Flow.Subscription
        override fun getBody(): CompletionStage<ByteArray> = result
        override fun onSubscribe(value: Flow.Subscription) { subscription = value; value.request(1) }
        override fun onNext(items: List<ByteBuffer>) {
            for (buffer in items) {
                if (buffer.remaining().toLong() + bytes.size() > MAX_RESPONSE_BYTES) {
                    subscription.cancel()
                    result.completeExceptionally(ApiFailure(FailureKind.INVALID_RESPONSE))
                    return
                }
                val chunk = ByteArray(buffer.remaining())
                buffer.get(chunk)
                bytes.write(chunk)
            }
            subscription.request(1)
        }
        override fun onError(error: Throwable) { result.completeExceptionally(error) }
        override fun onComplete() { result.complete(bytes.toByteArray()) }
    }

    companion object {
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build()
    }
}

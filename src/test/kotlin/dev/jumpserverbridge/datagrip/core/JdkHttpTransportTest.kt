package dev.jumpserverbridge.datagrip.core

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class JdkHttpTransportTest {
    @Test fun realTransportDoesNotFollowRedirects() {
        val targetCalls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/target")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/target") { exchange -> targetCalls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.start()
        try {
            val response = JdkHttpTransport().execute("GET", URI("http://127.0.0.1:${server.address.port}/redirect"), emptyMap(), null)
            assertEquals(302, response.status)
            assertEquals(0, targetCalls.get())
        } finally { server.stop(0) }
    }

    @Test fun realTransportBoundsResponseWhileStreaming() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/large") { exchange ->
            try {
                val body = ByteArray(MAX_RESPONSE_BYTES + 1)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body)
            } finally { exchange.close() }
        }
        server.start()
        try {
            val error = assertThrows(ApiFailure::class.java) { JdkHttpTransport().execute("GET", URI("http://127.0.0.1:${server.address.port}/large"), emptyMap(), null) }
            assertEquals(FailureKind.INVALID_RESPONSE, error.kind)
        } finally { server.stop(0) }
    }
}

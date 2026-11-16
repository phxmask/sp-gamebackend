package com.example.game_backend.client

import arrow.resilience.ktor.client.HttpCircuitBreaker
import com.example.game_backend.client.dto.ConfigDto
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.seconds

@Component
class ConfigServiceClient {
    private val engine = ConfigServiceMock().engine

    private val client =
        HttpClient(engine) {
            expectSuccess = true
            install(HttpCircuitBreaker) {
                circuitBreaker(resetTimeout = 5.seconds, maxFailures = 5)
            }
            install(HttpRequestRetry) {
                maxRetries = 1
                delayMillis { 0L }
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 250L
                requestTimeoutMillis = 500L
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        coerceInputValues = true
                        ignoreUnknownKeys = true
                    },
                )
            }
            install(DefaultRequest) {
                url("http://localhost:8083/")
            }
        }.also { it.clearHistoryAfterEachCall(engine) }

    @Cacheable("config", sync = true)
    suspend fun getConfig(): ConfigDto =
        client
            .get("getConfig")
            .body()
}

private class ConfigServiceMock {
    val engine =
        MockEngine { request ->
            delay(ServiceMockBehavior.latencyMillis())
            if (ServiceMockBehavior.shouldFail()) {
                return@MockEngine respondError(HttpStatusCode.ServiceUnavailable)
            }
            when (request.url.encodedPath) {
                "/getConfig" -> {
                    respond(
                        content = CONFIG_JSON,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

                else -> {
                    error("Unhandled ${request.url}")
                }
            }
        }

    companion object {
        // n intendo pls don't sue me!!
        private val CONFIG_JSON =
            """
            {
              "levels": [
                { "id": 1, "name": "Rainbow Road", "maxDailyAttempts": 5 },
                { "id": 2, "name": "Donut Plains", "maxDailyAttempts": 3 },
                { "id": 3, "name": "Ghost Valley", "maxDailyAttempts": 2 }
              ]
            }
            """.trimIndent()
    }
}

package com.example.game_backend.client

import arrow.resilience.ktor.client.HttpCircuitBreaker
import com.example.game_backend.client.dto.ConfigDto
import com.github.benmanes.caffeine.cache.Caffeine
import com.sksamuel.aedile.core.asLoadingCache
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
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import kotlin.time.toKotlinDuration

@Component
class ConfigServiceClient(
    @Value("\${game.clients.config.base-url}") baseUrl: String,
    @Value("\${game.clients.config.connect-timeout}") connectTimeout: Duration,
    @Value("\${game.clients.config.request-timeout}") requestTimeout: Duration,
    @Value("\${game.clients.config.max-retries}") maxRetries: Int,
    @Value("\${game.clients.config.circuit-breaker.max-failures}") circuitBreakerMaxFailures: Int,
    @Value("\${game.clients.config.circuit-breaker.reset-timeout}") circuitBreakerResetTimeout: Duration,
    @Value("\${game.clients.config.refresh-interval}") refreshInterval: Duration,
) {
    private val engine = ConfigServiceMock().engine

    private val client =
        HttpClient(engine) {
            expectSuccess = true
            install(HttpCircuitBreaker) {
                circuitBreaker(
                    resetTimeout = circuitBreakerResetTimeout.toKotlinDuration(),
                    maxFailures = circuitBreakerMaxFailures,
                )
            }
            install(HttpRequestRetry) {
                this.maxRetries = maxRetries
                exponentialDelay(baseDelayMs = 50L, maxDelayMs = 200L, randomizationMs = 50L)
            }
            install(HttpTimeout) {
                connectTimeoutMillis = connectTimeout.toMillis()
                requestTimeoutMillis = requestTimeout.toMillis()
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
                url(baseUrl)
            }
        }.also { it.clearHistoryAfterEachCall(engine) }

    private val cachedConfig =
        Caffeine
            .newBuilder()
            .refreshAfterWrite(refreshInterval)
            .asLoadingCache<Unit, ConfigDto> { fetchConfig() }

    suspend fun getConfig(): ConfigDto = cachedConfig.get(Unit)

    suspend fun fetchConfig(): ConfigDto =
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

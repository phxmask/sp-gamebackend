package com.example.game_backend.client

import arrow.resilience.ktor.client.HttpCircuitBreaker
import com.example.game_backend.client.dto.DisplayNameDto
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
import io.ktor.http.appendPathSegments
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import kotlin.random.Random
import kotlin.time.toKotlinDuration

@Component
class PlayerProfileServiceClient(
    @Value("\${game.clients.player-profile.base-url}") baseUrl: String,
    @Value("\${game.clients.player-profile.connect-timeout}") connectTimeout: Duration,
    @Value("\${game.clients.player-profile.request-timeout}") requestTimeout: Duration,
    @Value("\${game.clients.player-profile.max-retries}") maxRetries: Int,
    @Value("\${game.clients.player-profile.circuit-breaker.max-failures}") circuitBreakerMaxFailures: Int,
    @Value("\${game.clients.player-profile.circuit-breaker.reset-timeout}") circuitBreakerResetTimeout: Duration,
    @Value("\${game.cache.display-names.refresh-after-write}") cacheRefreshAfterWrite: Duration,
    @Value("\${game.cache.display-names.expire-after-write}") cacheExpireAfterWrite: Duration,
    @Value("\${game.cache.display-names.maximum-size}") cacheMaximumSize: Long,
) {
    private val engine = PlayerProfileServiceMock().engine

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

    private val displayNamesById =
        Caffeine
            .newBuilder()
            .refreshAfterWrite(cacheRefreshAfterWrite)
            .expireAfterWrite(cacheExpireAfterWrite)
            .maximumSize(cacheMaximumSize)
            .asLoadingCache<Long, String> { fetchDisplayName(it) }

    suspend fun getDisplayName(id: Long): String = displayNamesById.get(id)

    private suspend fun fetchDisplayName(id: Long): String =
        client
            .get("getPlayerDisplayName") {
                url {
                    appendPathSegments(id.toString())
                }
            }.body<DisplayNameDto>()
            .name
}

private class PlayerProfileServiceMock {
    val engine =
        MockEngine { request ->
            delay(ServiceMockBehavior.latencyMillis())
            if (ServiceMockBehavior.shouldFail()) {
                return@MockEngine respondError(HttpStatusCode.ServiceUnavailable)
            }
            val segments =
                request.url.encodedPath
                    .trim('/')
                    .split('/')
            when (segments.first()) {
                "getPlayerDisplayName" -> {
                    respond(
                        content = Json.encodeToString(DisplayNameDto(displayName())),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

                else -> {
                    error("Unhandled ${request.url}")
                }
            }
        }

    private fun displayName(): String = "${MOCK_NAME_POOL_ONE.random()}${MOCK_NAME_POOL_TWO.random()}${Random.nextInt(1000)}"

    companion object {
        private val MOCK_NAME_POOL_ONE =
            listOf("Iron", "Grim", "Void", "Blood", "Zealous", "Eternal", "Fallen", "Sacred", "Ashen", "Relentless")
        private val MOCK_NAME_POOL_TWO =
            listOf(
                "Inquisitor",
                "Templar",
                "Commissar",
                "Crusader",
                "Warden",
                "Reaper",
                "Dreadnought",
                "Confessor",
                "Sentinel",
                "Exorcist",
            )
    }
}

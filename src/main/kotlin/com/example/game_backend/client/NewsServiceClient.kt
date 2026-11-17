package com.example.game_backend.client

import arrow.resilience.ktor.client.HttpCircuitBreaker
import com.example.game_backend.client.dto.NewsDto
import com.example.game_backend.client.dto.NewsTypeDto
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
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import java.time.Duration
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.toKotlinDuration

@Component
class NewsServiceClient(
    @Value("\${game.clients.news.base-url}") baseUrl: String,
    @Value("\${game.clients.news.connect-timeout}") connectTimeout: Duration,
    @Value("\${game.clients.news.request-timeout}") requestTimeout: Duration,
    @Value("\${game.clients.news.max-retries}") maxRetries: Int,
    @Value("\${game.clients.news.circuit-breaker.max-failures}") circuitBreakerMaxFailures: Int,
    @Value("\${game.clients.news.circuit-breaker.reset-timeout}") circuitBreakerResetTimeout: Duration,
) {
    private val engine = NewsServiceMock().engine

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
                delayMillis { 0L }
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

    @Cacheable("news", sync = true)
    suspend fun getNews(count: Int): List<NewsDto> {
        val news =
            client
                .get("getNews") {
                    url {
                        appendPathSegments(count.toString())
                    }
                }.body<List<NewsDto>>()

        val unknownCount = news.count { it.type == NewsTypeDto.UNKNOWN }
        if (unknownCount > 0) {
            log.warn("Received {} news item(s) with an unknown type", unknownCount)
        }
        return news
    }

    companion object {
        private val log = LoggerFactory.getLogger(NewsServiceClient::class.java)
    }
}

private class NewsServiceMock {
    private val news =
        MESSAGES.mapIndexed { index, (type, message) ->
            NewsDto(timestamp = Clock.System.now() - (index * 6).hours, type = type, message = message)
        }

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
                "getNews" -> {
                    respond(
                        content = Json.encodeToString(news.take(segments[1].toInt())),
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
        private val MESSAGES =
            listOf(
                NewsTypeDto.EVENT to "Double score weekend has started",
                NewsTypeDto.GIFT to "You received a gift of 50 coins from your guild member",
                NewsTypeDto.SYSTEM to "Scheduled maintenance tonight at 02:00 UTC",
                NewsTypeDto.EVENT to "New season leaderboard is live",
                NewsTypeDto.SYSTEM to "Patch 1.3 released",
                NewsTypeDto.EVENT to "Special PVP Season starts tomorrow!",
            )
    }
}

package com.example.game_backend.client

import com.example.game_backend.client.dto.NewsDto
import com.example.game_backend.client.dto.NewsTypeDto
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.DefaultRequest
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
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

@Component
class NewsServiceClient {
    private val client =
        HttpClient(NewsServiceMock().engine) {
            expectSuccess = true
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
        }

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
            delay(latencyMillis())
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

    private fun latencyMillis(): Long =
        if (Random.nextInt(100) < SLOW_RESPONSE_CHANCE_PERCENTAGE) {
            SLOW_RESPONSE_TIME_MILLIS
        } else {
            Random.nextLong(MIN_RESPONSE_TIME_MILLIS, MAX_RESPONSE_TIME_MILLIS + 1)
        }

    companion object {
        const val SLOW_RESPONSE_CHANCE_PERCENTAGE = 5
        const val MIN_RESPONSE_TIME_MILLIS = 30L
        const val MAX_RESPONSE_TIME_MILLIS = 50L
        const val SLOW_RESPONSE_TIME_MILLIS = 150L

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

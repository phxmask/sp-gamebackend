package com.example.game_backend.client

import arrow.resilience.ktor.client.HttpCircuitBreaker
import com.example.game_backend.client.dto.LeaderboardDto
import com.example.game_backend.client.dto.LeaderboardPositionDto
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.seconds

@Component
class LeaderboardServiceClient {
    private val engine = LeaderboardServiceMock().engine

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

    suspend fun getLeaderboardPosition(score: Int): Long =
        client
            .get("getLeaderboardPosition") {
                url {
                    appendPathSegments(score.toString())
                }
            }.body<LeaderboardPositionDto>()
            .position

    suspend fun getLeaderboard(
        startPosition: Long,
        endPosition: Long,
    ): List<LeaderboardDto> =
        client
            .get("getLeaderboard") {
                url {
                    appendPathSegments(startPosition.toString(), endPosition.toString())
                }
            }.body<List<LeaderboardDto>>()
            .sortedByDescending {
                it.score
            } // As per the assigment, its not clear if this list is sorted already, this could be dropped based on the API specification

    @Cacheable("topLeaderboard", sync = true)
    suspend fun getTopLeaderboard(count: Long): List<LeaderboardDto> = getLeaderboard(1, count)

    // This endpoint is not defined in the assignment
    suspend fun updatePlayerScore(
        playerId: Long,
        score: Int,
    ) {
        client.post("updateLeaderboard") {
            url { appendPathSegments(playerId.toString(), score.toString()) }
        }
    }
}

private class LeaderboardServiceMock {
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
            when {
                request.method == HttpMethod.Get && segments.first() == "getLeaderboardPosition" -> {
                    respondJson(Json.encodeToString(LeaderboardPositionDto(positionFor(segments[1].toInt()))))
                }

                request.method == HttpMethod.Get && segments.first() == "getLeaderboard" -> {
                    val start = segments[1].toLong().coerceAtLeast(1)
                    val end = segments[2].toLong().coerceIn(start - 1, start + MAX_PAGE_SIZE - 1)
                    respondJson(Json.encodeToString((start..end).map(::entryAt)))
                }

                request.method == HttpMethod.Post && segments.first() == "updateLeaderboard" -> {
                    respondOk()
                }

                else -> {
                    error("Unhandled ${request.method.value} ${request.url}")
                }
            }
        }

    // Higher score -> better position, always below the fake top spots
    private fun positionFor(score: Int): Long = TOP_SPOTS + 1 + SCORE_SCALE / (score.coerceAtLeast(0) + 1)

    private fun entryAt(position: Long): LeaderboardDto =
        if (position <= TOP_SPOTS) {
            LeaderboardDto(playerId = position, score = TOP_SCORE - position.toInt())
        } else {
            LeaderboardDto(
                playerId = FAKE_PLAYER_ID_OFFSET + position,
                score = (SCORE_SCALE / (position - TOP_SPOTS)).toInt(),
            )
        }

    private fun MockRequestHandleScope.respondJson(content: String) =
        respond(
            content = content,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

    companion object {
        const val TOP_SPOTS = 10L
        const val TOP_SCORE = 1_000_000_000
        const val SCORE_SCALE = 1_000_000L
        const val MAX_PAGE_SIZE = 100L
        const val FAKE_PLAYER_ID_OFFSET = 1_000_000_000L
    }
}

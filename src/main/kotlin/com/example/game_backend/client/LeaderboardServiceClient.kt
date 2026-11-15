package com.example.game_backend.client

import com.example.game_backend.client.dto.LeaderboardDto
import com.example.game_backend.client.dto.LeaderboardPositionDto
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.DefaultRequest
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

@Component
class LeaderboardServiceClient {
    private val client =
        HttpClient(LeaderboardServiceMock().engine) {
            expectSuccess = true
            install(HttpTimeout) {
                connectTimeoutMillis = 250L
                requestTimeoutMillis = 500L
            }

            install(ContentNegotiation) {
                json()
            }
            install(DefaultRequest) {
                url("http://localhost:8083/")
            }
        }

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
    private val scores =
        ConcurrentHashMap<Long, Int>().apply {
            val seeded = Random(42)
            (1L..1000L).forEach { playerId -> put(playerId, seeded.nextInt(0, 5000)) }
        }

    val engine =
        MockEngine { request ->
            delay(latencyMillis())
            val segments =
                request.url.encodedPath
                    .trim('/')
                    .split('/')
            when {
                request.method == HttpMethod.Get && segments.first() == "getLeaderboardPosition" -> {
                    val score = segments[1].toInt()
                    respondJson(Json.encodeToString(LeaderboardPositionDto(scores.values.count { it > score } + 1L)))
                }

                request.method == HttpMethod.Get && segments.first() == "getLeaderboard" -> {
                    val start = segments[1].toInt()
                    val end = segments[2].toInt()
                    val ranked =
                        scores.entries
                            .map { LeaderboardDto(playerId = it.key, score = it.value) }
                            .sortedWith(compareByDescending<LeaderboardDto> { it.score }.thenBy { it.playerId })
                    respondJson(Json.encodeToString(ranked.drop(start - 1).take((end - start + 1).coerceAtLeast(0))))
                }

                request.method == HttpMethod.Post && segments.first() == "updateLeaderboard" -> {
                    scores[segments[1].toLong()] = segments[2].toInt()
                    respondOk()
                }

                else -> {
                    error("Unhandled ${request.method.value} ${request.url}")
                }
            }
        }

    private fun MockRequestHandleScope.respondJson(content: String) =
        respond(
            content = content,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

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
    }
}

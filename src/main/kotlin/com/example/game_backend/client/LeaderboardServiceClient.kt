package com.example.game_backend.client

import com.example.game_backend.client.dto.LeaderboardDto
import com.example.game_backend.client.dto.LeaderboardPositionDto
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.appendPathSegments
import io.ktor.serialization.kotlinx.json.json
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component

@Component
class LeaderboardServiceClient {
    private val client =
        HttpClient(CIO) {
            expectSuccess = true
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

    @Cacheable("topLeaderboard")
    suspend fun getTopLeaderboard(count: Long): List<LeaderboardDto> = getLeaderboard(1, count)

    // This endpoint is not defined in the assignment
    suspend fun updatePlayerScore(playerId: Long, score: Int) {
        client.post("updateLeaderboard") {
            url { appendPathSegments(playerId.toString(), score.toString()) }
        }
    }
}

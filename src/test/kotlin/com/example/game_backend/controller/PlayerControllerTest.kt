package com.example.game_backend.controller

import com.example.game_backend.controller.dto.LeaderboardEntryDto
import com.example.game_backend.controller.dto.LevelDto
import com.example.game_backend.controller.dto.NewsResponseDto
import com.example.game_backend.controller.dto.NewsTypeDto
import com.example.game_backend.controller.dto.PlayerStateDto
import com.example.game_backend.service.PlayerStateService
import com.ninjasquad.springmockk.MockkBean
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Named
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.time.Instant

@WebMvcTest(PlayerController::class)
class PlayerControllerTest {
    @Autowired
    private lateinit var mvc: MockMvcTester

    @MockkBean
    private lateinit var playerStateService: PlayerStateService

    @Test
    fun `returns the player state as JSON`() {
        coEvery { playerStateService.getPlayerState(PLAYER_ID) } returns PLAYER_STATE

        assertThat(mvc.get().uri("/getPlayer/{id}", PLAYER_ID))
            .hasStatusOk()
            .bodyJson()
            .isStrictlyEqualTo(
                """
                {
                  "levels": [{ "id": 1, "name": "Rainbow Road", "dailyStartCount": 2 }],
                  "leaderboard": [
                    { "playerId": 101, "position": 1, "score": 1950, "name": "Top1" },
                    { "playerId": 102, "position": 2, "score": 1900, "name": "Top2" },
                    { "playerId": 103, "position": 3, "score": 1850, "name": "Top3" },
                    { "playerId": 104, "position": 4, "score": 1800, "name": "Top4" },
                    { "playerId": 105, "position": 5, "score": 1750, "name": "Top5" },
                    { "playerId": 106, "position": 6, "score": 1700, "name": "Top6" },
                    { "playerId": 107, "position": 7, "score": 1650, "name": "Top7" },
                    { "playerId": 108, "position": 8, "score": 1600, "name": "Top8" },
                    { "playerId": 109, "position": 9, "score": 1550, "name": "Top9" },
                    { "playerId": 110, "position": 10, "score": 1500, "name": "Top10" },
                    { "playerId": 201, "position": 48, "score": 320, "name": "Player1" },
                    { "playerId": 202, "position": 49, "score": 310, "name": "Player2" },
                    { "playerId": 42, "position": 50, "score": 300, "name": "Player3" },
                    { "playerId": 203, "position": 51, "score": 300, "name": "Player4" },
                    { "playerId": 204, "position": 52, "score": 290, "name": "Player5" }
                  ],
                  "news": [{ "timestamp": "2026-11-16T08:00:00Z", "message": "Double score weekend", "type": "EVENT" }],
                  "activity": "2026-11-16T10:00:00Z"
                }
                """.trimIndent(),
            )
        coVerify(exactly = 1) { playerStateService.getPlayerState(PLAYER_ID) }
    }

    @Test
    fun `rejects an id that isn't a number`() {
        assertThat(mvc.get().uri("/getPlayer/abc")).hasStatus(HttpStatus.BAD_REQUEST)
        coVerify(exactly = 0) { playerStateService.getPlayerState(any()) }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("downstreamFailures")
    fun `maps downstream failures to a problem response`(
        failure: Throwable,
        status: HttpStatus,
        detail: String,
    ) {
        coEvery { playerStateService.getPlayerState(PLAYER_ID) } throws failure

        assertThat(mvc.get().uri("/getPlayer/{id}", PLAYER_ID))
            .hasStatus(status)
            .bodyJson()
            .isLenientlyEqualTo("""{ "status": ${status.value()}, "detail": "$detail" }""")
    }

    companion object {
        private const val PLAYER_ID = 42L
        private const val UNAVAILABLE = "A downstream service is unavailable"
        private const val TIMED_OUT = "A downstream service timed out"

        private val TOP_ENTRIES =
            (1L..10L).map { position ->
                LeaderboardEntryDto(
                    playerId = 100 + position,
                    position = position,
                    score = 2_000 - position.toInt() * 50,
                    name = "Top$position",
                )
            }

        private val AROUND_PLAYER_ENTRIES =
            listOf(
                LeaderboardEntryDto(playerId = 201, position = 48, score = 320, name = "Player1"),
                LeaderboardEntryDto(playerId = 202, position = 49, score = 310, name = "Player2"),
                LeaderboardEntryDto(playerId = PLAYER_ID, position = 50, score = 300, name = "Player3"),
                LeaderboardEntryDto(playerId = 203, position = 51, score = 300, name = "Player4"),
                LeaderboardEntryDto(playerId = 204, position = 52, score = 290, name = "Player5"),
            )

        private val LEADERBOARD = TOP_ENTRIES + AROUND_PLAYER_ENTRIES

        private val PLAYER_STATE =
            PlayerStateDto(
                levels = listOf(LevelDto(id = 1, name = "Rainbow Road", dailyStartCount = 2)),
                leaderboard = LEADERBOARD,
                news =
                    listOf(
                        NewsResponseDto(
                            timestamp = Instant.parse("2026-11-16T08:00:00Z"),
                            message = "Double score weekend",
                            type = NewsTypeDto.EVENT,
                        ),
                    ),
                activity = Instant.parse("2026-11-16T10:00:00Z"),
            )

        @JvmStatic
        fun downstreamFailures() =
            listOf(
                arguments(
                    "error response",
                    ServerResponseException(mockk(relaxed = true), ""),
                    HttpStatus.SERVICE_UNAVAILABLE,
                    UNAVAILABLE,
                ),
                arguments("connection refused", ConnectException(), HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE),
                arguments(
                    "request timeout",
                    HttpRequestTimeoutException("http://localhost:8083/", 500),
                    HttpStatus.GATEWAY_TIMEOUT,
                    TIMED_OUT,
                ),
                arguments(
                    "connect timeout",
                    ConnectTimeoutException("connect timed out"),
                    HttpStatus.GATEWAY_TIMEOUT,
                    TIMED_OUT,
                ),
                arguments("socket timeout", SocketTimeoutException(), HttpStatus.GATEWAY_TIMEOUT, TIMED_OUT),
            )

        private fun arguments(
            name: String,
            failure: Throwable,
            status: HttpStatus,
            detail: String,
        ) = Arguments.of(Named.of(name, failure), status, detail)
    }
}

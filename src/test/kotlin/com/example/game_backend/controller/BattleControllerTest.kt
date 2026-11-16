package com.example.game_backend.controller

import arrow.core.left
import arrow.core.right
import com.example.game_backend.service.BattleService
import com.example.game_backend.service.CompleteBattleError
import com.example.game_backend.service.StartBattleError
import com.ninjasquad.springmockk.MockkBean
import io.mockk.coEvery
import io.mockk.coVerify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Named
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import kotlin.uuid.Uuid

@WebMvcTest(BattleController::class)
class BattleControllerTest {
    @Autowired
    private lateinit var mvc: MockMvcTester

    @MockkBean
    private lateinit var battleService: BattleService

    @Test
    fun `starts a battle and returns its id`() {
        coEvery { battleService.startBattle(PLAYER_ID, LEVEL_ID) } returns BATTLE_ID.right()

        assertThat(mvc.post().uri("/startBattle/{playerId}/levelId/{levelId}", PLAYER_ID, LEVEL_ID))
            .hasStatusOk()
            .hasBodyTextEqualTo(BATTLE_ID.toString())
        coVerify(exactly = 1) { battleService.startBattle(PLAYER_ID, LEVEL_ID) }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("startBattleErrors")
    fun `maps start battle errors to a problem response`(
        error: StartBattleError,
        status: HttpStatus,
        detail: String,
    ) {
        coEvery { battleService.startBattle(PLAYER_ID, LEVEL_ID) } returns error.left()

        assertThat(mvc.post().uri("/startBattle/{playerId}/levelId/{levelId}", PLAYER_ID, LEVEL_ID))
            .hasStatus(status)
            .bodyJson()
            .isLenientlyEqualTo("""{ "status": ${status.value()}, "detail": "$detail" }""")
    }

    @ParameterizedTest
    @ValueSource(strings = ["/startBattle/abc/levelId/1", "/startBattle/42/levelId/abc"])
    fun `rejects start battle ids that aren't numbers`(uri: String) {
        assertThat(mvc.post().uri(uri)).hasStatus(HttpStatus.BAD_REQUEST)
        coVerify(exactly = 0) { battleService.startBattle(any(), any()) }
    }

    @ParameterizedTest(name = "position {0}")
    @MethodSource("positions")
    fun `completes a battle and returns the new position`(
        position: Long?,
        json: String,
    ) {
        coEvery { battleService.completeBattle(PLAYER_ID, BATTLE_ID, 50) } returns position.right()

        assertThat(mvc.post().uri(COMPLETE_BATTLE_URI, PLAYER_ID, BATTLE_ID, 50))
            .hasStatusOk()
            .bodyJson()
            .isStrictlyEqualTo(json)
        coVerify(exactly = 1) { battleService.completeBattle(PLAYER_ID, BATTLE_ID, 50) }
    }

    @Test
    fun `returns not found for a battle that wasn't started`() {
        coEvery { battleService.completeBattle(PLAYER_ID, BATTLE_ID, 50) } returns
            CompleteBattleError.BattleNotStarted(BATTLE_ID).left()

        assertThat(mvc.post().uri(COMPLETE_BATTLE_URI, PLAYER_ID, BATTLE_ID, 50))
            .hasStatus(HttpStatus.NOT_FOUND)
            .bodyJson()
            .isLenientlyEqualTo(
                """{ "status": 404, "detail": "Battle $BATTLE_ID was not started or is already completed" }""",
            )
    }

    @Test
    fun `rejects a battle id that isn't a UUID`() {
        assertThat(mvc.post().uri(COMPLETE_BATTLE_URI, PLAYER_ID, "not-a-uuid", 50))
            .hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson()
            .isLenientlyEqualTo("""{ "status": 400, "detail": "Battle id not-a-uuid is not a valid UUID" }""")
        coVerify(exactly = 0) { battleService.completeBattle(any(), any(), any()) }
    }

    @ParameterizedTest(name = "score {0}")
    @CsvSource(
        "-101, The minimum score is -100",
        "101, The maximum score is 100",
    )
    fun `rejects a score over the limits`(
        score: Int,
        detail: String,
    ) {
        assertThat(mvc.post().uri(COMPLETE_BATTLE_URI, PLAYER_ID, BATTLE_ID, score))
            .hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson()
            .isLenientlyEqualTo("""{ "status": 400, "detail": "$detail" }""")
        coVerify(exactly = 0) { battleService.completeBattle(any(), any(), any()) }
    }

    @ParameterizedTest(name = "score {0}")
    @ValueSource(ints = [-100, -99, 0, 99, 100])
    fun `accepts a score within the limits`(score: Int) {
        coEvery { battleService.completeBattle(PLAYER_ID, BATTLE_ID, score) } returns 1L.right()

        assertThat(mvc.post().uri(COMPLETE_BATTLE_URI, PLAYER_ID, BATTLE_ID, score)).hasStatusOk()
        coVerify(exactly = 1) { battleService.completeBattle(PLAYER_ID, BATTLE_ID, score) }
    }

    companion object {
        private const val PLAYER_ID = 42L
        private const val LEVEL_ID = 1L
        private const val COMPLETE_BATTLE_URI = "/completeBattle/{playerId}/battleId/{battleId}/score/{score}"
        private val BATTLE_ID = Uuid.parse("3f2b8c1e-5d4a-4e7b-9c6f-1a2b3c4d5e6f")

        @JvmStatic
        fun startBattleErrors() =
            listOf(
                Arguments.of(
                    Named.of("unknown level", StartBattleError.UnknownLevel(LEVEL_ID)),
                    HttpStatus.NOT_FOUND,
                    "Level $LEVEL_ID does not exist",
                ),
                Arguments.of(
                    Named.of("daily limit reached", StartBattleError.MaxAttemptsReached(LEVEL_ID, maxDailyAttempts = 3)),
                    HttpStatus.CONFLICT,
                    "Daily limit of 3 battles reached for level $LEVEL_ID",
                ),
            )

        @JvmStatic
        fun positions() =
            listOf(
                Arguments.of(17L, """{ "position": 17 }"""),
                Arguments.of(null, """{ "position": null }"""),
            )
    }
}

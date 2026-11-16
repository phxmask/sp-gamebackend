package com.example.game_backend.service

import arrow.core.left
import arrow.core.right
import com.example.game_backend.client.ConfigServiceClient
import com.example.game_backend.client.LeaderboardServiceClient
import com.example.game_backend.client.dto.ConfigDto
import com.example.game_backend.client.dto.LevelsDto
import com.example.game_backend.domain.ActiveBattle
import com.example.game_backend.domain.PlayerState
import com.example.game_backend.domain.toUtcDate
import com.example.game_backend.persistence.PlayerStateLMDBStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Named
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class BattleServiceTest {
    private val playerStateStore = mockk<PlayerStateLMDBStore>()
    private val leaderboardClient = mockk<LeaderboardServiceClient>()
    private val configClient = mockk<ConfigServiceClient>()

    private val service = BattleService(playerStateStore, leaderboardClient, configClient)

    private val today = Clock.System.now().toUtcDate()
    private val storedStates = mutableMapOf<LocalDate, PlayerState>()
    private val update = slot<(PlayerState?) -> PlayerState?>()

    @BeforeEach
    fun setUp() {
        coEvery { playerStateStore.compute(PLAYER_ID, any(), capture(update)) } answers {
            val day = secondArg<LocalDate>()
            update.captured(storedStates[day])?.also { storedStates[day] = it } ?: storedStates[day]
        }
        coEvery { playerStateStore.get(PLAYER_ID, any()) } answers { storedStates[secondArg()] }
        coEvery { configClient.getConfig() } returns ConfigDto(levels = LEVELS)
        coEvery { leaderboardClient.updatePlayerScore(any(), any()) } just runs
        coEvery { leaderboardClient.getLeaderboardPosition(any()) } returns POSITION
    }

    @Test
    fun `rejects a level that isn't configured`() =
        runTest {
            val result = service.startBattle(PLAYER_ID, levelId = 99)

            assertEquals(StartBattleError.UnknownLevel(99).left(), result)
            coVerify(exactly = 0) { playerStateStore.compute(any(), any(), any()) }
        }

    @Test
    fun `starts the first battle of the day for a player`() =
        runTest {
            val before = Clock.System.now()

            val battleId = service.startBattle(PLAYER_ID, LEVEL_ID).getOrNull()!!

            val state = storedStates.getValue(today)
            assertEquals(ActiveBattle(battleId, LEVEL_ID), state.activeBattle)
            assertEquals(mapOf(LEVEL_ID to 1), state.dailyBattleStats)
            assertTrue(state.lastActiveTime in before..Clock.System.now())
        }

    @Test
    fun `abandons the active battle when starting another level`() =
        runTest {
            storedStates[today] =
                PlayerState(
                    activeBattle = ActiveBattle(BATTLE_ID, OTHER_LEVEL_ID),
                    dailyBattleStats = mapOf(LEVEL_ID to MAX_DAILY_ATTEMPTS - 1, OTHER_LEVEL_ID to 4),
                    dailyScore = 120,
                )

            val battleId = service.startBattle(PLAYER_ID, LEVEL_ID).getOrNull()!!

            val state = storedStates.getValue(today)
            assertEquals(ActiveBattle(battleId, LEVEL_ID), state.activeBattle)
            assertEquals(mapOf(LEVEL_ID to MAX_DAILY_ATTEMPTS, OTHER_LEVEL_ID to 4), state.dailyBattleStats)
            assertEquals(120, state.dailyScore)
        }

    @Test
    fun `returns the active battle when the start is retried with the same idempotency key`() =
        runTest {
            val existing =
                PlayerState(
                    activeBattle = ActiveBattle(BATTLE_ID, LEVEL_ID, IDEMPOTENCY_KEY),
                    dailyBattleStats = mapOf(LEVEL_ID to MAX_DAILY_ATTEMPTS),
                    lastActiveTime = Instant.fromEpochSeconds(0),
                )
            storedStates[today] = existing
            val before = Clock.System.now()

            val result = service.startBattle(PLAYER_ID, LEVEL_ID, IDEMPOTENCY_KEY)

            assertEquals(BATTLE_ID.right(), result)
            assertOnlyActivityUpdated(existing, before)
        }

    @ParameterizedTest(name = "active key {0}, requested key {1}, requested level {2}")
    @CsvSource(
        nullValues = ["null"],
        value = [
            "null, null, 1",
            "null, key-1, 1",
            "key-1, null, 1",
            "key-1, key-2, 1",
            "key-1, key-1, 2",
        ],
    )
    fun `counts a new attempt when the start isn't a retry of the active battle`(
        activeKey: String?,
        requestedKey: String?,
        levelId: Long,
    ) = runTest {
        storedStates[today] =
            PlayerState(
                activeBattle = ActiveBattle(BATTLE_ID, LEVEL_ID, activeKey),
                dailyBattleStats = mapOf(LEVEL_ID to 1),
            )

        val battleId = service.startBattle(PLAYER_ID, levelId, requestedKey).getOrNull()!!

        val state = storedStates.getValue(today)
        assertNotEquals(BATTLE_ID, battleId)
        assertEquals(ActiveBattle(battleId, levelId, requestedKey), state.activeBattle)
        assertEquals(if (levelId == LEVEL_ID) 2 else 1, state.dailyBattleStats.getValue(levelId))
    }


    @Test
    fun `rejects a battle once the level's daily limit is reached`() =
        runTest {
            val existing =
                PlayerState(
                    dailyBattleStats = mapOf(LEVEL_ID to MAX_DAILY_ATTEMPTS),
                    lastActiveTime = Instant.fromEpochSeconds(0),
                )
            storedStates[today] = existing
            val before = Clock.System.now()

            val result = service.startBattle(PLAYER_ID, LEVEL_ID)

            assertEquals(StartBattleError.MaxAttemptsReached(LEVEL_ID, MAX_DAILY_ATTEMPTS).left(), result)
            assertOnlyActivityUpdated(existing, before)
        }

    @Test
    fun `keeps the active battle when another level's daily limit is reached`() =
        runTest {
            val existing =
                PlayerState(
                    activeBattle = ActiveBattle(BATTLE_ID, OTHER_LEVEL_ID),
                    dailyBattleStats = mapOf(LEVEL_ID to MAX_DAILY_ATTEMPTS, OTHER_LEVEL_ID to 1),
                    lastActiveTime = Instant.fromEpochSeconds(0),
                )
            storedStates[today] = existing
            val before = Clock.System.now()

            val result = service.startBattle(PLAYER_ID, LEVEL_ID)

            assertEquals(StartBattleError.MaxAttemptsReached(LEVEL_ID, MAX_DAILY_ATTEMPTS).left(), result)
            assertOnlyActivityUpdated(existing, before)
        }

    @ParameterizedTest(name = "{0} + {1} = {2}")
    @MethodSource("scores")
    fun `completes today's battle and reports the new daily score`(
        dailyScore: Int,
        score: Int,
        expectedDailyScore: Int,
    ) = runTest {
        val battleId = Uuid.random()
        storedStates[today] =
            PlayerState(
                activeBattle = ActiveBattle(battleId, LEVEL_ID),
                dailyScore = dailyScore,
                lastActiveTime = Instant.fromEpochSeconds(0),
            )
        val before = Clock.System.now()

        val result = service.completeBattle(PLAYER_ID, battleId, score)

        val state = storedStates.getValue(today)
        assertEquals(POSITION.right(), result)
        assertNull(state.activeBattle)
        assertEquals(expectedDailyScore, state.dailyScore)
        assertTrue(state.lastActiveTime in before..Clock.System.now())
        coVerify(exactly = 1) { leaderboardClient.updatePlayerScore(PLAYER_ID, expectedDailyScore) }
        coVerify(exactly = 1) { leaderboardClient.getLeaderboardPosition(expectedDailyScore) }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("todayScores")
    fun `completes yesterday's battle without scoring it on today's leaderboard`(todayState: PlayerState?) =
        runTest {
            val battleId = Uuid.random()
            storedStates[today.minusDays(1)] =
                PlayerState(activeBattle = ActiveBattle(battleId, LEVEL_ID), dailyScore = 200)
            todayState?.let { storedStates[today] = it }

            val result = service.completeBattle(PLAYER_ID, battleId, score = 50)

            assertEquals(POSITION.right(), result)
            assertNull(storedStates.getValue(today.minusDays(1)).activeBattle)
            assertEquals(250, storedStates.getValue(today.minusDays(1)).dailyScore)
            coVerify(exactly = 0) { leaderboardClient.updatePlayerScore(any(), any()) }
            coVerify(exactly = 1) { leaderboardClient.getLeaderboardPosition(todayState?.dailyScore ?: 0) }
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unknownBattles")
    fun `rejects a battle that wasn't started today or yesterday`(states: Map<Long, PlayerState>) =
        runTest {
            states.forEach { (daysAgo, state) -> storedStates[today.minusDays(daysAgo)] = state }

            val result = service.completeBattle(PLAYER_ID, BATTLE_ID, score = 50)

            assertEquals(CompleteBattleError.BattleNotStarted(BATTLE_ID).left(), result)
            assertEquals(states.mapKeys { (daysAgo, _) -> today.minusDays(daysAgo) }, storedStates)
            coVerify(exactly = 0) { leaderboardClient.updatePlayerScore(any(), any()) }
            coVerify(exactly = 0) { leaderboardClient.getLeaderboardPosition(any()) }
        }

    @Test
    fun `still completes the battle when the leaderboard score update fails`() =
        runTest {
            storedStates[today] = PlayerState(activeBattle = ActiveBattle(BATTLE_ID, LEVEL_ID), dailyScore = 10)
            coEvery { leaderboardClient.updatePlayerScore(any(), any()) } throws RuntimeException("leaderboard down")

            val result = service.completeBattle(PLAYER_ID, BATTLE_ID, score = 50)

            assertEquals(POSITION.right(), result)
            assertEquals(60, storedStates.getValue(today).dailyScore)
        }

    @Test
    fun `returns no position when the leaderboard position can't be fetched`() =
        runTest {
            storedStates[today] = PlayerState(activeBattle = ActiveBattle(BATTLE_ID, LEVEL_ID), dailyScore = 10)
            coEvery { leaderboardClient.getLeaderboardPosition(any()) } throws RuntimeException("leaderboard down")

            val result = service.completeBattle(PLAYER_ID, BATTLE_ID, score = 50)

            assertNull(result.getOrNull())
            assertTrue(result.isRight())
            assertEquals(60, storedStates.getValue(today).dailyScore)
        }

    private fun assertOnlyActivityUpdated(
        existing: PlayerState,
        before: Instant,
    ) {
        val state = storedStates.getValue(today)
        assertEquals(existing.copy(lastActiveTime = state.lastActiveTime), state)
        assertTrue(state.lastActiveTime in before..Clock.System.now())
    }

    companion object {
        private const val PLAYER_ID = 42L
        private const val LEVEL_ID = 1L
        private const val OTHER_LEVEL_ID = 2L
        private const val IDEMPOTENCY_KEY = "key-1"
        private const val MAX_DAILY_ATTEMPTS = 3
        private const val POSITION = 17L
        private val BATTLE_ID = Uuid.parse("3f2b8c1e-5d4a-4e7b-9c6f-1a2b3c4d5e6f")

        private val LEVELS =
            listOf(
                LevelsDto(id = LEVEL_ID, name = "Rainbow Road", maxDailyAttempts = MAX_DAILY_ATTEMPTS),
                LevelsDto(id = OTHER_LEVEL_ID, name = "Donut Plains", maxDailyAttempts = 5),
            )

        @JvmStatic
        fun scores() =
            listOf(
                Arguments.of(30, 50, 80),
                Arguments.of(30, -20, 10),
                Arguments.of(30, -100, 0),
            )

        @JvmStatic
        fun todayScores() =
            listOf(
                Named.of("no battles today", null),
                Named.of("battles today", PlayerState(dailyScore = 70, lastActiveTime = Instant.fromEpochSeconds(0))),
            )

        @JvmStatic
        fun unknownBattles() =
            listOf(
                Named.of("no state", emptyMap()),
                Named.of(
                    "already completed",
                    mapOf(0L to PlayerState(dailyScore = 20), 1L to PlayerState(dailyScore = 40))
                ),
                Named.of(
                    "another battle is active",
                    mapOf(0L to PlayerState(activeBattle = ActiveBattle(Uuid.random(), LEVEL_ID))),
                ),
                Named.of(
                    "started two days ago",
                    mapOf(2L to PlayerState(activeBattle = ActiveBattle(BATTLE_ID, LEVEL_ID))),
                ),
            )
    }
}

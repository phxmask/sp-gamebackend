package com.example.game_backend.service

import com.example.game_backend.client.ConfigServiceClient
import com.example.game_backend.client.LeaderboardServiceClient
import com.example.game_backend.client.NewsServiceClient
import com.example.game_backend.client.PlayerProfileServiceClient
import com.example.game_backend.client.dto.ConfigDto
import com.example.game_backend.client.dto.LeaderboardDto
import com.example.game_backend.client.dto.LevelsDto
import com.example.game_backend.client.dto.NewsDto
import com.example.game_backend.client.dto.NewsTypeDto
import com.example.game_backend.controller.dto.LeaderboardEntryDto
import com.example.game_backend.controller.dto.LevelDto
import com.example.game_backend.controller.dto.NewsResponseDto
import com.example.game_backend.domain.ActiveBattle
import com.example.game_backend.domain.PlayerState
import com.example.game_backend.persistence.PlayerStateLMDBStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Named
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import com.example.game_backend.controller.dto.NewsTypeDto as NewsTypeResponseDto

class PlayerStateServiceTest {
    private val playerProfileClient = mockk<PlayerProfileServiceClient>()
    private val configClient = mockk<ConfigServiceClient>()
    private val newsClient = mockk<NewsServiceClient>()
    private val leaderboardClient = mockk<LeaderboardServiceClient>()
    private val playerStateStore = mockk<PlayerStateLMDBStore>()

    private val service =
        PlayerStateService(playerProfileClient, configClient, newsClient, leaderboardClient, playerStateStore)

    private val update = slot<(PlayerState?) -> PlayerState?>()
    private var storedState: PlayerState? = null

    @BeforeEach
    fun setUp() {
        givenStoredState(null)
        coEvery { configClient.getConfig() } returns ConfigDto(levels = LEVELS)
        coEvery { newsClient.getNews(any()) } returns emptyList()
        coEvery { leaderboardClient.getTopLeaderboard(any()) } returns emptyList()
        coEvery { leaderboardClient.getLeaderboardPosition(any()) } returns 1
        coEvery { leaderboardClient.getLeaderboard(any(), any()) } returns emptyList()
        coEvery { playerProfileClient.getDisplayName(any()) } answers { "name-${firstArg<Long>()}" }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storedStates")
    fun `updates the last active time and keeps the rest of the state`(existing: PlayerState?) =
        runTest {
            givenStoredState(existing)
            val before = Clock.System.now()

            val result = service.getPlayerState(PLAYER_ID)

            assertEquals((existing ?: PlayerState()).copy(lastActiveTime = result.activity), storedState)
            assertTrue(result.activity in before..Clock.System.now())
        }

    @Test
    fun `returns every level from config with daily start count`() =
        runTest {
            givenStoredState(PlayerState(dailyBattleStats = mapOf(1L to 3)))

            val result = service.getPlayerState(PLAYER_ID)

            assertEquals(
                listOf(
                    LevelDto(id = 1, name = "Level 1", dailyStartCount = 3),
                    LevelDto(id = 2, name = "Level 2", dailyStartCount = 0),
                ),
                result.levels,
            )
        }

    @Test
    fun `returns the latest news`() =
        runTest {
            val timestamp = Instant.fromEpochSeconds(1_000)
            coEvery { newsClient.getNews(5) } returns
                listOf(NewsDto(timestamp = timestamp, type = NewsTypeDto.EVENT, message = "Event started"))

            val result = service.getPlayerState(PLAYER_ID)

            assertEquals(
                listOf(
                    NewsResponseDto(
                        timestamp = timestamp,
                        message = "Event started",
                        type = NewsTypeResponseDto.EVENT,
                    ),
                ),
                result.news,
            )
        }

    @Test
    fun `fallback to no news when the news service fails`() =
        runTest {
            coEvery { newsClient.getNews(any()) } throws RuntimeException("news down")

            val result = service.getPlayerState(PLAYER_ID)

            assertEquals(emptyList(), result.news)
            assertEquals(LEVELS.size, result.levels.size)
        }

    @Test
    fun `numbers the top entries from 1 and fetches their names`() =
        runTest {
            coEvery { leaderboardClient.getTopLeaderboard(10) } returns
                listOf(LeaderboardDto(playerId = 7, score = 900), LeaderboardDto(playerId = 8, score = 800))

            val result = service.getPlayerState(PLAYER_ID)

            assertEquals(
                listOf(
                    LeaderboardEntryDto(playerId = 7, position = 1, score = 900, name = "name-7"),
                    LeaderboardEntryDto(playerId = 8, position = 2, score = 800, name = "name-8"),
                ),
                result.leaderboard,
            )
        }

    @Test
    fun `skip position lookup for a player without a score`() =
        runTest {
            givenStoredState(PlayerState(dailyScore = 0))

            service.getPlayerState(PLAYER_ID)

            coVerify(exactly = 0) { leaderboardClient.getLeaderboardPosition(any()) }
            coVerify(exactly = 0) { leaderboardClient.getLeaderboard(any(), any()) }
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("windowsAroundPlayer")
    fun `returns the entries around the player`(case: WindowCase) =
        runTest {
            givenStoredState(PlayerState(dailyScore = case.score))
            coEvery { leaderboardClient.getLeaderboardPosition(case.score) } returns case.position
            coEvery { leaderboardClient.getLeaderboard(case.requested.first, case.requested.second) } returns
                case.returned.map { (playerId, score) -> LeaderboardDto(playerId, score) }

            val result = service.getPlayerState(PLAYER_ID)

            assertEquals(
                case.expected.map { (playerId, position, score) ->
                    LeaderboardEntryDto(
                        playerId = playerId,
                        position = position,
                        score = score,
                        name = "name-$playerId",
                    )
                },
                result.leaderboard,
            )
        }

    @Test
    fun `lists a player in both the top and around the player only once, at their top position`() =
        runTest {
            givenStoredState(PlayerState(dailyScore = 800))
            coEvery { leaderboardClient.getTopLeaderboard(10) } returns
                listOf(LeaderboardDto(playerId = 7, score = 900), LeaderboardDto(playerId = PLAYER_ID, score = 800))
            coEvery { leaderboardClient.getLeaderboardPosition(800) } returns 2
            coEvery { leaderboardClient.getLeaderboard(1, 4) } returns
                listOf(
                    LeaderboardDto(playerId = 7, score = 900),
                    LeaderboardDto(playerId = PLAYER_ID, score = 800),
                    LeaderboardDto(playerId = 9, score = 700),
                )

            val result = service.getPlayerState(PLAYER_ID)

            assertEquals(listOf(7L, PLAYER_ID, 9L), result.leaderboard.map { it.playerId })
            assertEquals(listOf(1L, 2L, 3L), result.leaderboard.map { it.position })
        }

    @Test
    fun `still returns the top entries when the entries around the player fail`() =
        runTest {
            givenStoredState(PlayerState(dailyScore = 100))
            coEvery { leaderboardClient.getTopLeaderboard(10) } returns
                listOf(
                    LeaderboardDto(
                        playerId = 7,
                        score = 900,
                    ),
                )
            coEvery { leaderboardClient.getLeaderboardPosition(any()) } throws RuntimeException("leaderboard down")

            val result = service.getPlayerState(PLAYER_ID)

            assertEquals(listOf(7L), result.leaderboard.map { it.playerId })
        }

    @Test
    fun `still returns the entries around the player when the top entries fail`() =
        runTest {
            givenStoredState(PlayerState(dailyScore = 100))
            coEvery { leaderboardClient.getTopLeaderboard(any()) } throws RuntimeException("leaderboard down")
            coEvery { leaderboardClient.getLeaderboardPosition(100) } returns 20
            coEvery { leaderboardClient.getLeaderboard(18, 22) } returns
                listOf(LeaderboardDto(playerId = PLAYER_ID, score = 100))

            val result = service.getPlayerState(PLAYER_ID)

            assertEquals(listOf(PLAYER_ID), result.leaderboard.map { it.playerId })
        }

    @Test
    fun `falls back to a placeholder name when the display name can't be fetched`() =
        runTest {
            coEvery { leaderboardClient.getTopLeaderboard(10) } returns
                listOf(
                    LeaderboardDto(
                        playerId = 7,
                        score = 900,
                    ),
                )
            coEvery { playerProfileClient.getDisplayName(7) } throws RuntimeException("profiles down")

            val result = service.getPlayerState(PLAYER_ID)

            assertEquals("<UNKNOWN>", result.leaderboard.single().name)
        }

    private fun givenStoredState(state: PlayerState?) {
        coEvery { playerStateStore.compute(PLAYER_ID, any(), capture(update)) } answers {
            update.captured(state)?.also { storedState = it }
        }
    }

    data class WindowCase(
        val description: String,
        val score: Int,
        val position: Long,
        val requested: Pair<Long, Long>,
        val returned: List<Pair<Long, Int>>,
        val expected: List<Triple<Long, Long, Int>>,
    ) {
        override fun toString() = description
    }

    companion object {
        private const val PLAYER_ID = 42L

        private val LEVELS =
            listOf(
                LevelsDto(id = 1, name = "Level 1", maxDailyAttempts = 3),
                LevelsDto(id = 2, name = "Level 2", maxDailyAttempts = 5),
            )

        @JvmStatic
        fun storedStates() =
            listOf(
                Named.of("new player", null),
                Named.of(
                    "existing player",
                    PlayerState(
                        activeBattle = ActiveBattle(Uuid.random(), levelId = 1),
                        dailyBattleStats = mapOf(1L to 2),
                        dailyScore = 50,
                        lastActiveTime = Instant.fromEpochSeconds(0),
                    ),
                ),
            )

        @JvmStatic
        fun windowsAroundPlayer() =
            listOf(
                WindowCase(
                    description = "includes the two players above and below the player",
                    score = 100,
                    position = 20,
                    requested = 18L to 22L,
                    returned = listOf(1L to 120, 2L to 110, PLAYER_ID to 100, 3L to 90, 4L to 80),
                    expected =
                        listOf(
                            Triple(1L, 18L, 120),
                            Triple(2L, 19L, 110),
                            Triple(PLAYER_ID, 20L, 100),
                            Triple(3L, 21L, 90),
                            Triple(4L, 22L, 80),
                        ),
                ),
                WindowCase(
                    description = "puts the player in their position when ties leave them out of the window",
                    score = 100,
                    position = 20,
                    requested = 18L to 22L,
                    returned = listOf(1L to 120, 2L to 110, 3L to 100, 4L to 100, 5L to 100),
                    expected =
                        listOf(
                            Triple(1L, 18L, 120),
                            Triple(2L, 19L, 110),
                            Triple(PLAYER_ID, 20L, 100),
                            Triple(4L, 21L, 100),
                            Triple(5L, 22L, 100),
                        ),
                ),
                WindowCase(
                    description = "adds the player when the window ends before their position",
                    score = 5,
                    position = 20,
                    requested = 18L to 22L,
                    returned = listOf(1L to 30, 2L to 10),
                    expected = listOf(Triple(1L, 18L, 30), Triple(2L, 19L, 10), Triple(PLAYER_ID, 20L, 5)),
                ),
                WindowCase(
                    description = "doesn't request positions above the first",
                    score = 100,
                    position = 2,
                    requested = 1L to 4L,
                    returned = listOf(1L to 200, PLAYER_ID to 100),
                    expected = listOf(Triple(1L, 1L, 200), Triple(PLAYER_ID, 2L, 100)),
                ),
            )
    }
}

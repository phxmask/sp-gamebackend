package com.example.game_backend.service

import com.example.game_backend.client.ConfigServiceClient
import com.example.game_backend.client.LeaderboardServiceClient
import com.example.game_backend.client.NewsServiceClient
import com.example.game_backend.client.PlayerProfileServiceClient
import com.example.game_backend.client.dto.LeaderboardDto
import com.example.game_backend.client.dto.toLeaderboardEntryDto
import com.example.game_backend.client.dto.toNewsResponse
import com.example.game_backend.controller.dto.LeaderboardEntryDto
import com.example.game_backend.controller.dto.LevelDto
import com.example.game_backend.controller.dto.NewsResponseDto
import com.example.game_backend.controller.dto.PlayerStateDto
import com.example.game_backend.domain.PlayerState
import com.example.game_backend.domain.PlayerStateStore
import com.example.game_backend.domain.toUtcDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Service
import kotlin.time.Clock

@Service
class PlayerStateService(
    private val playerProfileClient: PlayerProfileServiceClient,
    private val configClient: ConfigServiceClient,
    private val newsClient: NewsServiceClient,
    private val leaderboardClient: LeaderboardServiceClient,
    private val playerState: PlayerStateStore,
) {
    suspend fun getPlayerState(playerId: Long): PlayerStateDto =
        coroutineScope {
            val now = Clock.System.now()
            val state =
                playerState.players.compute(playerId to now.toUtcDate()) { _, v ->
                    (v ?: PlayerState()).copy(lastActiveTime = now)
                }!!

            val levels = async { getLevels(state) }
            val news = async { getNews() }
            val leaderboard = async { getLeaderboard(playerId, state.dailyScore) }

            PlayerStateDto(
                levels = levels.await(),
                news = news.await(),
                leaderboard = leaderboard.await(),
                activity = state.lastActiveTime,
            )
        }

    private suspend fun getLevels(state: PlayerState): List<LevelDto> =
        configClient.getConfig().levels.map {
            LevelDto(
                id = it.id,
                name = it.name,
                dailyStartCount = state.dailyBattleStats.getOrDefault(it.id, 0),
            )
        }

    private suspend fun getNews(): List<NewsResponseDto> = newsClient.getNews(5).map { it.toNewsResponse() }

    private suspend fun getLeaderboard(
        playerId: Long,
        dailyScore: Int,
    ): List<LeaderboardEntryDto> =
        coroutineScope {
            val topEntries = async { getTopEntries() }
            val aroundPlayerEntries = async { getAroundPlayerEntries(playerId, dailyScore) }

            (topEntries.await() + aroundPlayerEntries.await())
                .distinctBy { (entry, _) -> entry.playerId }
                .map { (entry, position) ->
                    async {
                        entry.toLeaderboardEntryDto(
                            position = position,
                            name = getDisplayNameOrUnknown(entry.playerId),
                        )
                    }
                }.awaitAll()
        }

    // TODO: Confirm what I'll mock for the index (1, 10)
    private suspend fun getTopEntries(): List<Pair<LeaderboardDto, Long>> =
        leaderboardClient.getTopLeaderboard(10).mapIndexed { index, entry -> entry to 1L + index }

    // TODO: Handle edge cases properly (ties, battles not started yet, etc)
    private suspend fun getAroundPlayerEntries(
        playerId: Long,
        dailyScore: Int,
    ): List<Pair<LeaderboardDto, Long>> {
        val playerPosition = leaderboardClient.getLeaderboardPosition(dailyScore)
        val start = maxOf(1L, playerPosition - 2)
        return leaderboardClient
            .getLeaderboard(start, playerPosition + 2)
            .mapIndexed { index, entry -> entry to start + index }
    }

    private suspend fun getDisplayNameOrUnknown(playerId: Long): String =
        try {
            playerProfileClient.getDisplayName(playerId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "<UNKNOWN>"
        }
}

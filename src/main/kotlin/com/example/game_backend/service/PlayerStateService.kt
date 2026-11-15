package com.example.game_backend.service

import arrow.core.Either
import arrow.core.getOrElse
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
import com.example.game_backend.domain.toUtcDate
import com.example.game_backend.persistence.PlayerStateLMDBStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.time.Clock

@Service
class PlayerStateService(
    private val playerProfileClient: PlayerProfileServiceClient,
    private val configClient: ConfigServiceClient,
    private val newsClient: NewsServiceClient,
    private val leaderboardClient: LeaderboardServiceClient,
    private val playerState: PlayerStateLMDBStore,
) {
    suspend fun getPlayerState(playerId: Long): PlayerStateDto =
        coroutineScope {
            val now = Clock.System.now()
            val state =
                playerState.compute(playerId, now.toUtcDate()) { v ->
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

    private suspend fun getNews(): List<NewsResponseDto> =
        Either
            .catch { newsClient.getNews(5).map { it.toNewsResponse() } }
            .onLeft { log.warn("Failed to fetch news, returning no news: {}", it.message) }
            .getOrElse { emptyList() }

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

    private suspend fun getTopEntries(): List<Pair<LeaderboardDto, Long>> =
        Either
            .catch { leaderboardClient.getTopLeaderboard(10).mapIndexed { index, entry -> entry to 1L + index } }
            .onLeft { log.warn("Failed to fetch top leaderboard, returning no entries: {}", it.message) }
            .getOrElse { emptyList() }

    private suspend fun getAroundPlayerEntries(
        playerId: Long,
        dailyScore: Int,
    ): List<Pair<LeaderboardDto, Long>> {
        // If the player hasn't scored yet, we don't try to return their position or the players around them
        // modelled after how tacticus shows only the top ranked in this case.
        if (dailyScore == 0) {
            return emptyList()
        }

        return Either
            .catch {
                val playerPosition = leaderboardClient.getLeaderboardPosition(dailyScore)
                val start = maxOf(1L, playerPosition - 2)
                leaderboardClient
                    .getLeaderboard(start, playerPosition + 2)
                    .mapIndexed { index, entry -> entry to start + index }
            }.onLeft {
                log.warn(
                    "Failed to fetch leaderboard around player {}, returning no entries: {}",
                    playerId,
                    it.message,
                )
            }.getOrElse { emptyList() }
    }

    private suspend fun getDisplayNameOrUnknown(playerId: Long): String =
        Either
            .catch { playerProfileClient.getDisplayName(playerId) }
            .onLeft { log.warn("Failed to fetch display name of player {}, using fallback: {}", playerId, it.message) }
            .getOrElse { "<UNKNOWN>" }

    companion object {
        private val log = LoggerFactory.getLogger(PlayerStateService::class.java)
    }
}

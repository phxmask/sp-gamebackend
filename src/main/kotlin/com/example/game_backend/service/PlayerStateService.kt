package com.example.game_backend.service

import com.example.game_backend.client.ConfigServiceClient
import com.example.game_backend.client.LeaderboardServiceClient
import com.example.game_backend.client.NewsServiceClient
import com.example.game_backend.client.PlayerProfileServiceClient
import com.example.game_backend.client.dto.toLeaderboardEntryDto
import com.example.game_backend.client.dto.toNewsResponse
import com.example.game_backend.controller.dto.LevelDto
import com.example.game_backend.controller.dto.PlayerStateDto
import com.example.game_backend.domain.PlayerState
import com.example.game_backend.domain.PlayerStateStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class PlayerStateService(
    private val playerProfileClient: PlayerProfileServiceClient,
    private val configClient: ConfigServiceClient,
    private val newsClient: NewsServiceClient,
    private val leaderboardClient: LeaderboardServiceClient,
    private val playerState: PlayerStateStore,

) {
    suspend fun getPlayerState(playerId: Long): PlayerStateDto =
    // TODO: Cache where possible

        // TODO: Introduce session instead of blindly trusting input?
        coroutineScope {
            val state =
                playerState.players.getOrDefault(
                    playerId to LocalDate.now(),
                    PlayerState()
                )

            val playerProfile = async { playerProfileClient.getDisplayName(playerId) }
            val news = async { newsClient.getNews(5) }
            val config = async { configClient.getConfig() }

            // TODO: Confirm what I'll mock for the index (0, 10)
            val topLeaderboard = async { leaderboardClient.getLeaderboard(1, 10) }

            val playerLeaderboardPosition = async { leaderboardClient.getLeaderboardPosition(state.dailyScore) }
            val aroundPlayerLeaderboard = async {
                val playerPosition = playerLeaderboardPosition.await()
                leaderboardClient.getLeaderboard(playerPosition - 2, playerPosition + 2)
            }

            val topEntries = topLeaderboard.await()
            val names =
                topEntries
                    .map { entry -> async { entry.playerId to playerProfileClient.getDisplayName(entry.playerId) } }
                    .awaitAll()
                    .toMap()

            PlayerStateDto(
                levels = config.await().levels.map {
                    LevelDto(
                        id = it.id,
                        name = it.name,
                        dailyStartCount = state.dailyBattleStats.getOrDefault(it.id, 0)
                    )
                },
                news = news.await().map { it.toNewsResponse() },
                leaderboard =
                    topEntries.mapIndexed { index, entry ->
                        entry.toLeaderboardEntryDto(position = 1L + index, name = names.getValue(entry.playerId))
                    },
                activity = TODO(),
            )

        }
}

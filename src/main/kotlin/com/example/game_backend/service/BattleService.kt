package com.example.game_backend.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.example.game_backend.client.ConfigServiceClient
import com.example.game_backend.client.LeaderboardServiceClient
import com.example.game_backend.domain.PlayerState
import com.example.game_backend.domain.PlayerStateStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Service
import java.time.LocalDate
import kotlin.uuid.Uuid

sealed interface StartBattleError {
    data class UnknownLevel(val levelId: Long) : StartBattleError
    data class MaxAttemptsReached(val levelId: Long, val maxDailyAttempts: Int) : StartBattleError
}

sealed interface CompleteBattleError {
    data class BattleNotStarted(val battleId: Long) : CompleteBattleError
}

@Service
class BattleService(
    val playerState: PlayerStateStore,
    val leaderboardClient: LeaderboardServiceClient,
    val configClient: ConfigServiceClient,
) {
    suspend fun startBattle(playerId: Long, levelId: Long): Either<StartBattleError, Uuid> {
        val level = configClient.getConfig().levels.find { levelId == it.id }
            ?: return StartBattleError.UnknownLevel(levelId).left()

        val state = playerState.players.getOrDefault(playerId to LocalDate.now(), PlayerState())
        val levelStartCount = state.dailyBattleStats.getOrDefault(levelId, 0)
        if (levelStartCount >= level.maxDailyAttempts) {
            return StartBattleError.MaxAttemptsReached(levelId, level.maxDailyAttempts).left()
        }

        return Uuid.random().right()
    }

    suspend fun completeBattle(playerId: Long, battleId: Long, score: Int): Either<CompleteBattleError, Long> {
        val state = playerState.players.getOrDefault(playerId to LocalDate.now(), PlayerState())
        if (!state.startedBattles.contains(battleId)) {
            return CompleteBattleError.BattleNotStarted(battleId).left()
        }

        return coroutineScope {
            val updateLeaderboard =
                async { leaderboardClient.getLeaderboardPosition(1) } // TODO: Different call, since update is not covered in specs
            val position = async { leaderboardClient.getLeaderboardPosition(score) }

            updateLeaderboard.await()
            position.await().right()
        }
    }
}

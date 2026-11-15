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
import kotlin.time.Clock
import kotlin.uuid.Uuid

sealed interface StartBattleError {
    data class UnknownLevel(
        val levelId: Long,
    ) : StartBattleError

    data class MaxAttemptsReached(
        val levelId: Long,
        val maxDailyAttempts: Int,
    ) : StartBattleError
}

sealed interface CompleteBattleError {
    data class BattleNotStarted(
        val battleId: Uuid,
    ) : CompleteBattleError
}

@Service
class BattleService(
    val playerState: PlayerStateStore,
    val leaderboardClient: LeaderboardServiceClient,
    val configClient: ConfigServiceClient,
) {
    suspend fun startBattle(
        playerId: Long,
        levelId: Long,
    ): Either<StartBattleError, Uuid> {
        val level =
            configClient.getConfig().levels.find { levelId == it.id }
                ?: return StartBattleError.UnknownLevel(levelId).left()
        val battleUuid = Uuid.random()
        val now = Clock.System.now()
        var limitReached = false

        playerState.players.compute(playerId to LocalDate.now()) { _, v ->
            val currentState = v ?: PlayerState()
            val levelStartCount = currentState.dailyBattleStats.getOrDefault(levelId, 0)
            if (levelStartCount >= level.maxDailyAttempts) {
                limitReached = true
                v
            } else {
                currentState.copy(
                    lastActiveTime = now,
                    startedBattles = currentState.startedBattles + battleUuid,
                    dailyBattleStats = currentState.dailyBattleStats + (levelId to levelStartCount + 1),
                )
            }
        }

        if (limitReached) {
            return StartBattleError.MaxAttemptsReached(levelId, level.maxDailyAttempts).left()
        }
        return battleUuid.right()
    }

    suspend fun completeBattle(
        playerId: Long,
        battleId: Uuid,
        score: Int,
    ): Either<CompleteBattleError, Long> {
        val now = Clock.System.now()
        var notStarted = false

        val updated =
            playerState.players.compute(playerId to LocalDate.now()) { _, v ->
                if (v == null || battleId !in v.startedBattles) {
                    notStarted = true
                    v
                } else {
                    v.copy(
                        startedBattles = v.startedBattles - battleId,
                        dailyScore = maxOf(0, v.dailyScore + score),
                        lastActiveTime = now,
                    )
                }
            }

        if (notStarted || updated == null) {
            return CompleteBattleError.BattleNotStarted(battleId).left()
        }

        return coroutineScope {
            val updateLeaderboard =
                async {
                    leaderboardClient.updatePlayerScore(
                        playerId,
                        updated.dailyScore
                    )
                }
            val position = async { leaderboardClient.getLeaderboardPosition(updated.dailyScore) }

            updateLeaderboard.await()
            position.await().right()
        }
    }
}

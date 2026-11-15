package com.example.game_backend.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.example.game_backend.client.ConfigServiceClient
import com.example.game_backend.client.LeaderboardServiceClient
import com.example.game_backend.domain.PlayerState
import com.example.game_backend.domain.PlayerStateStore
import com.example.game_backend.domain.toUtcDate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import kotlin.time.Clock
import kotlin.time.Instant
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

        playerState.players.compute(playerId to now.toUtcDate()) { _, v ->
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
        val today = now.toUtcDate()

        // Since the player state is stored per day (to simplify cleanup later) we need to validate
        // if the battle happened today or yesterday, mostly to handle battles happening around midnight UTC
        // Anything older than that is considered expired
        val completedToday = completeStartedBattle(playerId, today, battleId, score, now)
        if (completedToday != null) {
            // Since updating the player score is not defined in the assignment, I preferred not to assume that it returns
            // the new leaderboard position, so we need to update it first then request the new position
            // In case of failures we just log an error, since score is client side in this setup (bad!!) and the player will
            // just report it again next time
            Either
                .catch { leaderboardClient.updatePlayerScore(playerId, completedToday.dailyScore) }
                .onLeft { log.error("Failed to update leaderboard score for player {}: {}", playerId, it.message) }

            return leaderboardClient.getLeaderboardPosition(completedToday.dailyScore).right()
        }

        completeStartedBattle(playerId, today.minusDays(1), battleId, score, now)
            ?: return CompleteBattleError.BattleNotStarted(battleId).left()

        // We always get today's score for the leaderboard position, as battles from a previous day are not scored 
        val todayScore = playerState.players[playerId to today]?.dailyScore ?: 0
        return leaderboardClient.getLeaderboardPosition(todayScore).right()
    }

    private fun completeStartedBattle(
        playerId: Long,
        day: LocalDate,
        battleId: Uuid,
        score: Int,
        now: Instant,
    ): PlayerState? {
        var completed: PlayerState? = null
        playerState.players.computeIfPresent(playerId to day) { _, v ->
            if (battleId in v.startedBattles) {
                v
                    .copy(
                        startedBattles = v.startedBattles - battleId,
                        dailyScore = maxOf(0, v.dailyScore + score),
                        lastActiveTime = now,
                    ).also { completed = it }
            } else {
                v
            }
        }
        return completed
    }

    companion object {
        private val log = LoggerFactory.getLogger(BattleService::class.java)
    }
}

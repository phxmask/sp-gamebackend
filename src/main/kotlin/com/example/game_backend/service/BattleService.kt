package com.example.game_backend.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.example.game_backend.client.ConfigServiceClient
import com.example.game_backend.client.LeaderboardServiceClient
import com.example.game_backend.domain.ActiveBattle
import com.example.game_backend.domain.CompletedBattle
import com.example.game_backend.domain.PlayerState
import com.example.game_backend.domain.toUtcDate
import com.example.game_backend.persistence.PlayerStateLMDBStore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    val playerState: PlayerStateLMDBStore,
    val leaderboardClient: LeaderboardServiceClient,
    val configClient: ConfigServiceClient,
) {
    suspend fun startBattle(
        playerId: Long,
        levelId: Long,
        idempotencyKey: String? = null,
    ): Either<StartBattleError, Uuid> {
        val level =
            configClient.getConfig().levels.find { levelId == it.id }
                ?: return StartBattleError.UnknownLevel(levelId).left()
        val now = Clock.System.now()
        val newBattle = ActiveBattle(Uuid.random(), levelId, idempotencyKey)

        val state =
            playerState.compute(playerId, now.toUtcDate()) { v ->
                val currentState = (v ?: PlayerState()).copy(lastActiveTime = now)
                val levelStartCount = currentState.dailyBattleStats.getOrDefault(levelId, 0)
                if (currentState.activeBattle?.isRetryOf(newBattle) == true || levelStartCount >= level.maxDailyAttempts) {
                    currentState
                } else {
                    currentState.copy(
                        activeBattle = newBattle,
                        dailyBattleStats = currentState.dailyBattleStats + (levelId to levelStartCount + 1),
                    )
                }
            }

        return state
            ?.activeBattle
            ?.takeIf { it == newBattle || it.isRetryOf(newBattle) }
            ?.id
            ?.right()
            ?: StartBattleError.MaxAttemptsReached(levelId, level.maxDailyAttempts).left()
    }

    private fun ActiveBattle.isRetryOf(other: ActiveBattle): Boolean =
        idempotencyKey != null && idempotencyKey == other.idempotencyKey && levelId == other.levelId

    suspend fun completeBattle(
        playerId: Long,
        battleId: Uuid,
        score: Int,
    ): Either<CompleteBattleError, Long?> {
        val now = Clock.System.now()
        val today = now.toUtcDate()

        // Since the player state is stored per day (to simplify cleanup later) we need to validate
        // if the battle happened today or yesterday, mostly to handle battles happening around midnight UTC
        // Anything older than that is considered expired
        val completedToday = completeStartedBattle(playerId, today, battleId, score, now)
        if (completedToday != null) {
            // Since updating the player score is not defined in the assignment, I preferred not to assume that it returns
            // the new leaderboard position, so we need both to update it and to request the new position
            // The position is looked up by score, so it doesn't depend on the update and both are done in parallel
            // In case of failures we just log an error, since score is client side in this setup (bad!!) and the player will
            // just report it again next time

            // TODO: Resend failed score updates, since otherwise players might lose score in their last battle of the day
            return coroutineScope {
                launch {
                    Either
                        .catch { leaderboardClient.updatePlayerScore(playerId, completedToday.dailyScore) }
                        .onLeft {
                            log.error(
                                "Failed to update leaderboard score for player {}: {}",
                                playerId,
                                it.message,
                            )
                        }
                }

                getLeaderboardPositionOrNull(playerId, completedToday.dailyScore).right()
            }
        }

        completeStartedBattle(playerId, today.minusDays(1), battleId, score, now)
            ?: return CompleteBattleError.BattleNotStarted(battleId).left()

        // We always get today's score for the leaderboard position, as battles from a previous day are not scored
        val todayScore = playerState.get(playerId, today)?.dailyScore ?: 0
        return getLeaderboardPositionOrNull(playerId, todayScore).right()
    }

    private suspend fun getLeaderboardPositionOrNull(
        playerId: Long,
        score: Int,
    ): Long? =
        Either
            .catch { leaderboardClient.getLeaderboardPosition(score) }
            .onLeft {
                log.warn(
                    "Failed to fetch leaderboard position for player {}, returning no position: {}",
                    playerId,
                    it.message,
                )
            }.getOrNull()

    private suspend fun completeStartedBattle(
        playerId: Long,
        day: LocalDate,
        battleId: Uuid,
        score: Int,
        now: Instant,
    ): PlayerState? {
        var completed: PlayerState? = null
        val completedBattle = CompletedBattle(battleId, score)
        playerState.compute(playerId, day) { v ->
            when {
                v == null -> {
                    null
                }

                v.activeBattle?.id == battleId -> {
                    v
                        .copy(
                            activeBattle = null,
                            lastCompletedBattle = completedBattle,
                            dailyScore = maxOf(0, v.dailyScore + score),
                            lastActiveTime = now,
                        ).also { completed = it }
                }

                // A retry of the last completed battle gets the same result, without scoring it again
                v.lastCompletedBattle == completedBattle -> {
                    v.copy(lastActiveTime = now).also { completed = it }
                }

                else -> {
                    null
                }
            }
        }
        return completed
    }

    companion object {
        private val log = LoggerFactory.getLogger(BattleService::class.java)
    }
}

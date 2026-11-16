package com.example.game_backend.domain

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

@Serializable
data class ActiveBattle(
    val id: Uuid,
    val levelId: Long,
    val idempotencyKey: String? = null,
)

@Serializable
data class PlayerState(
    val activeBattle: ActiveBattle? = null,
    val dailyBattleStats: Map<Long, Int> = emptyMap(), // (LevelId, count)
    val dailyScore: Int = 0,
    val lastActiveTime: Instant = Clock.System.now(),
)

fun Instant.toUtcDate(): LocalDate = LocalDate.ofInstant(toJavaInstant(), ZoneOffset.UTC)

package com.example.game_backend.domain

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

@Serializable
data class PlayerState(
    val startedBattles: Set<Uuid> = emptySet(),
    val dailyBattleStats: Map<Long, Int> = emptyMap(), // (LevelId, count)
    val dailyScore: Int = 0,
    val lastActiveTime: Instant = Clock.System.now(),
)

fun Instant.toUtcDate(): LocalDate = LocalDate.ofInstant(toJavaInstant(), ZoneOffset.UTC)

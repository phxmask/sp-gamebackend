package com.example.game_backend.domain

import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

data class PlayerState(
    val startedBattles: Set<Uuid> = emptySet(),
    val dailyBattleStats: Map<Long, Int> = emptyMap(), // (LevelId, count)
    val dailyScore: Int = 0,
    val lastActiveTime: Instant = Clock.System.now(),
)

fun Instant.toUtcDate(): LocalDate = LocalDate.ofInstant(toJavaInstant(), ZoneOffset.UTC)

// TODO: Temporary, move to redis later?
@Component
class PlayerStateStore {
    val players: ConcurrentHashMap<Pair<Long, LocalDate>, PlayerState> =
        ConcurrentHashMap<Pair<Long, LocalDate>, PlayerState>()
}

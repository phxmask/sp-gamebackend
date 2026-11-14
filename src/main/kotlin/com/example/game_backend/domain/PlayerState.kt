package com.example.game_backend.domain

import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

data class PlayerState(
    val startedBattles: Set<Long> = emptySet(),
    val dailyBattleStats: Map<Long, Int> = emptyMap(), // (LevelId, count)
    val dailyScore: Int = 0,
)


// TODO: Temporary, move to redis later?
@Component
class PlayerStateStore {
    val players: ConcurrentHashMap<Pair<Long, LocalDate>, PlayerState> =
        ConcurrentHashMap<Pair<Long, LocalDate>, PlayerState>()
}

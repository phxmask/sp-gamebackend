package com.example.game_backend.controller.dto

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class PlayerStateDto(
    val levels: List<LevelDto>,
    val leaderboard: List<LeaderboardEntryDto>,
    val news: List<NewsResponseDto>,
    val activity: Instant,
)

@Serializable
data class LevelDto(
    val id: Long,
    val name: String,
    val dailyStartCount: Int,
)

@Serializable
data class LeaderboardEntryDto(
    val playerId: Long,
    val position: Long,
    val score: Int,
    val name: String,
)

@Serializable
data class NewsResponseDto(
    val timestamp: Instant,
    val message: String,
    val type: NewsTypeDto,
)

@Serializable
enum class NewsTypeDto {
    EVENT,
    GIFT,
    SYSTEM,
}

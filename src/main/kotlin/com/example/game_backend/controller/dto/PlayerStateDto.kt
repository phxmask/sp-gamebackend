package com.example.game_backend.controller.dto

import kotlin.time.Instant

data class PlayerStateDto(
    val levels: List<LevelDto>,
    val leaderboard: List<LeaderboardEntryDto>,
    val news: List<NewsResponseDto>,
    val activity: Instant,
)

data class LevelDto(
    val id: Long,
    val name: String,
    val dailyStartCount: Int,
)

data class LeaderboardEntryDto(
    val playerId: Long,
    val position: Long,
    val score: Int,
    val name: String,
)

data class NewsResponseDto(
    val timestamp: Instant,
    val message: String,
    val type: NewsTypeDto,
)

enum class NewsTypeDto {
    EVENT,
    GIFT,
    SYSTEM,
}

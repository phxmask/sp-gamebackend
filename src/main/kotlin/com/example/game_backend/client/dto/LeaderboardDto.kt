package com.example.game_backend.client.dto

import com.example.game_backend.controller.dto.LeaderboardEntryDto
import kotlinx.serialization.Serializable

@Serializable
data class LeaderboardDto(
    val playerId: Long,
    val score: Int,
)

@Serializable
data class LeaderboardPositionDto(
    val position: Long,
)

fun LeaderboardDto.toLeaderboardEntryDto(position: Long, name: String): LeaderboardEntryDto =
    LeaderboardEntryDto(
        playerId = playerId,
        position = position,
        score = score,
        name = name,
    )

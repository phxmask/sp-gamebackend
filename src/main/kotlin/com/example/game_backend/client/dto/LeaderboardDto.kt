package com.example.game_backend.client.dto

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

package com.example.game_backend.client.dto

import kotlinx.serialization.Serializable

@Serializable
data class ConfigDto(
    val levels: List<LevelsDto>,
)

@Serializable
data class LevelsDto(
    val id: Long,
    val name: String,
    val maxDailyAttempts: Int,
)

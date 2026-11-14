package com.example.game_backend.client.dto

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class NewsDto(
    val timestamp: Instant,
    val type: NewsTypeDto,
    val message: String,
)

@Serializable
enum class NewsTypeDto {
    EVENT,
    GIFT,
    SYSTEM,
}

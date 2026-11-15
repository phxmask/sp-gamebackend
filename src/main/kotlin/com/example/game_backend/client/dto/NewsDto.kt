package com.example.game_backend.client.dto

import com.example.game_backend.controller.dto.NewsResponseDto
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import com.example.game_backend.controller.dto.NewsTypeDto as NewsTypeResponseDto

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

fun NewsDto.toNewsResponse(): NewsResponseDto =
    NewsResponseDto(
        timestamp = timestamp,
        message = message,
        type =
            when (type) {
                NewsTypeDto.EVENT -> NewsTypeResponseDto.EVENT
                NewsTypeDto.GIFT -> NewsTypeResponseDto.GIFT
                NewsTypeDto.SYSTEM -> NewsTypeResponseDto.SYSTEM
            },
    )

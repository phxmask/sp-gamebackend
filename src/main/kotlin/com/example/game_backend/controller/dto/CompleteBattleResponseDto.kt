package com.example.game_backend.controller.dto

import kotlinx.serialization.Serializable

@Serializable
data class CompleteBattleResponseDto(
    val position: Long?,
)

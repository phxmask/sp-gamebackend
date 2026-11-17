package com.example.game_backend.controller

import com.example.game_backend.controller.dto.PlayerStateDto
import com.example.game_backend.service.PlayerStateService
import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class PlayerController(
    private val playerStateService: PlayerStateService,
) {
    @GetMapping("/getPlayer/{id}")
    fun getPlayerState(
        @PathVariable id: Long,
    ): PlayerStateDto = runBlocking { playerStateService.getPlayerState(id) }
}

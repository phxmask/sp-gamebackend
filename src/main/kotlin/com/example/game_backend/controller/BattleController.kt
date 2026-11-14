package com.example.game_backend.controller

import com.example.game_backend.service.BattleService
import com.example.game_backend.service.CompleteBattleError
import com.example.game_backend.service.StartBattleError
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class BattleController(
    private val battleService: BattleService,
) {
    @PostMapping("/startBattle/{playerId}/levelId/{levelId}")
    suspend fun startBattle(
        @PathVariable playerId: Long,
        @PathVariable levelId: Long,
    ): ResponseEntity<*> {
        return battleService.startBattle(playerId, levelId).fold(
            ifLeft = { it.toResponse() },
            ifRight = { ResponseEntity.ok(it) }
        )
    }

    @PostMapping("/completeBattle/{playerId}/battleId/{battleId}/score/{score}")
    suspend fun completeBattle(
        @PathVariable playerId: Long,
        @PathVariable battleId: Long,
        @PathVariable score: Int,
    ): ResponseEntity<*> {
        return battleService.completeBattle(playerId, battleId, score).fold(
            ifLeft = { it.toResponse() },
            ifRight = { ResponseEntity.ok(it) }
        )
    }
}

private fun StartBattleError.toResponse(): ResponseEntity<ProblemDetail> =
    when (this) {
        is StartBattleError.UnknownLevel ->
            problem(HttpStatus.NOT_FOUND, "Level $levelId does not exist")

        is StartBattleError.MaxAttemptsReached ->
            problem(HttpStatus.CONFLICT, "Daily limit of $maxDailyAttempts battles reached for level $levelId")
    }

private fun CompleteBattleError.toResponse(): ResponseEntity<ProblemDetail> =
    when (this) {
        is CompleteBattleError.BattleNotStarted ->
            problem(HttpStatus.NOT_FOUND, "Battle $battleId was not started or is already completed")
    }

private fun problem(status: HttpStatus, detail: String): ResponseEntity<ProblemDetail> =
    ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail))

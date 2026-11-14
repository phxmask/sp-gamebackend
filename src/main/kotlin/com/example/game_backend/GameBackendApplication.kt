package com.example.game_backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class GameBackendApplication

fun main(args: Array<String>) {
    runApplication<GameBackendApplication>(*args)
}

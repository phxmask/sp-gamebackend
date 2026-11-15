package com.example.game_backend.client

import com.example.game_backend.client.dto.ConfigDto
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component

@Component
class ConfigServiceClient {
    private val client =
        HttpClient(CIO) {
            expectSuccess = true
            install(ContentNegotiation) {
                json()
            }
            install(DefaultRequest) {
                url("http://localhost:8083/")
            }
        }

    @Cacheable("config")
    suspend fun getConfig(): ConfigDto =
        client
            .get("getConfig")
            .body()
}

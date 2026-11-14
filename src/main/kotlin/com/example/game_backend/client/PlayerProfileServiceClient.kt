package com.example.game_backend.client

import com.example.game_backend.client.dto.DisplayNameDto
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.appendPathSegments
import io.ktor.serialization.kotlinx.json.json
import org.springframework.stereotype.Component

@Component
class PlayerProfileServiceClient {
    private val client =
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json()
            }
            install(DefaultRequest) {
                url("http://localhost:8083/")
            }
        }

    suspend fun getDisplayName(id: Long): DisplayNameDto =
        client
            .get("getPlayerDisplayName") {
                url {
                    appendPathSegments(id.toString())
                }
            }.body()
}

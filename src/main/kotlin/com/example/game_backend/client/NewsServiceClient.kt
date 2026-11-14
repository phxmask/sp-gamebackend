package com.example.game_backend.client

import com.example.game_backend.client.dto.NewsDto
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
class NewsServiceClient {
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

    suspend fun getNews(count: Int): List<NewsDto> =
        client
            .get("getNews") {
                url {
                    appendPathSegments(count.toString())
                }
            }.body()
}

package com.example.game_backend.client

import com.example.game_backend.client.dto.DisplayNameDto
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import kotlin.random.Random

@Component
class PlayerProfileServiceClient {
    private val client =
        HttpClient(PlayerProfileServiceMock().engine) {
            expectSuccess = true
            install(HttpTimeout) {
                connectTimeoutMillis = 250L
                requestTimeoutMillis = 500L
            }

            install(ContentNegotiation) {
                json()
            }
            install(DefaultRequest) {
                url("http://localhost:8083/")
            }
        }

    @Cacheable("displayNames")
    suspend fun getDisplayName(id: Long): String =
        client
            .get("getPlayerDisplayName") {
                url {
                    appendPathSegments(id.toString())
                }
            }.body<DisplayNameDto>()
            .name
}

private class PlayerProfileServiceMock {
    val engine =
        MockEngine { request ->
            delay(latencyMillis())
            val segments =
                request.url.encodedPath
                    .trim('/')
                    .split('/')
            when (segments.first()) {
                "getPlayerDisplayName" -> {
                    respond(
                        content = Json.encodeToString(DisplayNameDto(displayName())),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

                else -> {
                    error("Unhandled ${request.url}")
                }
            }
        }

    private fun displayName(): String = "${MOCK_NAME_POOL_ONE.random()}${MOCK_NAME_POOL_TWO.random()}${Random.nextInt(1000)}"

    private fun latencyMillis(): Long =
        if (Random.nextInt(100) < SLOW_RESPONSE_CHANCE_PERCENTAGE) {
            SLOW_RESPONSE_TIME_MILLIS
        } else {
            Random.nextLong(MIN_RESPONSE_TIME_MILLIS, MAX_RESPONSE_TIME_MILLIS + 1)
        }

    companion object {
        const val SLOW_RESPONSE_CHANCE_PERCENTAGE = 5
        const val MIN_RESPONSE_TIME_MILLIS = 30L
        const val MAX_RESPONSE_TIME_MILLIS = 50L
        const val SLOW_RESPONSE_TIME_MILLIS = 150L
        private val MOCK_NAME_POOL_ONE =
            listOf("Iron", "Grim", "Void", "Blood", "Zealous", "Eternal", "Fallen", "Sacred", "Ashen", "Relentless")
        private val MOCK_NAME_POOL_TWO =
            listOf(
                "Inquisitor",
                "Templar",
                "Commissar",
                "Crusader",
                "Warden",
                "Reaper",
                "Dreadnought",
                "Confessor",
                "Sentinel",
                "Exorcist",
            )
    }
}

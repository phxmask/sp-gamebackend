package com.example.game_backend.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import kotlin.random.Random

object ServiceMockBehavior {
    private const val MIN_RESPONSE_TIME_MILLIS = 30L
    private const val MAX_RESPONSE_TIME_MILLIS = 50L
    private const val SLOW_RESPONSE_CHANCE = 0.05
    private const val SLOW_RESPONSE_TIME_MILLIS = 150L
    private const val TIMEOUT_SPIKE_CHANCE = 0.0001
    private const val MIN_TIMEOUT_SPIKE_MILLIS = 600L
    private const val MAX_TIMEOUT_SPIKE_MILLIS = 1000L
    private const val FAILURE_CHANCE = 0.001

    fun latencyMillis(): Long {
        val roll = Random.nextDouble()
        return when {
            roll < TIMEOUT_SPIKE_CHANCE -> {
                Random.nextLong(MIN_TIMEOUT_SPIKE_MILLIS, MAX_TIMEOUT_SPIKE_MILLIS + 1)
            }

            roll < TIMEOUT_SPIKE_CHANCE + SLOW_RESPONSE_CHANCE -> {
                SLOW_RESPONSE_TIME_MILLIS
            }

            else -> {
                Random.nextLong(MIN_RESPONSE_TIME_MILLIS, MAX_RESPONSE_TIME_MILLIS + 1)
            }
        }
    }

    fun shouldFail(): Boolean = Random.nextDouble() < FAILURE_CHANCE
}

// This is pretty ugly BUT MockEngine stores every request and response internally in a list, so when running for long this would degrade performance eventually
// discussed in https://github.com/ktorio/ktor/issues/913
// As we never read the history, this extension drops it after each call and can be implemented via plugins
fun HttpClient.clearHistoryAfterEachCall(engine: MockEngine) {
    plugin(HttpSend).intercept { request ->
        execute(request).also {
            (engine.requestHistory as MutableList<*>).clear()
            (engine.responseHistory as MutableList<*>).clear()
        }
    }
}

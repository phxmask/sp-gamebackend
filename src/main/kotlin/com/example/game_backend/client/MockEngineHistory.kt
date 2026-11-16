package com.example.game_backend.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin

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

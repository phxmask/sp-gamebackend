package com.example.game_backend.config

import com.example.game_backend.client.ConfigServiceClient
import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.AsyncCacheLoader
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.future
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.cache.autoconfigure.CacheManagerCustomizer
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
@EnableCaching
class CacheConfig {
    @Bean
    fun asyncCacheModeCustomizer(
        configClient: ObjectProvider<ConfigServiceClient>,
        @Value("\${game.clients.config.refresh-interval}") configRefreshInterval: Duration,
    ): CacheManagerCustomizer<CaffeineCacheManager> = CacheManagerCustomizer {
        it.setAsyncCacheMode(true)
        it.registerCustomCache(
            CONFIG_CACHE,
            refreshingCache(configRefreshInterval) { configClient.getObject().fetchConfig() },
        )
    }

    companion object {
        const val CONFIG_CACHE = "config"
    }
}

fun refreshingCache(
    refreshInterval: Duration,
    ticker: Ticker = Ticker.systemTicker(),
    load: suspend () -> Any,
): AsyncCache<Any, Any> =
    Caffeine
        .newBuilder()
        .refreshAfterWrite(refreshInterval)
        .ticker(ticker)
        .buildAsync(
            AsyncCacheLoader { _, executor ->
                CoroutineScope(executor.asCoroutineDispatcher()).future { load() }
            },
        )

package com.example.game_backend.config

import org.springframework.boot.cache.autoconfigure.CacheManagerCustomizer
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableCaching
class CacheConfig {
    @Bean
    fun asyncCacheModeCustomizer(): CacheManagerCustomizer<CaffeineCacheManager> = CacheManagerCustomizer {
        // TODO: Set up custom caches so that some of them have a longer TTL
        it.setAsyncCacheMode(true)
    }
}

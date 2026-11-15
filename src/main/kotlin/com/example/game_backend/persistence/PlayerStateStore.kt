package com.example.game_backend.persistence

import com.example.game_backend.domain.PlayerState
import com.example.game_backend.domain.toUtcDate
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import lmdb.Dbi
import lmdb.DbiOption
import lmdb.Env
import lmdb.EnvOption
import lmdb.TxnOption
import lmdb.first
import lmdb.get
import lmdb.next
import lmdb.put
import lmdb.toValueByteArray
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Repository
import java.io.File
import java.time.LocalDate
import kotlin.time.Clock

@Repository
class PlayerStateLMDBStore(
    @Value("\${game.lmdb.path:./data/lmdb}") path: String,
    @Value("\${game.lmdb.map-size-mb:1024}") mapSizeMb: Long,
) {
    private val env =
        Env().apply {
            mapSize = (mapSizeMb * 1024 * 1024).toULong()
            File(path).mkdirs()
            // Syncing every commit can get quite slow so we set NoSync and sync periodically
            open(path, EnvOption.NoTLS, EnvOption.NoSync)
        }

    private val lifecycleLock = Any()
    private var closed = false

    private val dbi: Dbi =
        env.beginTxn().use { txn ->
            txn.dbiOpen(null, DbiOption.Create).also { txn.commit() }
        }

    // Single writer
    private val readDispatcher = Dispatchers.IO
    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)

    suspend fun get(
        playerId: Long,
        day: LocalDate,
    ): PlayerState? =
        withContext(readDispatcher) {
            env.beginTxn(TxnOption.ReadOnly).use { txn ->
                txn.get(dbi, key(playerId, day)).toValueByteArray()?.let(::decode)
            }
        }

    suspend fun compute(
        playerId: Long,
        day: LocalDate,
        update: (PlayerState?) -> PlayerState?,
    ): PlayerState? =
        withContext(writeDispatcher) {
            env.beginTxn().use { txn ->
                val k = key(playerId, day)
                val current = txn.get(dbi, k).toValueByteArray()?.let(::decode)
                val updated = update(current)
                if (updated != null) {
                    txn.put(dbi, k, encode(updated))
                    txn.commit()
                }
                updated ?: current
            }
        }

    @PreDestroy
    fun close() {
        synchronized(lifecycleLock) {
            closed = true
            env.sync(true)
            env.close()
        }
    }

    @Scheduled(cron = "\${game.lmdb.cleanup-cron:0 5 * * * *}", zone = "UTC")
    fun deleteExpired() {
        val cutoff =
            Clock.System
                .now()
                .toUtcDate()
                .minusDays(RETAINED_DAYS)
                .toString()
        var deleted = 0
        do {
            val batch =
                synchronized(lifecycleLock) {
                    if (closed) return
                    deleteBatchBefore(cutoff)
                }
            deleted += batch
        } while (batch == CLEANUP_BATCH_SIZE)
        log.info("Deleted {} player states from before {}", deleted, cutoff)
    }

    private fun deleteBatchBefore(cutoff: String): Int =
        env.beginTxn().use { txn ->
            var deleted = 0
            txn.openCursor(dbi).use { cursor ->
                var entry = cursor.first()
                while (deleted < CLEANUP_BATCH_SIZE &&
                    entry.resultCode == 0 &&
                    entry.key.toByteArray()!!.decodeToString() < cutoff
                ) {
                    cursor.delete()
                    deleted++
                    entry = cursor.next()
                }
            }
            txn.commit()
            deleted
        }

    @Scheduled(
        fixedDelayString = "\${game.lmdb.sync-interval-ms:1000}",
        initialDelayString = "\${game.lmdb.sync-interval-ms:1000}",
    )
    fun sync() {
        synchronized(lifecycleLock) {
            if (closed) return
            try {
                env.sync(true)
            } catch (e: Exception) {
                log.error("Failed to sync LMDB to disk: {}", e.message)
            }
        }
    }

    // Since we cleanup by dates, the key has the day first so it is sorted correctly
    private fun key(
        playerId: Long,
        day: LocalDate,
    ) = "$day:$playerId".encodeToByteArray()

    private fun encode(playerState: PlayerState) = Json.encodeToString(playerState).encodeToByteArray()

    private fun decode(bytes: ByteArray) = Json.decodeFromString<PlayerState>(bytes.decodeToString())

    companion object {
        private val log = LoggerFactory.getLogger(PlayerStateLMDBStore::class.java)

        // Yesterday is still needed for battles started before midnight UTC
        private const val RETAINED_DAYS = 2L
        private const val CLEANUP_BATCH_SIZE = 1000
    }
}

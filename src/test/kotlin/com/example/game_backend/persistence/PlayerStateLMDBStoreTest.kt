package com.example.game_backend.persistence

import com.example.game_backend.domain.ActiveBattle
import com.example.game_backend.domain.PlayerState
import com.example.game_backend.domain.toUtcDate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PlayerStateLMDBStoreTest {
    @TempDir
    private lateinit var directory: Path

    private lateinit var store: PlayerStateLMDBStore
    private var open = false

    private val today = Clock.System.now().toUtcDate()

    @BeforeEach
    fun setUp() {
        openStore()
    }

    @AfterEach
    fun tearDown() {
        closeStore()
    }

    @Test
    fun `returns nothing for a player without a state`() =
        runTest {
            assertNull(store.get(PLAYER_ID, today))
        }

    @Test
    fun `stores and reads back every field of a state`() =
        runTest {
            val stored = store.compute(PLAYER_ID, today) { STATE }

            assertEquals(STATE, stored)
            assertEquals(STATE, store.get(PLAYER_ID, today))
        }

    @Test
    fun `updates from the current state`() =
        runTest {
            store.compute(PLAYER_ID, today) { STATE }

            val updated = store.compute(PLAYER_ID, today) { it!!.copy(dailyScore = it.dailyScore + 10) }

            assertEquals(STATE.copy(dailyScore = 130), updated)
            assertEquals(STATE.copy(dailyScore = 130), store.get(PLAYER_ID, today))
        }

    @Test
    fun `leaves a missing state missing when the update returns null`() =
        runTest {
            val result = store.compute(PLAYER_ID, today) { null }

            assertNull(result)
            assertNull(store.get(PLAYER_ID, today))
        }

    @Test
    fun `keeps the current state when the update returns null`() =
        runTest {
            store.compute(PLAYER_ID, today) { STATE }

            val result = store.compute(PLAYER_ID, today) { null }

            assertEquals(STATE, result)
            assertEquals(STATE, store.get(PLAYER_ID, today))
        }

    @Test
    fun `keeps states separate per player and per day`() =
        runTest {
            store.compute(PLAYER_ID, today) { PlayerState(dailyScore = 1) }
            store.compute(OTHER_PLAYER_ID, today) { PlayerState(dailyScore = 2) }
            store.compute(PLAYER_ID, today.minusDays(1)) { PlayerState(dailyScore = 3) }

            assertEquals(1, store.get(PLAYER_ID, today)?.dailyScore)
            assertEquals(2, store.get(OTHER_PLAYER_ID, today)?.dailyScore)
            assertEquals(3, store.get(PLAYER_ID, today.minusDays(1))?.dailyScore)
        }

    @Test
    fun `applies concurrent updates one at a time`() =
        runTest {
            coroutineScope {
                List(200) {
                    async {
                        store.compute(
                            PLAYER_ID,
                            today,
                        ) { state -> (state ?: PlayerState()).run { copy(dailyScore = dailyScore + 1) } }
                    }
                }.awaitAll()
            }

            assertEquals(200, store.get(PLAYER_ID, today)?.dailyScore)
        }

    @Test
    fun `keeps states after the store is reopened`() =
        runTest {
            store.compute(PLAYER_ID, today) { STATE }

            closeStore()
            openStore()

            assertEquals(STATE, store.get(PLAYER_ID, today))
        }

    @Test
    fun `deletes states from before the last two days`() =
        runTest {
            val players = 1L..1_500L
            (0L..4L).forEach { daysAgo ->
                players.forEach { playerId -> store.compute(playerId, today.minusDays(daysAgo)) { PlayerState(dailyScore = 1) } }
            }

            store.deleteExpired()

            players.forEach { playerId ->
                (0L..2L).forEach { daysAgo -> assertNotNull(store.get(playerId, today.minusDays(daysAgo))) }
                (3L..4L).forEach { daysAgo -> assertNull(store.get(playerId, today.minusDays(daysAgo))) }
            }
        }

    @Test
    fun `skips the scheduled jobs once closed`() {
        closeStore()

        assertDoesNotThrow {
            store.sync()
            store.deleteExpired()
        }
    }

    private fun openStore() {
        store = PlayerStateLMDBStore(directory.toString(), mapSizeMb = 64)
        open = true
    }

    private fun closeStore() {
        if (open) {
            store.close()
            open = false
        }
    }

    companion object {
        private const val PLAYER_ID = 42L
        private const val OTHER_PLAYER_ID = 7L

        private val STATE =
            PlayerState(
                activeBattle = ActiveBattle(Uuid.parse("3f2b8c1e-5d4a-4e7b-9c6f-1a2b3c4d5e6f"), levelId = 1),
                dailyBattleStats = mapOf(1L to 2, 3L to 1),
                dailyScore = 120,
                lastActiveTime = Instant.parse("2026-11-16T10:00:00Z"),
            )
    }
}

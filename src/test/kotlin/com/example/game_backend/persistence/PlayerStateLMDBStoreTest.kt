package com.example.game_backend.persistence

import com.example.game_backend.domain.ActiveBattle
import com.example.game_backend.domain.CompletedBattle
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
import kotlin.time.Duration.Companion.minutes
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
    fun `doesn't store recorded activity before it is flushed`() =
        runTest {
            store.recordActivity(PLAYER_ID, ACTIVE_AT)

            assertNull(store.get(PLAYER_ID, ACTIVE_DAY))
        }

    @Test
    fun `flushes the activity of a new player into a new state`() =
        runTest {
            store.recordActivity(PLAYER_ID, ACTIVE_AT)

            store.flushActivity()

            assertEquals(PlayerState(lastActiveTime = ACTIVE_AT), store.get(PLAYER_ID, ACTIVE_DAY))
        }

    @Test
    fun `flushes the latest activity and keeps the rest of the state`() =
        runTest {
            store.compute(PLAYER_ID, ACTIVE_DAY) { STATE }
            store.recordActivity(PLAYER_ID, ACTIVE_AT + 2.minutes)
            store.recordActivity(PLAYER_ID, ACTIVE_AT + 1.minutes)

            store.flushActivity()

            assertEquals(STATE.copy(lastActiveTime = ACTIVE_AT + 2.minutes), store.get(PLAYER_ID, ACTIVE_DAY))
        }

    @Test
    fun `keeps the last active time of a state that was updated after the activity`() =
        runTest {
            store.recordActivity(PLAYER_ID, ACTIVE_AT - 1.minutes)
            store.compute(PLAYER_ID, ACTIVE_DAY) { STATE }

            store.flushActivity()

            assertEquals(STATE, store.get(PLAYER_ID, ACTIVE_DAY))
        }

    @Test
    fun `flushes the pending activity when closed`() =
        runTest {
            store.recordActivity(PLAYER_ID, ACTIVE_AT)

            closeStore()
            openStore()

            assertEquals(PlayerState(lastActiveTime = ACTIVE_AT), store.get(PLAYER_ID, ACTIVE_DAY))
        }

    @Test
    fun `skips the scheduled jobs once closed`() {
        closeStore()

        assertDoesNotThrow {
            store.sync()
            store.deleteExpired()
            store.flushActivity()
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
        private val ACTIVE_AT = Instant.parse("2026-11-16T10:00:00Z")
        private val ACTIVE_DAY = ACTIVE_AT.toUtcDate()

        private val STATE =
            PlayerState(
                activeBattle = ActiveBattle(Uuid.parse("3f2b8c1e-5d4a-4e7b-9c6f-1a2b3c4d5e6f"), levelId = 1),
                lastCompletedBattle = CompletedBattle(Uuid.parse("9a1c7d2e-4b3f-4c8a-8e5d-6f7a8b9c0d1e"), score = 40),
                dailyBattleStats = mapOf(1L to 2, 3L to 1),
                dailyScore = 120,
                lastActiveTime = ACTIVE_AT,
            )
    }
}

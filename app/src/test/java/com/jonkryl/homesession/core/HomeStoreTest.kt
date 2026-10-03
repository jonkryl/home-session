package com.jonkryl.homesession.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class HomeStoreTest {
    @Test fun formingPlanAndStartingSessionNeverChangesHistoryOrLastDone() {
        val persistence = JsonMemoryPersistence()
        val store = testStore(persistence)
        val task = store.newTask("Wipe desk", 5, 7, null)
        val before = store.state
        val writes = persistence.writes
        assertEquals(PlanOutcome.READY, store.planSession(5).outcome)
        assertEquals(before, store.state)
        assertEquals(writes, persistence.writes)
        store.startSession(5)
        assertEquals(before.tasks, store.state.tasks)
        assertEquals(before.history, store.state.history)
        assertNull(store.state.tasks.single { it.id == task.id }.lastDone)
    }

    @Test fun failedProposalDoesNotSaveAnEmptyFinishedSession() {
        val store = testStore()
        store.newTask("Deep clean", 31, 30, null)
        assertEquals(PlanOutcome.NO_TASK_FITS, store.startSession(30).outcome)
        assertNull(store.state.activeSession)
        assertTrue(store.state.history.isEmpty())
    }

    @Test fun noDueProposalLeavesAllDatesUntouched() {
        val store = testStore()
        val task = store.newTask("Clean today", 5, 7, null, store.today)
        assertEquals(PlanOutcome.NO_DUE_TASKS, store.startSession(15).outcome)
        assertEquals(task, store.state.tasks.single())
        assertNull(store.state.activeSession)
    }

    @Test fun repeatedMarkAndUndoAreIdempotent() {
        val store = testStore()
        val previous = store.today.minusDays(12)
        val task = store.newTask("Sink", 5, 7, null, previous)
        store.startSession(5)
        assertTrue(store.markDone(task.id))
        assertFalse(store.markDone(task.id))
        assertEquals(1, store.state.history.size)
        assertEquals(1, store.state.activeSession!!.completedCount)
        assertEquals(store.today, store.state.tasks.single().lastDone)
        assertTrue(store.undoDone(task.id))
        assertFalse(store.undoDone(task.id))
        assertTrue(store.state.history.isEmpty())
        assertEquals(previous, store.state.tasks.single().lastDone)
        assertFalse(store.state.activeSession!!.items.single().isCompleted)
        assertTrue(store.markDone(task.id))
        assertEquals(1, store.state.history.size)
    }

    @Test fun cancellationRetainsCompletedTaskOnly() {
        val store = testStore()
        val done = store.newTask("Done", 5, 7, null)
        val untouched = store.newTask("Pending", 5, 7, null)
        store.startSession(15)
        store.markDone(done.id)
        store.cancelSession()
        assertNull(store.state.activeSession)
        assertEquals(listOf(done.id), store.state.history.map { it.taskId })
        assertEquals(store.today, store.state.tasks.first { it.id == done.id }.lastDone)
        assertNull(store.state.tasks.first { it.id == untouched.id }.lastDone)
    }

    @Test fun finishPartialSessionDoesNotCompleteUncheckedItems() {
        val store = testStore()
        val a = store.newTask("A", 5, 7, null)
        val b = store.newTask("B", 5, 7, null)
        store.startSession(15)
        store.markDone(a.id)
        store.finishSession()
        assertNull(store.state.tasks.first { it.id == b.id }.lastDone)
        assertEquals(1, store.state.history.size)
        assertFalse(store.markDone(b.id))
    }

    @Test fun restartResumesPartialSessionAndPreservesUndo() {
        val persistence = JsonMemoryPersistence()
        val store = testStore(persistence)
        val a = store.newTask("A", 5, 7, null)
        store.newTask("B", 5, 7, null)
        store.startSession(15)
        store.markDone(a.id)
        val restored = testStore(persistence)
        assertEquals(store.state, restored.state)
        assertEquals(1, restored.state.activeSession!!.completedCount)
        assertTrue(restored.undoDone(a.id))
        assertNull(restored.state.tasks.first { it.id == a.id }.lastDone)
        assertTrue(restored.state.history.isEmpty())
        assertEquals(restored.state, testStore(persistence).state)
    }

    @Test fun editingPendingTaskUpdatesSnapshotAndKeepsSessionBudget() {
        val store = testStore()
        val task = store.newTask("Before", 5, 7, null)
        store.startSession(5)
        store.saveTask(task.copy(title = "After", durationMinutes = 4))
        assertEquals("After", store.state.activeSession!!.items.single().title)
        assertEquals(4, store.state.activeSession!!.totalMinutes)
        store.saveTask(store.state.tasks.single().copy(durationMinutes = 6))
        assertTrue(store.state.activeSession!!.items.isEmpty())
        assertEquals(ExclusionReason.TASK_UPDATED, store.state.activeSession!!.excluded.single().reason)
        assertTrue(store.state.history.isEmpty())
    }

    @Test fun editDurationKeepsCompletedTimeAndDropsPendingItemsThatNoLongerFit() {
        val store = testStore()
        val a = store.newTask("A", 5, 7, null, priority = TaskPriority.HIGH)
        val b = store.newTask("B", 5, 7, null)
        store.startSession(15)
        store.markDone(a.id)
        store.saveTask(store.state.tasks.first { it.id == b.id }.copy(durationMinutes = 11))
        assertEquals(5, store.state.activeSession!!.totalMinutes)
        assertEquals(a.id, store.state.activeSession!!.items.single().taskId)
        assertEquals(b.id, store.state.activeSession!!.excluded.single().taskId)
    }

    @Test fun manualFreshDateRemovesPendingTaskWithoutAddingHistory() {
        val store = testStore()
        val task = store.newTask("A", 5, 7, null)
        store.startSession(5)
        store.saveTask(task.copy(lastDone = store.today))
        assertTrue(store.state.activeSession!!.items.isEmpty())
        assertTrue(store.state.history.isEmpty())
        assertFalse(store.markDone(task.id))
    }

    @Test fun undoDoesNotOverwriteManualLastDoneEdit() {
        val store = testStore()
        val task = store.newTask("A", 5, 7, null)
        store.startSession(5)
        store.markDone(task.id)
        val manualDate = store.today.minusDays(3)
        store.saveTask(store.state.tasks.single().copy(lastDone = manualDate))
        assertTrue(store.undoDone(task.id))
        assertEquals(manualDate, store.state.tasks.single().lastDone)
        assertTrue(store.state.history.isEmpty())
    }

    @Test fun undoAfterCompletedTaskEditUsesCurrentTaskAndRechecksBudget() {
        val store = testStore()
        val task = store.newTask("Before", 5, 7, null)
        store.startSession(5)
        store.markDone(task.id)
        store.saveTask(store.state.tasks.single().copy(title = "After", durationMinutes = 20))
        assertEquals("Before", store.state.history.single().taskTitle)
        assertEquals(5, store.state.activeSession!!.totalMinutes)
        store.undoDone(task.id)
        assertEquals("After", store.state.tasks.single().title)
        assertNull(store.state.tasks.single().lastDone)
        assertTrue(store.state.activeSession!!.items.isEmpty())
        assertEquals(ExclusionReason.TASK_UPDATED, store.state.activeSession!!.excluded.single().reason)
    }

    @Test fun undoRestoresEarlierRealCompletionSourceAcrossSessions() {
        val clock = MutableHomeClock(Instant.parse("2026-10-03T10:00:00Z"))
        val persistence = JsonMemoryPersistence()
        val store = testStore(persistence, clock)
        val task = store.newTask("Daily", 5, 1, null)
        store.startSession(5)
        store.markDone(task.id)
        val first = store.state.history.single()
        store.finishSession()
        clock.instantValue = Instant.parse("2026-10-04T10:00:00Z")
        store.startSession(5)
        store.markDone(task.id)
        assertEquals(2, store.state.history.size)
        store.undoDone(task.id)
        assertEquals(listOf(first), store.state.history)
        assertEquals(first.localDate, store.state.tasks.single().lastDone)
        assertEquals(first.id, store.state.tasks.single().lastDoneCompletionId)
        assertEquals(store.state, testStore(persistence, clock).state)
    }

    @Test fun deletingPendingTaskRemovesItAndNeverMarksItDone() {
        val store = testStore()
        val task = store.newTask("A", 5, 7, null)
        store.startSession(5)
        store.deleteTask(task.id)
        assertTrue(store.state.tasks.isEmpty())
        assertTrue(store.state.activeSession!!.items.isEmpty())
        assertFalse(store.markDone(task.id))
        assertTrue(store.state.history.isEmpty())
    }

    @Test fun deletingCompletedTaskPreservesJournalAndStillAllowsUndo() {
        val store = testStore()
        val task = store.newTask("A", 5, 7, null)
        store.startSession(5)
        store.markDone(task.id)
        store.deleteTask(task.id)
        assertEquals("A", store.state.history.single().taskTitle)
        assertEquals(1, store.state.activeSession!!.completedCount)
        assertTrue(store.undoDone(task.id))
        assertTrue(store.state.history.isEmpty())
        assertTrue(store.state.activeSession!!.items.isEmpty())
    }

    @Test fun roomRenameChangesPendingLabelButCompletedJournalKeepsOriginal() {
        val store = testStore()
        val room = store.saveRoom("Kitchen")
        val a = store.newTask("A", 5, 7, room.id)
        store.newTask("B", 5, 7, room.id)
        store.startSession(15)
        store.markDone(a.id)
        store.saveRoom("Cooking room", room.id)
        assertEquals("Kitchen", store.state.history.single().roomName)
        assertEquals("Kitchen", store.state.activeSession!!.items.first { it.isCompleted }.roomName)
        assertEquals("Cooking room", store.state.activeSession!!.items.first { !it.isCompleted }.roomName)
        store.deleteRoom(room.id)
        assertTrue(store.state.tasks.all { it.roomId == null })
        assertNull(store.state.activeSession!!.items.first { !it.isCompleted }.roomName)
    }

    @Test fun allTaskFieldsPersistAndCanBeEdited() {
        val persistence = JsonMemoryPersistence()
        val store = testStore(persistence)
        val room = store.saveRoom("Bedroom")
        val task = store.newTask("Original", 5, 7, null)
        val changed = store.saveTask(task.copy(title = "  Changed  ", durationMinutes = 30, intervalDays = 10,
            roomId = room.id, lastDone = LocalDate.of(2026, 9, 28), priority = TaskPriority.HIGH))
        assertEquals("Changed", changed.title)
        assertEquals(changed, testStore(persistence).state.tasks.single())
    }

    @Test fun localDayAtMidnightControlsCompletionAndIntervalBoundary() {
        val clock = MutableHomeClock(Instant.parse("2026-10-02T21:30:00Z"), ZoneId.of("Europe/Kaliningrad"))
        val store = testStore(clock = clock)
        assertEquals(LocalDate.of(2026, 10, 2), store.today)
        val task = store.newTask("Daily", 5, 1, null, store.today)
        assertEquals(PlanOutcome.NO_DUE_TASKS, store.planSession(5).outcome)
        clock.instantValue = Instant.parse("2026-10-02T22:30:00Z")
        assertEquals(LocalDate.of(2026, 10, 3), store.today)
        store.startSession(5)
        store.markDone(task.id)
        assertEquals(LocalDate.of(2026, 10, 3), store.state.history.single().localDate)
        assertEquals(clock.millis(), store.state.history.single().completedAt)
    }

    @Test fun completionTimestampAndDateComeFromOneInstantAtMidnight() {
        var reads = 0
        val base = Instant.parse("2026-10-03T23:59:59Z")
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = base.plusSeconds(2L * reads++)
        }
        val store = testStore(clock = clock)
        val task = store.newTask("A", 5, 7, null)
        store.startSession(5)
        reads = 0
        store.markDone(task.id)
        val completion = store.state.history.single()
        assertEquals(1, reads)
        assertEquals(base.toEpochMilli(), completion.completedAt)
        assertEquals(LocalDate.of(2026, 10, 3), completion.localDate)
    }

    @Test fun identicalInstantUsesDeviceZoneForItsCalendarDate() {
        val moment = Instant.parse("2026-10-03T01:00:00Z")
        val west = testStore(clock = Clock.fixed(moment, ZoneId.of("America/Los_Angeles")))
        val east = testStore(clock = Clock.fixed(moment, ZoneId.of("Europe/Kaliningrad")))
        assertEquals(LocalDate.of(2026, 10, 2), west.today)
        assertEquals(LocalDate.of(2026, 10, 3), east.today)
    }

    @Test fun failedSaveLeavesStateAndCompletionUndoUnchanged() {
        val persistence = JsonMemoryPersistence()
        val store = testStore(persistence)
        val task = store.newTask("A", 5, 7, null)
        store.startSession(5)
        val before = store.state
        persistence.failWrites = true
        try { store.markDone(task.id); fail("Expected storage failure") } catch (_: IOException) { }
        assertEquals(before, store.state)
        persistence.failWrites = false
        assertTrue(store.markDone(task.id))
        val completed = store.state
        persistence.failWrites = true
        try { store.undoDone(task.id); fail("Expected storage failure") } catch (_: IOException) { }
        assertEquals(completed, store.state)
    }

    @Test fun emptySetupAndOriginalTemplatesArePersistedWithoutCompletions() {
        val emptyPersistence = JsonMemoryPersistence()
        val empty = testStore(emptyPersistence)
        empty.initializeEmpty()
        assertTrue(testStore(emptyPersistence).state.onboardingComplete)
        assertTrue(empty.state.tasks.isEmpty())
        listOf("ru", "en").forEach { language ->
            val store = testStore()
            store.applyStarterTemplates(language)
            assertEquals(3, store.state.rooms.size)
            assertEquals(9, store.state.tasks.size)
            assertTrue(store.state.tasks.all { it.lastDone == null })
            assertTrue(store.state.history.isEmpty())
            assertEquals(PlanOutcome.READY, store.planSession(15).outcome)
        }
    }

    @Test(expected = IllegalStateException::class) fun activeSessionCannotBeSilentlyReplaced() {
        val store = testStore()
        store.newTask("A", 5, 7, null)
        store.startSession(5)
        store.startSession(15)
    }

    @Test(expected = IllegalArgumentException::class) fun zeroMinuteTaskIsRejected() {
        testStore().newTask("A", 0, 7, null)
    }

    @Test(expected = IllegalArgumentException::class) fun zeroIntervalTaskIsRejected() {
        testStore().newTask("A", 5, 0, null)
    }
}

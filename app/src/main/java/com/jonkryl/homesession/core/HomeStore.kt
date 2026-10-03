package com.jonkryl.homesession.core

import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

interface HomePersistence {
    @Throws(IOException::class) fun load(): HomeState?
    @Throws(IOException::class) fun save(state: HomeState)
}

/** Commit-before-publish keeps the visible state unchanged if saving fails. */
class HomeStore(
    private val persistence: HomePersistence,
    private val clock: Clock? = null,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    var state: HomeState = persistence.load() ?: HomeState()
        private set

    init { validateState(state) }
    // The default zone is read for each operation so travelling does not require a process restart.
    val today: LocalDate get() = localDate(nowMillis())

    fun initializeEmpty() {
        check(!state.onboardingComplete) { "Setup is already complete" }
        commit(state.copy(onboardingComplete = true))
    }

    fun applyStarterTemplates(language: String) {
        check(!state.onboardingComplete && state.rooms.isEmpty() && state.tasks.isEmpty()) { "Setup is already complete" }
        val rooms = mutableListOf<Room>()
        val tasks = mutableListOf<HomeTask>()
        HomeTemplates.rooms(language).forEach { starter ->
            val room = Room(newId(), starter.name)
            rooms += room
            tasks += starter.tasks.map { HomeTask(newId(), it.title, it.minutes, it.days, room.id, priority = it.priority) }
        }
        commit(state.copy(rooms = rooms, tasks = tasks, onboardingComplete = true))
    }

    fun saveRoom(name: String, id: String? = null): Room {
        val room = Room(id ?: newId(), name.trim())
        if (id != null) require(state.rooms.any { it.id == id }) { "Unknown room" }
        val rooms = if (id == null) state.rooms + room else state.rooms.map { if (it.id == id) room else it }
        commit(reconcileSession(state.copy(rooms = rooms)))
        return room
    }

    /** Deleting a room preserves its tasks in the unassigned group. */
    fun deleteRoom(id: String) {
        require(state.rooms.any { it.id == id }) { "Unknown room" }
        commit(reconcileSession(state.copy(rooms = state.rooms.filterNot { it.id == id },
            tasks = state.tasks.map { if (it.roomId == id) it.copy(roomId = null) else it })))
    }

    fun newTask(title: String, durationMinutes: Int, intervalDays: Int, roomId: String?, lastDone: LocalDate? = null,
                priority: TaskPriority = TaskPriority.NORMAL): HomeTask {
        val task = HomeTask(newId(), title.trim(), durationMinutes, intervalDays, roomId, lastDone, priority)
        commit(state.copy(tasks = state.tasks + task))
        return task
    }

    fun saveTask(task: HomeTask): HomeTask {
        val old = state.tasks.firstOrNull { it.id == task.id } ?: throw IllegalArgumentException("Unknown task")
        val cleaned = task.copy(title = task.title.trim(),
            lastDoneCompletionId = if (task.lastDone == old.lastDone) old.lastDoneCompletionId else null)
        validateTask(cleaned)
        commit(reconcileSession(state.copy(tasks = state.tasks.map { if (it.id == task.id) cleaned else it })))
        return cleaned
    }

    fun deleteTask(id: String) {
        require(state.tasks.any { it.id == id }) { "Unknown task" }
        val session = state.activeSession?.let { current ->
            current.copy(items = current.items.filterNot { it.taskId == id && !it.isCompleted },
                excluded = current.excluded.filterNot { it.taskId == id })
        }
        commit(state.copy(tasks = state.tasks.filterNot { it.id == id }, activeSession = session))
    }

    fun planSession(budgetMinutes: Int): SessionPlan = HomePlanner.plan(state, budgetMinutes, today)

    /** A failed/empty proposal is returned honestly and does not create a resumable session. */
    fun startSession(budgetMinutes: Int): SessionPlan {
        check(state.activeSession == null) { "Continue or finish the current session first" }
        val plan = planSession(budgetMinutes)
        if (plan.outcome == PlanOutcome.READY) {
            commit(state.copy(activeSession = CleaningSession(newId(), plan.budgetMinutes, plan.plannedOn,
                nowMillis(), plan.items, plan.excluded)))
        }
        return plan
    }

    /** Repeated taps on an already completed item are harmless and never duplicate the journal. */
    fun markDone(taskId: String): Boolean {
        val session = state.activeSession ?: return false
        val item = session.items.firstOrNull { it.taskId == taskId } ?: return false
        if (item.isCompleted) return false
        val task = state.tasks.firstOrNull { it.id == taskId } ?: return false
        val completedAt = nowMillis()
        val completion = Completion(newId(), task.id, item.title, item.roomName, completedAt, localDate(completedAt),
            session.id, task.lastDone, task.lastDoneCompletionId)
        commit(state.copy(
            tasks = state.tasks.map { if (it.id == taskId) it.copy(lastDone = completion.localDate, lastDoneCompletionId = completion.id) else it },
            history = listOf(completion) + state.history,
            activeSession = session.copy(items = session.items.map { if (it.taskId == taskId) it.copy(completionId = completion.id) else it }),
        ))
        return true
    }

    fun undoDone(taskId: String): Boolean {
        val session = state.activeSession ?: return false
        val item = session.items.firstOrNull { it.taskId == taskId } ?: return false
        val completionId = item.completionId ?: return false
        val completion = state.history.first { it.id == completionId }
        val history = state.history.filterNot { it.id == completionId }
        val tasks = state.tasks.map { task ->
            if (task.id == taskId && task.lastDoneCompletionId == completionId) task.copy(
                lastDone = completion.previousLastDone,
                lastDoneCompletionId = completion.previousLastDoneCompletionId?.takeIf { previous -> history.any { it.id == previous } },
            ) else task
        }
        // A deleted task can still have its erroneous completion removed from the journal.
        val items = session.items.mapNotNull {
            if (it.taskId != taskId) it else if (tasks.none { task -> task.id == taskId }) null else it.copy(completionId = null)
        }
        commit(reconcileSession(state.copy(tasks = tasks, history = history, activeSession = session.copy(items = items))))
        return true
    }

    /** Both finishing and cancelling retain only explicit completion events. */
    fun finishSession() { if (state.activeSession != null) commit(state.copy(activeSession = null)) }
    fun cancelSession() = finishSession()

    private fun reconcileSession(next: HomeState): HomeState {
        val session = next.activeSession ?: return next
        val roomNames = next.rooms.associate { it.id to it.name }
        var remaining = session.budgetMinutes - session.items.filter { it.isCompleted }.sumOf { it.durationMinutes }
        val removed = mutableListOf<ExcludedTask>()
        val items = session.items.mapNotNull { item ->
            if (item.isCompleted) return@mapNotNull item
            val task = next.tasks.firstOrNull { it.id == item.taskId } ?: return@mapNotNull null
            if (HomePlanner.status(task, today) != TaskStatus.OVERDUE || task.durationMinutes > remaining) {
                removed += ExcludedTask(task.id, task.title, task.durationMinutes, ExclusionReason.TASK_UPDATED)
                null
            } else {
                remaining -= task.durationMinutes
                item.copy(title = task.title, roomName = roomNames[task.roomId], durationMinutes = task.durationMinutes,
                    priority = task.priority, reason = HomePlanner.reason(task, today))
            }
        }
        val selectedIds = items.map { it.taskId }.toSet()
        val removedIds = removed.map { it.taskId }.toSet()
        val excluded = session.excluded.filter { it.taskId !in selectedIds && it.taskId !in removedIds && next.tasks.any { task -> task.id == it.taskId } } + removed
        return next.copy(activeSession = session.copy(items = items, excluded = excluded))
    }

    private fun commit(next: HomeState) {
        if (next == state) return
        validateState(next)
        persistence.save(next)
        state = next
    }

    private fun nowMillis(): Long = clock?.millis() ?: System.currentTimeMillis()
    private fun localDate(atMillis: Long): LocalDate = Instant.ofEpochMilli(atMillis)
        .atZone(clock?.zone ?: ZoneId.systemDefault()).toLocalDate()
}

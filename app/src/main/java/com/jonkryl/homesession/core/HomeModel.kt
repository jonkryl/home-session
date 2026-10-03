package com.jonkryl.homesession.core

import java.time.LocalDate

data class Room(val id: String, val name: String)

enum class TaskPriority(val scoreBonus: Long) { LOW(0), NORMAL(25), HIGH(50) }
enum class TaskStatus { OVERDUE, SOON, FRESH }

data class HomeTask(
    val id: String,
    val title: String,
    val durationMinutes: Int,
    val intervalDays: Int,
    val roomId: String?,
    val lastDone: LocalDate? = null,
    val priority: TaskPriority = TaskPriority.NORMAL,
    /** Identifies the completion that supplied lastDone, allowing undo without overwriting a manual edit. */
    val lastDoneCompletionId: String? = null,
)

enum class ReasonKind { NEVER_DONE, DUE_TODAY, OVERDUE }
data class SelectionReason(val kind: ReasonKind, val daysOverdue: Long, val urgencyScore: Long)
enum class ExclusionReason { TOO_LONG, BUDGET, TASK_UPDATED }
data class ExcludedTask(val taskId: String, val title: String, val durationMinutes: Int, val reason: ExclusionReason)
enum class PlanOutcome { READY, NO_DUE_TASKS, NO_TASK_FITS }

data class SessionItem(
    val taskId: String,
    val title: String,
    val roomName: String?,
    val durationMinutes: Int,
    val priority: TaskPriority,
    val reason: SelectionReason,
    val completionId: String? = null,
) {
    val isCompleted: Boolean get() = completionId != null
}

data class SessionPlan(
    val budgetMinutes: Int,
    val plannedOn: LocalDate,
    val items: List<SessionItem>,
    val excluded: List<ExcludedTask>,
    val outcome: PlanOutcome,
) {
    val totalMinutes: Int get() = items.sumOf { it.durationMinutes }
}

data class CleaningSession(
    val id: String,
    val budgetMinutes: Int,
    val plannedOn: LocalDate,
    val startedAt: Long,
    val items: List<SessionItem>,
    val excluded: List<ExcludedTask>,
) {
    val totalMinutes: Int get() = items.sumOf { it.durationMinutes }
    val completedCount: Int get() = items.count { it.isCompleted }
}

/** Names are snapshots, so deleting or renaming a task never rewrites the journal. */
data class Completion(
    val id: String,
    val taskId: String,
    val taskTitle: String,
    val roomName: String?,
    val completedAt: Long,
    val localDate: LocalDate,
    val sessionId: String,
    val previousLastDone: LocalDate?,
    val previousLastDoneCompletionId: String? = null,
)

data class HomeState(
    val rooms: List<Room> = emptyList(),
    val tasks: List<HomeTask> = emptyList(),
    val history: List<Completion> = emptyList(),
    val activeSession: CleaningSession? = null,
    val onboardingComplete: Boolean = false,
)

internal fun validateState(state: HomeState) {
    require(state.rooms.map { it.id }.distinct().size == state.rooms.size) { "Duplicate room IDs" }
    require(state.tasks.map { it.id }.distinct().size == state.tasks.size) { "Duplicate task IDs" }
    require(state.history.map { it.id }.distinct().size == state.history.size) { "Duplicate completion IDs" }
    state.rooms.forEach { require(it.id.isNotBlank() && it.name.isNotBlank() && it.name.length <= 80) { "Invalid room" } }
    val roomIds = state.rooms.map { it.id }.toSet()
    state.tasks.forEach { task ->
        validateTask(task)
        require(task.roomId == null || task.roomId in roomIds) { "Unknown room" }
        require(task.lastDoneCompletionId == null || state.history.any {
            it.id == task.lastDoneCompletionId && it.taskId == task.id && it.localDate == task.lastDone
        }) { "Missing or inconsistent task completion" }
    }
    state.history.forEach { completion ->
        require(completion.id.isNotBlank() && completion.taskId.isNotBlank() && completion.sessionId.isNotBlank()) { "Invalid completion ID" }
        require(completion.taskTitle.isNotBlank() && completion.taskTitle.length <= 120 && completion.completedAt >= 0) { "Invalid completion" }
        validateDate(completion.localDate)
        completion.previousLastDone?.let(::validateDate)
    }
    state.activeSession?.let { session ->
        require(session.id.isNotBlank() && session.startedAt >= 0) { "Invalid session" }
        require(session.budgetMinutes in HomePlanner.BUDGETS && session.items.sumOf { it.durationMinutes.toLong() } <= session.budgetMinutes) { "Session exceeds budget" }
        validateDate(session.plannedOn)
        require(session.items.map { it.taskId }.distinct().size == session.items.size) { "Duplicate session tasks" }
        session.items.forEach { item ->
            require(item.taskId.isNotBlank() && item.title.isNotBlank() && item.title.length <= 120 && item.durationMinutes in 1..480) { "Invalid session item" }
            require(item.reason.daysOverdue >= 0 && item.reason.urgencyScore >= 0) { "Invalid session reason" }
            if (item.completionId == null) require(state.tasks.any { it.id == item.taskId }) { "Missing pending task" }
            else require(state.history.any { it.id == item.completionId && it.taskId == item.taskId && it.sessionId == session.id }) { "Missing session completion" }
        }
        session.excluded.forEach { require(it.taskId.isNotBlank() && it.title.isNotBlank() && it.durationMinutes in 1..480) { "Invalid exclusion" } }
    }
}

internal fun validateTask(task: HomeTask) {
    require(task.id.isNotBlank()) { "Empty task ID" }
    require(task.title.isNotBlank() && task.title.length <= 120) { "Task titles must contain 1 to 120 characters" }
    require(task.durationMinutes in 1..480) { "Duration must be 1 to 480 minutes" }
    require(task.intervalDays in 1..3650) { "Interval must be 1 to 3650 days" }
    task.lastDone?.let(::validateDate)
}

internal fun validateDate(date: LocalDate) { require(date.year in 1900..9999) { "Date must be between 1900 and 9999" } }

package com.jonkryl.homesession.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Only tasks due on the local calendar day are suggested; a fresh task is never invented to fill time. */
object HomePlanner {
    val BUDGETS: Set<Int> = setOf(5, 15, 30)
    fun dueDate(task: HomeTask): LocalDate? = task.lastDone?.plusDays(task.intervalDays.toLong())
    fun daysUntilDue(task: HomeTask, today: LocalDate): Long? = dueDate(task)?.let { ChronoUnit.DAYS.between(today, it) }
    fun status(task: HomeTask, today: LocalDate): TaskStatus {
        val days = daysUntilDue(task, today) ?: return TaskStatus.OVERDUE
        return when {
            days <= 0 -> TaskStatus.OVERDUE
            days <= 2 -> TaskStatus.SOON
            else -> TaskStatus.FRESH
        }
    }

    fun reason(task: HomeTask, today: LocalDate): SelectionReason {
        // With no history we treat the task as moderately due, rather than assuming infinite age.
        // Known severe overdue age can therefore outrank a newly created task.
        val lastDone = task.lastDone ?: return SelectionReason(ReasonKind.NEVER_DONE, 0, 150 + task.priority.scoreBonus)
        val overdue = maxOf(0, ChronoUnit.DAYS.between(dueDate(task), today))
        val age = maxOf(0, ChronoUnit.DAYS.between(lastDone, today))
        // Relative age lets a daily task ignored for a week outrank a recently due monthly task.
        val score = age * 100 / task.intervalDays + task.priority.scoreBonus
        return SelectionReason(if (overdue == 0L) ReasonKind.DUE_TODAY else ReasonKind.OVERDUE, overdue, score)
    }

    fun plan(state: HomeState, budgetMinutes: Int, today: LocalDate): SessionPlan {
        require(budgetMinutes in BUDGETS) { "Choose a 5, 15 or 30 minute session" }
        validateDate(today)
        validateState(state)
        val roomNames = state.rooms.associate { it.id to it.name }
        val due = state.tasks.filter { status(it, today) == TaskStatus.OVERDUE }
            .sortedWith(compareByDescending<HomeTask> { reason(it, today).urgencyScore }
                .thenByDescending { reason(it, today).daysOverdue }.thenBy { it.id })
        var remaining = budgetMinutes
        val items = mutableListOf<SessionItem>()
        val excluded = mutableListOf<ExcludedTask>()
        due.forEach { task ->
            if (task.durationMinutes <= remaining) {
                items += SessionItem(task.id, task.title, roomNames[task.roomId], task.durationMinutes, task.priority, reason(task, today))
                remaining -= task.durationMinutes
            } else {
                excluded += ExcludedTask(task.id, task.title, task.durationMinutes,
                    if (task.durationMinutes > budgetMinutes) ExclusionReason.TOO_LONG else ExclusionReason.BUDGET)
            }
        }
        return SessionPlan(budgetMinutes, today, items, excluded,
            if (items.isNotEmpty()) PlanOutcome.READY else if (due.isEmpty()) PlanOutcome.NO_DUE_TASKS else PlanOutcome.NO_TASK_FITS)
    }
}

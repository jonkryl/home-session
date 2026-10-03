package com.jonkryl.homesession.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class HomePlannerTest {
    private val today = LocalDate.of(2026, 10, 3)
    private fun task(id: String, minutes: Int = 5, interval: Int = 7, lastDone: LocalDate? = null,
                     priority: TaskPriority = TaskPriority.NORMAL) = HomeTask(id, "Task $id", minutes, interval, null, lastDone, priority)

    @Test fun realRelativeStalenessOutranksPriorityBonus() {
        val oldLow = task("old", lastDone = today.minusDays(28), priority = TaskPriority.LOW)
        val dueHigh = task("high", lastDone = today.minusDays(7), priority = TaskPriority.HIGH)
        val result = HomePlanner.plan(HomeState(tasks = listOf(dueHigh, oldLow)), 5, today)
        assertEquals("old", result.items.single().taskId)
        assertEquals(21L, result.items.single().reason.daysOverdue)
        assertEquals(ReasonKind.OVERDUE, result.items.single().reason.kind)
    }

    @Test fun priorityBreaksEqualAgeAndNeverDoneIsExplained() {
        val result = HomePlanner.plan(HomeState(tasks = listOf(task("low", priority = TaskPriority.LOW), task("high", priority = TaskPriority.HIGH))), 5, today)
        assertEquals("high", result.items.single().taskId)
        assertEquals(ReasonKind.NEVER_DONE, result.items.single().reason.kind)
    }

    @Test fun dailyTaskIgnoredForAWeekOutranksMonthlyTaskDueToday() {
        val daily = task("daily", interval = 1, lastDone = today.minusDays(7))
        val monthly = task("monthly", interval = 30, lastDone = today.minusDays(30))
        assertEquals("daily", HomePlanner.plan(HomeState(tasks = listOf(monthly, daily)), 5, today).items.single().taskId)
    }

    @Test fun severeKnownStalenessOutranksNewHighPriorityTaskWithNoHistory() {
        val ignored = task("ignored", interval = 1, lastDone = today.minusDays(10), priority = TaskPriority.LOW)
        val newHigh = task("new", priority = TaskPriority.HIGH)
        val plan = HomePlanner.plan(HomeState(tasks = listOf(newHigh, ignored)), 5, today)
        assertEquals("ignored", plan.items.single().taskId)
        assertTrue(HomePlanner.reason(ignored, today).urgencyScore > HomePlanner.reason(newHigh, today).urgencyScore)
    }

    @Test fun packingNeverExceedsAnyBudgetAndIncludesBoundaryDuration() {
        HomePlanner.BUDGETS.forEach { budget ->
            val boundary = task("boundary", minutes = budget, priority = TaskPriority.HIGH)
            val plan = HomePlanner.plan(HomeState(tasks = listOf(boundary, task("extra", minutes = 1))), budget, today)
            assertEquals(budget, plan.totalMinutes)
            assertEquals(listOf("boundary"), plan.items.map { it.taskId })
            assertEquals(ExclusionReason.BUDGET, plan.excluded.single().reason)
        }
    }

    @Test fun oversizedTaskDoesNotHideSmallerSuitableTask() {
        val plan = HomePlanner.plan(HomeState(tasks = listOf(task("large", 6, priority = TaskPriority.HIGH), task("small", 5))), 5, today)
        assertEquals(5, plan.totalMinutes)
        assertEquals("small", plan.items.single().taskId)
        assertEquals(ExclusionReason.TOO_LONG, plan.excluded.single().reason)
    }

    @Test fun plannerFillsRemainderWithoutInventingFreshTasks() {
        val tasks = listOf(task("a", 8, priority = TaskPriority.HIGH), task("b", 8), task("c", 7), task("fresh", 1, lastDone = today))
        val plan = HomePlanner.plan(HomeState(tasks = tasks), 15, today)
        assertEquals(listOf("a", "c"), plan.items.map { it.taskId })
        assertEquals(15, plan.totalMinutes)
        assertEquals(listOf("b"), plan.excluded.map { it.taskId })
    }

    @Test fun noDueTasksProducesHonestEmptyResult() {
        val plan = HomePlanner.plan(HomeState(tasks = listOf(task("fresh", lastDone = today))), 15, today)
        assertEquals(PlanOutcome.NO_DUE_TASKS, plan.outcome)
        assertTrue(plan.items.isEmpty())
        assertTrue(plan.excluded.isEmpty())
        assertEquals(0, plan.totalMinutes)
    }

    @Test fun noTaskFitsHasExplicitOversizedExclusions() {
        val plan = HomePlanner.plan(HomeState(tasks = listOf(task("large", 31))), 30, today)
        assertEquals(PlanOutcome.NO_TASK_FITS, plan.outcome)
        assertTrue(plan.items.isEmpty())
        assertEquals(31, plan.excluded.single().durationMinutes)
        assertEquals(ExclusionReason.TOO_LONG, plan.excluded.single().reason)
    }

    @Test fun intervalBoundaryIsCalendarDayAndDueTodayHasZeroOverdueDays() {
        val yesterday = task("yesterday", interval = 1, lastDone = today.minusDays(1))
        val todayDone = task("today", interval = 1, lastDone = today)
        assertEquals(today, HomePlanner.dueDate(yesterday))
        assertEquals(TaskStatus.OVERDUE, HomePlanner.status(yesterday, today))
        assertEquals(ReasonKind.DUE_TODAY, HomePlanner.reason(yesterday, today).kind)
        assertEquals(0L, HomePlanner.reason(yesterday, today).daysOverdue)
        assertEquals(TaskStatus.SOON, HomePlanner.status(todayDone, today))
        assertEquals(1L, HomePlanner.daysUntilDue(todayDone, today))
    }

    @Test fun filtersSeparateNeverDoneSoonAndFresh() {
        assertEquals(TaskStatus.OVERDUE, HomePlanner.status(task("never"), today))
        assertEquals(TaskStatus.SOON, HomePlanner.status(task("soon", interval = 2, lastDone = today), today))
        assertEquals(TaskStatus.FRESH, HomePlanner.status(task("fresh", interval = 3, lastDone = today), today))
    }

    @Test fun leapDayIntervalUsesDatesRatherThanApproximateMonths() {
        val leap = task("leap", interval = 1, lastDone = LocalDate.of(2024, 2, 28))
        assertEquals(LocalDate.of(2024, 2, 29), HomePlanner.dueDate(leap))
        assertEquals(TaskStatus.OVERDUE, HomePlanner.status(leap, LocalDate.of(2024, 2, 29)))
    }

    @Test(expected = IllegalArgumentException::class) fun unsupportedBudgetIsRejected() {
        HomePlanner.plan(HomeState(), 10, today)
    }
}

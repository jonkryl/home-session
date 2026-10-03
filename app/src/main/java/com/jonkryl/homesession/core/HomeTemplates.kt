package com.jonkryl.homesession.core

/** Original short starter chores, deliberately editable instead of a fixed cleaning catalogue. */
object HomeTemplates {
    data class StarterTask(val title: String, val minutes: Int, val days: Int, val priority: TaskPriority = TaskPriority.NORMAL)
    data class StarterRoom(val name: String, val tasks: List<StarterTask>)
    fun rooms(language: String): List<StarterRoom> = if (language.startsWith("ru", ignoreCase = true)) listOf(
        StarterRoom("Кухня", listOf(StarterTask("Освободить и протереть столешницу", 5, 2, TaskPriority.HIGH), StarterTask("Вымыть раковину", 3, 3), StarterTask("Проверить продукты в холодильнике", 7, 7))),
        StarterRoom("Ванная", listOf(StarterTask("Протереть раковину и кран", 4, 3), StarterTask("Освежить зеркало", 3, 7), StarterTask("Почистить душевую зону", 12, 7, TaskPriority.HIGH))),
        StarterRoom("Жилая комната", listOf(StarterTask("Вернуть вещи на места", 5, 2), StarterTask("Убрать пыль с доступных поверхностей", 7, 7), StarterTask("Пропылесосить свободный пол", 10, 7))),
    ) else listOf(
        StarterRoom("Kitchen", listOf(StarterTask("Clear and wipe the worktop", 5, 2, TaskPriority.HIGH), StarterTask("Wash the sink", 3, 3), StarterTask("Check food in the fridge", 7, 7))),
        StarterRoom("Bathroom", listOf(StarterTask("Wipe the basin and tap", 4, 3), StarterTask("Freshen the mirror", 3, 7), StarterTask("Clean the shower area", 12, 7, TaskPriority.HIGH))),
        StarterRoom("Living room", listOf(StarterTask("Put loose items back", 5, 2), StarterTask("Dust reachable surfaces", 7, 7), StarterTask("Vacuum the clear floor", 10, 7))),
    )
}

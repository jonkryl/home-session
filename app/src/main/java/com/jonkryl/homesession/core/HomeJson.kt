package com.jonkryl.homesession.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate

/** Local dates are ISO calendar dates; instants remain millisecond strings, independent of locale. */
object HomeJson {
    fun encode(state: HomeState): String {
        validateState(state)
        return JSONObject().apply {
            put("version", 1)
            put("onboardingComplete", state.onboardingComplete)
            put("rooms", array(state.rooms) { JSONObject().put("id", it.id).put("name", it.name) })
            put("tasks", array(state.tasks) { task -> JSONObject().apply {
                put("id", task.id); put("title", task.title); put("durationMinutes", task.durationMinutes)
                put("intervalDays", task.intervalDays); putNullable("roomId", task.roomId)
                putNullable("lastDone", task.lastDone?.toString()); put("priority", task.priority.name)
                putNullable("lastDoneCompletionId", task.lastDoneCompletionId)
            } })
            put("history", array(state.history) { done -> JSONObject().apply {
                put("id", done.id); put("taskId", done.taskId); put("taskTitle", done.taskTitle)
                putNullable("roomName", done.roomName); put("completedAt", done.completedAt.toString())
                put("localDate", done.localDate.toString()); put("sessionId", done.sessionId)
                putNullable("previousLastDone", done.previousLastDone?.toString())
                putNullable("previousLastDoneCompletionId", done.previousLastDoneCompletionId)
            } })
            put("activeSession", state.activeSession?.let { session -> JSONObject().apply {
                put("id", session.id); put("budgetMinutes", session.budgetMinutes)
                put("plannedOn", session.plannedOn.toString()); put("startedAt", session.startedAt.toString())
                put("items", array(session.items) { item -> JSONObject().apply {
                    put("taskId", item.taskId); put("title", item.title); putNullable("roomName", item.roomName)
                    put("durationMinutes", item.durationMinutes); put("priority", item.priority.name)
                    put("reason", JSONObject().put("kind", item.reason.kind.name)
                        .put("daysOverdue", item.reason.daysOverdue.toString()).put("urgencyScore", item.reason.urgencyScore.toString()))
                    putNullable("completionId", item.completionId)
                } })
                put("excluded", array(session.excluded) { excluded -> JSONObject().apply {
                    put("taskId", excluded.taskId); put("title", excluded.title)
                    put("durationMinutes", excluded.durationMinutes); put("reason", excluded.reason.name)
                } })
            } } ?: JSONObject.NULL)
        }.toString()
    }

    @Throws(IOException::class)
    fun decode(json: String): HomeState {
        try {
            val root = JSONObject(json)
            require(root.strictInt("version") == 1) { "Unsupported save version" }
            require(root.has("activeSession")) { "Missing field: activeSession" }
            val state = HomeState(
                rooms = objects(root.getJSONArray("rooms")) { Room(it.strictString("id"), it.strictString("name")) },
                tasks = objects(root.getJSONArray("tasks")) { task -> HomeTask(
                    task.strictString("id"), task.strictString("title"), task.strictInt("durationMinutes"), task.strictInt("intervalDays"),
                    task.nullableString("roomId"), task.nullableDate("lastDone"), TaskPriority.valueOf(task.strictString("priority")),
                    task.nullableString("lastDoneCompletionId"),
                ) },
                history = objects(root.getJSONArray("history")) { done -> Completion(
                    done.strictString("id"), done.strictString("taskId"), done.strictString("taskTitle"), done.nullableString("roomName"),
                    done.strictString("completedAt").toLong(), LocalDate.parse(done.strictString("localDate")), done.strictString("sessionId"),
                    done.nullableDate("previousLastDone"), done.nullableString("previousLastDoneCompletionId"),
                ) },
                activeSession = if (root.isNull("activeSession")) null else root.getJSONObject("activeSession").let { session -> CleaningSession(
                    session.strictString("id"), session.strictInt("budgetMinutes"), LocalDate.parse(session.strictString("plannedOn")),
                    session.strictString("startedAt").toLong(),
                    objects(session.getJSONArray("items")) { item -> SessionItem(
                        item.strictString("taskId"), item.strictString("title"), item.nullableString("roomName"), item.strictInt("durationMinutes"),
                        TaskPriority.valueOf(item.strictString("priority")), item.getJSONObject("reason").let { reason -> SelectionReason(
                            ReasonKind.valueOf(reason.strictString("kind")), reason.strictString("daysOverdue").toLong(), reason.strictString("urgencyScore").toLong(),
                        ) }, item.nullableString("completionId"),
                    ) },
                    objects(session.getJSONArray("excluded")) { ExcludedTask(it.strictString("taskId"), it.strictString("title"),
                        it.strictInt("durationMinutes"), ExclusionReason.valueOf(it.strictString("reason"))) },
                ) },
                onboardingComplete = root.get("onboardingComplete").let { require(it is Boolean) { "Invalid setup flag" }; it as Boolean },
            )
            validateState(state)
            return state
        } catch (error: Exception) {
            throw IOException("Saved home tasks could not be read", error)
        }
    }

    private fun JSONObject.putNullable(key: String, value: String?) { put(key, value ?: JSONObject.NULL) }
    private fun JSONObject.nullableString(key: String): String? {
        require(has(key)) { "Missing field: $key" }
        return if (isNull(key)) null else strictString(key)
    }
    private fun JSONObject.strictString(key: String): String = get(key).let {
        require(it is String) { "Invalid string: $key" }; it as String
    }
    private fun JSONObject.strictInt(key: String): Int = get(key).let {
        require(it is Number) { "Invalid integer: $key" }
        it.toString().toIntOrNull() ?: throw IllegalArgumentException("Invalid integer: $key")
    }
    private fun JSONObject.nullableDate(key: String): LocalDate? = nullableString(key)?.let(LocalDate::parse)
    private fun <T> array(values: List<T>, encode: (T) -> JSONObject): JSONArray = JSONArray().apply { values.forEach { put(encode(it)) } }
    private fun <T> objects(values: JSONArray, decode: (JSONObject) -> T): List<T> = (0 until values.length()).map { decode(values.getJSONObject(it)) }
}

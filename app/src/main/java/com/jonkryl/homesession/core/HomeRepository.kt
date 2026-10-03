package com.jonkryl.homesession.core

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDate

/** Private app storage, backed by an atomic replace; no storage or network permission is required. */
class AtomicJsonHomePersistence(private val file: AtomicFile) : HomePersistence {
    override fun load(): HomeState? {
        val bytes = try { file.readFully() } catch (_: FileNotFoundException) { return null }
        return HomeJson.decode(bytes.toString(Charsets.UTF_8))
    }

    override fun save(state: HomeState) {
        val bytes = HomeJson.encode(state).toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw IOException("Home tasks could not be saved", error)
        }
    }
}

class HomeRepository(context: Context) {
    private val store = HomeStore(AtomicJsonHomePersistence(AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))))
    val state: HomeState get() = store.state
    val today: LocalDate get() = store.today
    fun initializeEmpty() = store.initializeEmpty()
    fun applyStarterTemplates(language: String) = store.applyStarterTemplates(language)
    fun saveRoom(name: String, id: String? = null): Room = store.saveRoom(name, id)
    fun deleteRoom(id: String) = store.deleteRoom(id)
    fun newTask(title: String, durationMinutes: Int, intervalDays: Int, roomId: String?, lastDone: LocalDate? = null,
                priority: TaskPriority = TaskPriority.NORMAL): HomeTask = store.newTask(title, durationMinutes, intervalDays, roomId, lastDone, priority)
    fun saveTask(task: HomeTask): HomeTask = store.saveTask(task)
    fun deleteTask(id: String) = store.deleteTask(id)
    fun planSession(budgetMinutes: Int): SessionPlan = store.planSession(budgetMinutes)
    fun startSession(budgetMinutes: Int): SessionPlan = store.startSession(budgetMinutes)
    fun markDone(taskId: String): Boolean = store.markDone(taskId)
    fun undoDone(taskId: String): Boolean = store.undoDone(taskId)
    fun finishSession() = store.finishSession()
    fun cancelSession() = store.cancelSession()
    companion object { const val FILE_NAME = "home-session-state-v1.json" }
}

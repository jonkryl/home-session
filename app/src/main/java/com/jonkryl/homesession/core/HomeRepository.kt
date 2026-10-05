package com.jonkryl.homesession.core

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Clock
import java.time.LocalDate

/** Private app storage, backed by an atomic replace; no storage or network permission is required. */
class AtomicJsonHomePersistence(private val file: AtomicFile) : HomePersistence {
    override fun load(): HomeState? {
        // AtomicFile may replace the base with .bak or remove an interrupted .new on read.
        // Preserve every original first, including when the recovered backup is also invalid.
        if (HomeStateRecovery.hasPendingAtomicRecovery(file.baseFile)) {
            HomeStateRecovery(file.baseFile).preserve()
        }
        val bytes = try { file.readFully() } catch (error: FileNotFoundException) {
            if (HomeStateRecovery.savedFiles(file.baseFile).any { it.exists() }) throw error
            return null
        }
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

class HomeRepository(context: Context, clock: Clock? = null) {
    private val store = HomeStore(AtomicJsonHomePersistence(AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))), clock)
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
    companion object {
        const val FILE_NAME = "home-session-state-v1.json"

        /** Only call after the user confirms replacing an unreadable schedule with an empty one. */
        fun restartEmpty(context: Context): File {
            val base = File(context.applicationContext.filesDir, FILE_NAME)
            return HomeStateRecovery(base).restartEmpty {
                val state = HomeState()
                AtomicJsonHomePersistence(AtomicFile(base)).save(state)
                if (!base.readBytes().contentEquals(HomeJson.encode(state).toByteArray(Charsets.UTF_8))) {
                    throw IOException("New home state could not be verified")
                }
            }
        }
    }
}

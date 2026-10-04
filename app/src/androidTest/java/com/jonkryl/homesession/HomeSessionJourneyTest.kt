package com.jonkryl.homesession

import android.graphics.Bitmap
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.jonkryl.homesession.core.HomeRepository
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.anyOf
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.instanceOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Writes only through the visible product UI; the next runner starts after am force-stop. */
@RunWith(AndroidJUnit4::class)
class HomeSessionJourneyTest {
    @Test
    fun createsRoomsEditableTasksAndARealPartlyCompletedSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf(HomeRepository.FILE_NAME, HomeRepository.FILE_NAME + ".bak", HomeRepository.FILE_NAME + ".new")
            .forEach { context.deleteFile(it) }
        context.getSharedPreferences("ad_privacy", 0).edit().clear().commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue("First launch must ask for an actual ad privacy choice", device.wait(
                Until.hasObject(By.text("Contextual ads")), 10_000))
            onView(anyOf(withText("Contextual ads"), withText("Контекстная реклама"))).perform(scrollTo(), click())
            val privacy = context.getSharedPreferences("ad_privacy", 0)
            assertTrue(privacy.getBoolean("choice_set", false))
            assertFalse("Contextual choice keeps personalization off", privacy.getBoolean("personalized", true))
            tap(R.id.onboarding_empty)
            tap(R.id.home_rooms)
            tap(R.id.add_room)
            onView(withId(R.id.room_name)).perform(replaceText("Study"), closeSoftKeyboard())
            tap(R.id.room_save)
            assertEquals("Study", savedState().rooms.single().name)
            tap(R.id.toolbar_back)
            tap(R.id.home_tasks)
            createTask("Clear table", 6)
            createTask("Sweep floor", 8)
            createTask("Clean high shelf", 20)
            assertEquals(3, savedState().tasks.size)
            assertTrue(savedState().tasks.all { it.lastDone == null && it.intervalDays == 7 })
            assertTrue(savedState().tasks.all { it.roomId == savedState().rooms.single().id })
            tap(R.id.toolbar_back)
            tap(R.id.home_pick_15)
            onView(withId(R.id.plan_total)).check(matches(withText(org.hamcrest.CoreMatchers.containsString("14"))))
            onView(withId(R.id.plan_excluded)).check { view, error ->
                if (error != null) throw error
                val excludedText = descendantsText(requireNotNull(view))
                assertTrue("A 20-minute task is shown honestly as separate", excludedText.contains("Clean high shelf"))
                assertTrue("The reason explains the longer duration", excludedText.contains(context.getString(R.string.excluded_too_long, 20)))
            }
            onView(withId(R.id.main_scroll)).check { view, error ->
                if (error != null) throw error
                assertTrue("The selection explains why tasks are due", descendantsText(requireNotNull(view)).contains(context.getString(R.string.reason_never)))
            }
            assertNull("Planning must not persist an active session", savedState().activeSession)
            assertTrue("Planning must leave completion history unchanged", savedState().history.isEmpty())
            tap(R.id.plan_start)
            val session = requireNotNull(savedState().activeSession)
            assertEquals(15, session.budgetMinutes)
            assertEquals(14, session.totalMinutes)
            assertEquals(setOf("Clear table", "Sweep floor"), session.items.map { item -> item.title }.toSet())
            onView(withContentDescription("Complete: Clear table")).perform(scrollTo(), click())
            assertEquals(1, savedState().history.size)
            assertNotNull(savedState().tasks.single { task -> task.title == "Clear table" }.lastDone)
            onView(withContentDescription("Undo: Clear table")).perform(scrollTo(), click())
            assertTrue(savedState().history.isEmpty())
            assertNull(savedState().tasks.single { task -> task.title == "Clear table" }.lastDone)
            onView(withContentDescription("Complete: Clear table")).perform(scrollTo(), click())
            assertEquals(1, savedState().history.size)
            assertEquals(1, requireNotNull(savedState().activeSession).completedCount)
            assertNull("A pending session item must have no false lastDone", savedState().tasks.single { task -> task.title == "Sweep floor" }.lastDone)
            saveScreenshot("01-session-api-${Build.VERSION.SDK_INT}.png")
            tap(R.id.toolbar_back)
            onView(withId(R.id.home_resume)).perform(scrollTo()).check(matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
        }
    }

    private fun createTask(title: String, duration: Int) {
        tap(R.id.add_task)
        onView(withId(R.id.task_title)).perform(replaceText(title), closeSoftKeyboard())
        onView(withId(R.id.task_duration)).perform(scrollTo(), replaceText(duration.toString()), closeSoftKeyboard())
        onView(withId(R.id.task_interval)).perform(scrollTo(), replaceText("7"), closeSoftKeyboard())
        onView(withId(R.id.task_room)).perform(scrollTo(), click())
        onData(allOf(instanceOf(String::class.java), equalTo("Study"))).inRoot(isPlatformPopup()).perform(click())
        onView(withId(R.id.task_room)).check(matches(androidx.test.espresso.matcher.ViewMatchers.withSpinnerText("Study")))
        tap(R.id.task_save)
    }
}

internal fun tap(id: Int) {
    onView(withId(id)).perform(scrollTo(), click())
}
internal fun savedState() = HomeRepository(InstrumentationRegistry.getInstrumentation().targetContext).state

internal fun saveScreenshot(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")
    check(directory.mkdirs() || directory.isDirectory)
    val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    File(directory, name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    screenshot.recycle()
}

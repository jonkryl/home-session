package com.jonkryl.homesession

import android.os.Build
import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jonkryl.homesession.core.HomeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Real Activity transitions with a controlled repository calendar, without changing device time. */
@RunWith(AndroidJUnit4::class)
class DateResumeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var clock: AdvancingClock
    private lateinit var repository: HomeRepository

    @Before
    fun guardOwnedDemoEmulatorBeforeFixtures() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("ownedFirstMacEmulator"))
        assertEquals("com.jonkryl.homesession", context.packageName)
        assertTrue(BuildConfig.DEBUG)
        assertEquals("demo-banner-yandex", BuildConfig.YANDEX_BANNER_ID)
        assertEquals(36, Build.VERSION.SDK_INT)
        assertTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        assertTrue(Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") ||
            Build.FINGERPRINT.contains("/sdk_gphone64_arm64/"))
        listOf(HomeRepository.FILE_NAME, HomeRepository.FILE_NAME + ".bak", HomeRepository.FILE_NAME + ".new")
            .forEach { name -> assertTrue(!context.getFileStreamPath(name).exists() || context.deleteFile(name)) }
        assertTrue(context.getSharedPreferences("ad_privacy", 0).edit()
            .putBoolean("choice_set", true).putBoolean("personalized", false).commit())
        clock = AdvancingClock(LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant())
        repository = HomeRepository(context, clock)
        repository.initializeEmpty()
        repository.newTask("Refresh daily task", 5, 1, null, repository.today)
    }

    @Test
    fun returningNextDayRefreshesHomeSummaryWithoutWritingTasks() {
        val saved = storedBytes()
        withCalendarActivity { scenario ->
            tap(R.id.home_tasks)
            tap(R.id.toolbar_back)
            onView(withText(context.getString(R.string.home_summary, 0, 1))).check(matches(withText(context.getString(R.string.home_summary, 0, 1))))
            advanceWhileStopped(scenario)
            onView(withText(context.getString(R.string.home_summary, 1, 0))).check(matches(withText(context.getString(R.string.home_summary, 1, 0))))
            assertEquals(saved, storedBytes())
        }
    }

    @Test
    fun returningNextDayRefreshesRetainedOverdueFilter() {
        val saved = storedBytes()
        withCalendarActivity { scenario ->
            tap(R.id.home_tasks)
            tap(R.id.tasks_filter_overdue)
            onView(withId(R.id.tasks_list)).check { view, error ->
                if (error != null) throw error
                assertTrue(!descendantsText(requireNotNull(view)).contains("Refresh daily task"))
            }
            advanceWhileStopped(scenario)
            onView(withId(R.id.tasks_list)).check { view, error ->
                if (error != null) throw error
                assertTrue(descendantsText(requireNotNull(view)).contains("Refresh daily task"))
            }
            scenario.onActivity { activity -> assertTrue(activity.findViewById<android.view.View>(R.id.tasks_filter_overdue).isSelected) }
            assertEquals(saved, storedBytes())
        }
    }

    @Test
    fun returningNextDayReplacesEmptyPreviewWithoutStartingSession() {
        val saved = storedBytes()
        withCalendarActivity { scenario ->
            tap(R.id.home_pick_15)
            onView(withId(R.id.plan_empty)).check { view, error ->
                if (error != null) throw error
                assertTrue(view != null)
            }
            advanceWhileStopped(scenario)
            onView(withId(R.id.plan_empty)).check(doesNotExist())
            onView(withId(R.id.plan_start)).check { view, error ->
                if (error != null) throw error
                assertTrue(view != null)
            }
            onView(withId(R.id.plan_total)).check(matches(withText(context.getString(R.string.plan_total, 5, 15))))
            assertEquals(saved, storedBytes())
            assertEquals(null, HomeRepository(context, clock).state.activeSession)
        }
    }

    @Test
    fun returningNextDayPreservesUnsavedTaskFieldsAndControls() {
        val saved = storedBytes()
        withCalendarActivity { scenario ->
            tap(R.id.home_tasks)
            tap(R.id.add_task)
            onView(withId(R.id.task_title)).perform(replaceText("Unsaved calendar draft"), closeSoftKeyboard())
            var titleBefore: EditText? = null
            scenario.onActivity { activity ->
                titleBefore = activity.findViewById(R.id.task_title)
                activity.findViewById<EditText>(R.id.task_duration).setText("23")
                activity.findViewById<EditText>(R.id.task_interval).setText("11")
            }
            advanceWhileStopped(scenario)
            scenario.onActivity { activity ->
                assertSame(titleBefore, activity.findViewById<EditText>(R.id.task_title))
                assertEquals("Unsaved calendar draft", activity.findViewById<EditText>(R.id.task_title).text.toString())
                assertEquals("23", activity.findViewById<EditText>(R.id.task_duration).text.toString())
                assertEquals("11", activity.findViewById<EditText>(R.id.task_interval).text.toString())
            }
            assertEquals(saved, storedBytes())
        }
    }

    @Test
    fun returningNextDayPreservesTheExactActiveSessionAndHistory() {
        repository.newTask("Already due task", 6, 7, null)
        val session = repository.startSession(15)
        assertEquals(1, session.items.size)
        assertTrue(repository.markDone(session.items.single().taskId))
        assertEquals(1, repository.state.history.size)
        val saved = storedBytes()
        val state = repository.state
        withCalendarActivity { scenario ->
            tap(R.id.home_resume)
            var listBefore: android.view.View? = null
            scenario.onActivity { activity -> listBefore = activity.findViewById(R.id.session_list) }
            advanceWhileStopped(scenario)
            scenario.onActivity { activity -> assertSame(listBefore, activity.findViewById<android.view.View>(R.id.session_list)) }
            assertEquals(state, HomeRepository(context, clock).state)
            assertEquals(saved, storedBytes())
        }
    }

    private fun withCalendarActivity(block: (ActivityScenario<MainActivity>) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(activity, repository)
            }
            block(scenario)
        }
    }

    private fun advanceWhileStopped(scenario: ActivityScenario<MainActivity>) {
        scenario.moveToState(Lifecycle.State.CREATED)
        clock.advanceDay()
        scenario.moveToState(Lifecycle.State.RESUMED)
    }

    private fun storedBytes() = context.getFileStreamPath(HomeRepository.FILE_NAME).readText()

    private class AdvancingClock(@Volatile private var value: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(value, zone)
        override fun instant(): Instant = value
        fun advanceDay() { value = value.plusSeconds(86_400) }
    }
}

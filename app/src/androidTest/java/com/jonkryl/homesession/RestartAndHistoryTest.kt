package com.jonkryl.homesession

import android.content.res.Configuration
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Invoked by CI in a fresh app process after force-stop, with no direct state seeding. */
@RunWith(AndroidJUnit4::class)
class RestartAndHistoryTest {
    @Test
    fun restoresTheExactPartialSessionAndShowsDatedHistoryInBothLanguages() {
        val restored = savedState()
        assertEquals(3, restored.tasks.size)
        assertEquals(1, restored.history.size)
        assertEquals("Clear table", restored.history.single().taskTitle)
        assertNotNull(restored.history.single().localDate)
        assertEquals(1, requireNotNull(restored.activeSession).completedCount)
        assertNull(restored.tasks.single { it.title == "Sweep floor" }.lastDone)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            tap(R.id.home_resume)
            onView(withContentDescription("Undo: Clear table")).perform(scrollTo()).check(matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
            onView(withContentDescription("Complete: Sweep floor")).perform(scrollTo()).check(matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
            // Undo must remain operational after process death, then restore the deliberate completion.
            onView(withContentDescription("Undo: Clear table")).perform(scrollTo(), click())
            assertTrue(savedState().history.isEmpty())
            onView(withContentDescription("Complete: Clear table")).perform(scrollTo(), click())
            val beforeBack = savedState()
            androidx.test.espresso.Espresso.pressBack()
            onView(withId(R.id.home_resume)).perform(scrollTo())
                .check(matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
            val afterBack = savedState()
            assertEquals("System back pauses the exact session", beforeBack.activeSession, afterBack.activeSession)
            assertEquals("System back preserves deliberate completion marks", beforeBack.history, afterBack.history)
            assertNull(afterBack.tasks.single { it.title == "Sweep floor" }.lastDone)
            for (language in listOf("en", "ru")) {
                scenario.onActivity { activity ->
                    val configuration = Configuration(activity.resources.configuration)
                    configuration.setLocale(Locale(language))
                    @Suppress("DEPRECATION")
                    activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
                    @Suppress("DEPRECATION")
                    activity.applicationContext.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
                }
                scenario.recreate()
                tap(R.id.home_history)
                onView(withId(R.id.history_list)).check { view, error ->
                    if (error != null) throw error
                    val text = descendantsText(requireNotNull(view))
                    assertTrue("Completed task appears in dated history", text.contains("Clear table"))
                    assertTrue("The history shows a calendar year", text.contains(savedState().history.single().localDate.year.toString()))
                    assertTrue("Pending tasks do not appear as completed", !text.contains("Sweep floor"))
                }
                saveScreenshot("02-history-${language}-api-${Build.VERSION.SDK_INT}.png")
                tap(R.id.toolbar_back)
            }
        }
    }
}

internal fun descendantsText(view: android.view.View): String {
    val own = (view as? android.widget.TextView)?.text?.toString().orEmpty()
    if (view !is android.view.ViewGroup) return own
    return own + "\n" + (0 until view.childCount).joinToString("\n") { descendantsText(view.getChildAt(it)) }
}

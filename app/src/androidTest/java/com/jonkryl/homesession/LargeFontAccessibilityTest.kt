package com.jonkryl.homesession

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.startsWith

/** CI changes the actual Android setting to 2.0 before launching this fresh process on 24 and 36. */
@RunWith(AndroidJUnit4::class)
class LargeFontAccessibilityTest {
    @Test
    fun taskCompletionUndoAndCancellationRemainReachableAtTwoHundredPercent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("Use the real 200% system setting", 2.0f, activity.resources.configuration.fontScale, 0.05f)
            }
            tap(R.id.home_resume)
            onView(withContentDescription("Complete: Sweep floor")).perform(scrollTo()).check { view, error ->
                if (error != null) throw error
                val control = requireNotNull(view)
                val minimum = control.resources.displayMetrics.density * 48f
                assertTrue("Task action is at least 48 dp tall", control.height >= minimum)
                assertTrue("Task action is at least 48 dp wide", control.width >= minimum)
                assertTextFits(control as TextView)
            }.perform(click())
            assertEquals(2, savedState().history.size)
            onView(withContentDescription("Undo: Sweep floor")).perform(scrollTo(), click())
            assertEquals(1, savedState().history.size)
            assertNull(savedState().tasks.single { it.title == "Sweep floor" }.lastDone)
            // Preserve the real completion timestamp before a failed assertion closes ActivityScenario.
            onView(withText(startsWith("Done: "))).perform(scrollTo())
            saveScreenshot("03-font-200-api-${Build.VERSION.SDK_INT}.png")
            onView(withId(R.id.session_list)).check { view, error ->
                if (error != null) throw error
                textViews(requireNotNull(view)).filter { it.visibility == View.VISIBLE && it.text.isNotEmpty() }
                    .forEach(::assertTextFits)
            }
            tap(R.id.session_cancel)
            onView(withId(android.R.id.button1)).perform(click())
            assertNull(savedState().activeSession)
            assertEquals("Cancellation retains only the explicitly completed task", 1, savedState().history.size)
            assertNull(savedState().tasks.single { it.title == "Sweep floor" }.lastDone)
        }
    }

    private fun assertTextFits(text: TextView) {
        val layout = requireNotNull(text.layout)
        val availableWidth = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
        val availableHeight = text.height - text.compoundPaddingTop - text.compoundPaddingBottom
        assertTrue("Text is not vertically clipped: ${text.text}", layout.height <= availableHeight + 1)
        for (line in 0 until layout.lineCount) {
            val visibleWidth = layout.getLineMax(line)
            val diagnostic = "line=$line visibleWidth=$visibleWidth totalWidth=${layout.getLineWidth(line)} " +
                "availableWidth=$availableWidth visibleEnd=${layout.getLineVisibleEnd(line)} text=${text.text}"
            android.util.Log.i("FontLayout", diagnostic)
            // getLineWidth includes the invisible whitespace at a wrapped line end; getLineMax measures visible text.
            assertTrue("Text is not horizontally clipped: $diagnostic", visibleWidth <= availableWidth + 1f)
            assertEquals("Text is not ellipsized: ${text.text}", 0, layout.getEllipsisCount(line))
        }
    }

    private fun textViews(view: View): List<TextView> = when (view) {
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        is TextView -> listOf(view)
        else -> emptyList()
    }
}

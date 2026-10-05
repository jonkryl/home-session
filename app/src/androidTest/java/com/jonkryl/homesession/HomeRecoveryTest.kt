package com.jonkryl.homesession

import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jonkryl.homesession.core.AtomicJsonHomePersistence
import com.jonkryl.homesession.core.HomeJson
import com.jonkryl.homesession.core.HomeRepository
import com.jonkryl.homesession.core.HomeState
import com.jonkryl.homesession.core.HomeStateRecovery
import com.jonkryl.homesession.core.Room
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/** This suite may run only in the controller's explicitly owned first-Mac demo emulator. */
@RunWith(AndroidJUnit4::class)
class HomeRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val base get() = context.getFileStreamPath(HomeRepository.FILE_NAME)
    private val archiveRoot get() = File(context.filesDir, HomeStateRecovery.DIRECTORY)
    private val corrupt = byteArrayOf(123, 34, -1, 0, 10)
    private var previousArchives = emptySet<String>()
    private var guarded = false

    @Before fun guardBeforeAnyFixtureChanges() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("ownedFirstMacEmulator"))
        assertEquals("com.jonkryl.homesession", context.packageName)
        assertTrue(BuildConfig.DEBUG)
        assertEquals("demo-banner-yandex", BuildConfig.YANDEX_BANNER_ID)
        assertEquals(36, Build.VERSION.SDK_INT)
        assertTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        assertTrue(Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") ||
            Build.FINGERPRINT.contains("/sdk_gphone64_arm64/"))
        guarded = true
        previousArchives = archiveRoot.listFiles()?.map { it.name }?.toSet().orEmpty()
        HomeStateRecovery.savedFiles(base).forEach { assertTrue(!it.exists() || it.delete()) }
        assertTrue(context.getSharedPreferences("ad_privacy", 0).edit()
            .putBoolean("choice_set", true).putBoolean("personalized", false).commit())
    }

    @After fun cleanOnlyThisSuiteFixtures() {
        if (!guarded) return
        HomeStateRecovery.savedFiles(base).forEach { it.delete() }
        archiveRoot.listFiles()?.filter { it.name !in previousArchives }?.forEach { it.deleteRecursively() }
    }

    @Test fun validBaseLoadsWithoutRecoveryCopyOrChangingBytes() {
        val state = HomeState(rooms = listOf(Room("room-1", "Existing room")), onboardingComplete = true)
        val bytes = HomeJson.encode(state).toByteArray()
        base.writeBytes(bytes)
        assertEquals(state, HomeRepository(context).state)
        assertArrayEquals(bytes, base.readBytes())
        assertTrue(newArchives().isEmpty())
    }

    @Test fun validAtomicBackupStillLoadsAndOriginalFilesRemainRecoverable() {
        base.writeBytes(corrupt)
        val expected = HomeState(rooms = listOf(Room("room-1", "Backup room")), onboardingComplete = true)
        val backup = HomeJson.encode(expected).toByteArray()
        File(base.path + ".bak").writeBytes(backup)
        assertEquals(expected, HomeRepository(context).state)
        assertArrayEquals(backup, base.readBytes())
        val archive = newArchives().single()
        assertArrayEquals(corrupt, archive.resolve(base.name).readBytes())
        assertArrayEquals(backup, archive.resolve(base.name + ".bak").readBytes())
    }

    @Test fun invalidAtomicBackupDoesNotLoseBaseBackupOrInterruptedWriteOnErrorPath() {
        base.writeBytes(corrupt)
        val backup = "{\"version\":999}".toByteArray()
        val pending = byteArrayOf(4, 5, 6)
        File(base.path + ".bak").writeBytes(backup)
        File(base.path + ".new").writeBytes(pending)
        assertThrows(IOException::class.java) { HomeRepository(context) }
        val archive = newArchives().single()
        assertArrayEquals(corrupt, archive.resolve(base.name).readBytes())
        assertArrayEquals(backup, archive.resolve(base.name + ".bak").readBytes())
        assertArrayEquals(pending, archive.resolve(base.name + ".new").readBytes())
    }

    @Test fun retryAndRecreateKeepTruncatedOriginalWithoutStartingEmptySchedule() {
        base.writeBytes(corrupt)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.recovery_retry)).perform(click())
            onView(withId(R.id.recovery_start_empty)).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withId(R.id.recovery_start_empty)).check(matches(isDisplayed()))
            assertArrayEquals(corrupt, base.readBytes())
            assertTrue(newArchives().isEmpty())
        }
    }

    @Test fun cancellingFreshStartDoesNotCopyOrReplaceOriginal() {
        base.writeBytes(corrupt)
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.recovery_start_empty)).perform(click())
            onView(withText(context.getString(R.string.cancel))).perform(click())
            assertArrayEquals(corrupt, base.readBytes())
            assertTrue(newArchives().isEmpty())
            onView(withId(R.id.recovery_start_empty)).check(matches(isDisplayed()))
        }
    }

    @Test fun confirmedFreshStartCopiesOriginalBeforeNewScheduleAndSurvivesRelaunch() {
        base.writeBytes(corrupt)
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.recovery_start_empty)).perform(click())
            onView(withId(android.R.id.button1)).perform(click())
            await { runCatching { onView(withId(R.id.onboarding_empty)).check(matches(isDisplayed())) }.isSuccess }
            assertEquals(HomeState(), HomeRepository(context).state)
            assertArrayEquals(corrupt, newArchives().single().resolve(base.name).readBytes())
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.onboarding_empty)).check(matches(isDisplayed()))
            assertEquals(HomeState(), HomeRepository(context).state)
            assertArrayEquals(corrupt, newArchives().single().resolve(base.name).readBytes())
        }
    }

    @Test fun atomicWriteFailureLeavesOriginalAndVerifiedCopyWithoutReportingSuccess() {
        base.writeBytes(corrupt)
        val pending = File(base.path + ".new")
        try {
            assertThrows(IOException::class.java) {
                HomeStateRecovery(base).restartEmpty {
                    // Force the actual AtomicFile writer to fail after the verified copy exists.
                    assertTrue(pending.mkdir())
                    AtomicJsonHomePersistence(AtomicFile(base)).save(HomeState())
                }
            }
            assertArrayEquals(corrupt, base.readBytes())
            assertArrayEquals(corrupt, newArchives().single().resolve(base.name).readBytes())
        } finally { pending.delete() }
    }

    @Test fun completionIsConsumedBeforeRecreateAndLaterLoadFailureStaysOpen() {
        base.writeBytes(corrupt)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.recovery_start_empty)).perform(click())
            onView(withId(android.R.id.button1)).perform(click())
            await { runCatching { onView(withId(R.id.onboarding_empty)).check(matches(isDisplayed())) }.isSuccess }
            scenario.onActivity { activity ->
                val recovery = ViewModelProvider(activity).get(HomeRecoveryViewModel::class.java)
                assertEquals(HomeRecoveryViewModel.Status.IDLE, recovery.status.value)
                assertTrue(!recovery.consumeCompletion())
            }
            // A new read error with the retained ViewModel must not replay an old completion.
            base.writeBytes(corrupt)
            scenario.recreate()
            onView(withId(R.id.recovery_retry)).check(matches(isDisplayed()))
            var failedActivity: MainActivity? = null
            scenario.onActivity { failedActivity = it }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertSame(failedActivity, activity)
                assertEquals(HomeRecoveryViewModel.Status.IDLE,
                    ViewModelProvider(activity).get(HomeRecoveryViewModel::class.java).status.value)
            }
            onView(withId(R.id.recovery_start_empty)).check(matches(isDisplayed()))
            assertArrayEquals(corrupt, base.readBytes())
        }
    }

    private fun newArchives(): List<File> = archiveRoot.listFiles()?.filter { it.name !in previousArchives }.orEmpty()
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Recovery did not reach the expected UI", condition())
    }
}

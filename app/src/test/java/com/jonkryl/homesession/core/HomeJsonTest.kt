package com.jonkryl.homesession.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HomeJsonTest {
    @Test fun roundTripPreservesUnicodeNullDatesExclusionsAndPartialSession() {
        val store = testStore()
        store.initializeEmpty()
        val room = store.saveRoom("Ванная 🚿")
        val small = store.newTask("Протереть кран", 5, 7, room.id)
        store.newTask("Большая уборка", 31, 30, null)
        store.startSession(15)
        store.markDone(small.id)
        assertEquals(store.state, HomeJson.decode(HomeJson.encode(store.state)))
    }

    @Test fun emptyStateRoundTripsWithoutGuessingSetupOrCompletion() {
        assertEquals(HomeState(), HomeJson.decode(HomeJson.encode(HomeState())))
    }

    @Test(expected = IOException::class) fun unsupportedVersionIsRejected() {
        HomeJson.decode(JSONObject(HomeJson.encode(HomeState())).put("version", 2).toString())
    }

    @Test(expected = IOException::class) fun malformedDataIsRejectedInsteadOfErasingUserData() {
        HomeJson.decode("{broken")
    }

    @Test(expected = IOException::class) fun missingRequiredArrayIsRejected() {
        val json = JSONObject(HomeJson.encode(HomeState()))
        json.remove("tasks")
        HomeJson.decode(json.toString())
    }

    @Test(expected = IOException::class) fun missingSessionFieldCannotSilentlyDiscardResumeState() {
        val store = testStore()
        store.newTask("A", 5, 7, null)
        store.startSession(5)
        val json = JSONObject(HomeJson.encode(store.state))
        json.remove("activeSession")
        HomeJson.decode(json.toString())
    }

    @Test(expected = IOException::class) fun fractionalDurationIsRejectedRatherThanRounded() {
        val store = testStore()
        store.newTask("A", 5, 7, null)
        val root = JSONObject(HomeJson.encode(store.state))
        root.getJSONArray("tasks").getJSONObject(0).put("durationMinutes", 5.5)
        HomeJson.decode(root.toString())
    }

    @Test(expected = IOException::class) fun stringFlagIsRejectedRatherThanGuessed() {
        val root = JSONObject(HomeJson.encode(HomeState())).put("onboardingComplete", "false")
        HomeJson.decode(root.toString())
    }

    @Test(expected = IOException::class) fun completionSourceCannotBelongToAnotherTask() {
        val store = testStore()
        val a = store.newTask("A", 5, 7, null)
        store.newTask("B", 5, 7, null)
        store.startSession(15)
        store.markDone(a.id)
        val root = JSONObject(HomeJson.encode(store.state))
        val source = root.getJSONArray("tasks").getJSONObject(0)
        val other = root.getJSONArray("tasks").getJSONObject(1)
        other.put("lastDoneCompletionId", source.get("lastDoneCompletionId"))
        other.put("lastDone", source.get("lastDone"))
        HomeJson.decode(root.toString())
    }

    @Test(expected = IOException::class) fun corruptSessionAboveBudgetIsRejected() {
        val store = testStore()
        store.newTask("A", 5, 7, null)
        store.startSession(5)
        val root = JSONObject(HomeJson.encode(store.state))
        root.getJSONObject("activeSession").getJSONArray("items").getJSONObject(0).put("durationMinutes", 6)
        HomeJson.decode(root.toString())
    }

    @Test(expected = IOException::class) fun danglingCompletionDoesNotCreateFalseLastDone() {
        val store = testStore()
        val task = store.newTask("A", 5, 7, null)
        store.startSession(5)
        store.markDone(task.id)
        val root = JSONObject(HomeJson.encode(store.state))
        root.getJSONArray("history").remove(0)
        HomeJson.decode(root.toString())
    }
}

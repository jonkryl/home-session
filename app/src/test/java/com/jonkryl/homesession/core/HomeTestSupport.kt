package com.jonkryl.homesession.core

import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal class JsonMemoryPersistence : HomePersistence {
    var json: String? = null
    var writes = 0
    var failWrites = false
    override fun load(): HomeState? = json?.let(HomeJson::decode)
    override fun save(state: HomeState) {
        if (failWrites) throw IOException("Simulated full storage")
        json = HomeJson.encode(state)
        writes++
    }
}

internal class MutableHomeClock(var instantValue: Instant, private val zoneValue: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun getZone(): ZoneId = zoneValue
    override fun withZone(zone: ZoneId): Clock = MutableHomeClock(instantValue, zone)
    override fun instant(): Instant = instantValue
}

internal fun testStore(persistence: JsonMemoryPersistence = JsonMemoryPersistence(),
                       clock: Clock = Clock.fixed(Instant.parse("2026-10-03T10:00:00Z"), ZoneOffset.UTC)): HomeStore {
    var next = 0
    return HomeStore(persistence, clock) { "id-${++next}" }
}

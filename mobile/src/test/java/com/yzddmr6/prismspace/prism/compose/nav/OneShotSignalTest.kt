package com.yzddmr6.prismspace.prism.compose.nav

import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotSignalTest {

    @Test fun requestIsDeliveredOnceAndNeverReplayedToALaterCollector() = runBlocking {
        val signal = OneShotSignal<String>()
        signal.emit("u23")
        assertEquals("u23", signal.requests.first())
        // A recreated Activity starts a new collection: the consumed request must not come back.
        assertNull(withTimeoutOrNull(100) { signal.requests.first() })
    }

    @Test fun pendingRequestWaitsForTheNextCollectorAndNewerOneWins() = runBlocking {
        val signal = OneShotSignal<String>()
        signal.emit("old")
        signal.emit("new")
        assertEquals("new", signal.requests.first())
        assertNull(withTimeoutOrNull(100) { signal.requests.first() })
    }

    @Test fun pickerSignalIsOneShotAndValidatedBeforeNavigating() {
        val signals = source("prism/compose/nav/AppLaunchSignals.kt")
        assertTrue(signals.contains("OneShotSignal<SystemAppPickerRequest>()"))
        assertFalse(signals.contains("MutableStateFlow<SystemAppPickerRequest"))

        val host = source("prism/compose/nav/PrismNavHost.kt")
        val collector = host.substringAfter("AppLaunchSignals.openSystemAppPicker.collect").substringBefore("\n    }")
        assertTrue(collector.indexOf("isManagedSpace(request.userId)") in 0 until collector.indexOf("openSystemAppPicker("))
        assertTrue(collector.indexOf("awaitGraph(navController)") in 0 until collector.indexOf("openSystemAppPicker("))
    }

    private fun source(path: String): String = listOf(
        File("mobile/src/main/java/com/yzddmr6/prismspace/$path"),
        File("src/main/java/com/yzddmr6/prismspace/$path"),
    ).first(File::isFile).readText()
}

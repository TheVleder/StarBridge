package org.starbridge.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.starbridge.core.net.Beacon
import org.starbridge.core.net.BrainInfo
import org.starbridge.core.net.Peer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BeaconTest {
    @Test
    fun datagramRoundTripAndHostileInput() {
        val i = BrainInfo("abc123", "android", "Android · P20", 8080, true, "SLT", host = "127.0.0.1")
        assertEquals(i, Beacon.decode(Beacon.encode(i)))
        // Never the access key in the datagram.
        assertFalse(Beacon.encode(i).contains("\"k\""))
        assertNull(Beacon.decode("not json"))
        assertNull(Beacon.decode("""{"starbridge":2,"id":"x","kind":"pc","http":8080}"""))
        assertNull(Beacon.decode("""{"starbridge":1,"id":"x","kind":"pc","http":99999}"""))
        // A host that is not a plain IPv4 address is ignored (the sender's address is used).
        assertNull(Beacon.decode("""{"starbridge":1,"id":"x","kind":"pc","http":80,"host":"evil.example/x"}""")?.host)
        assertEquals("http://10.0.0.5:8080", Peer(i.copy(host = null), "10.0.0.5", 0).baseUrl)
    }

    @Test
    fun twoBrainsFindEachOtherAndSeeWhoHasTheTelescope() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = 47_900 + (System.nanoTime() % 50).toInt()
        val pcSees = java.util.concurrent.atomic.AtomicReference<List<Peer>>(emptyList())
        val androidSees = java.util.concurrent.atomic.AtomicReference<List<Peer>>(emptyList())
        try {
            Beacon(scope, { BrainInfo("pc1", "pc", "PC", 8080, telescope = false) }, { pcSees.set(it) }, port, intervalMs = 200).start()
            Beacon(scope, { BrainInfo("and1", "android", "Android", 8081, telescope = true) }, { androidSees.set(it) }, port, intervalMs = 200).start()
            withTimeoutOrNull(5_000) { while (pcSees.get().isEmpty() || androidSees.get().isEmpty()) delay(50) }
            val android = assertNotNull(pcSees.get().firstOrNull { it.info.id == "and1" }, "the PC did not see the Android")
            assertTrue(android.info.telescope)
            assertTrue(android.baseUrl.endsWith(":8081"))
            assertTrue(androidSees.get().any { it.info.id == "pc1" && !it.info.telescope })
            // A brain never lists itself.
            assertTrue(pcSees.get().none { it.info.id == "pc1" })
        } finally {
            scope.cancel()
        }
    }
}

package ai.openclaw.app.wear

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearRelayLogTest {

  @After
  fun tearDown() {
    // Singleton state — reset so tests don't leak into each other.
    WearRelayLog.clear()
  }

  @Test
  fun infoAppendsEntryWithMatchingKindAndFields() {
    WearRelayLog.info("chat", "hello world")

    val entries = WearRelayLog.entries.value
    assertEquals(1, entries.size)
    val entry = entries.single()
    assertEquals(WearRelayLogKind.Info, entry.kind)
    assertEquals("chat", entry.tag)
    assertEquals("hello world", entry.message)
    assertTrue("timestamp should be non-empty", entry.timestamp.isNotEmpty())
  }

  @Test
  fun severityHelpersMapToMatchingKinds() {
    WearRelayLog.incoming("ping", "from watch")
    WearRelayLog.outgoing("ping", "to watch")
    WearRelayLog.warn("chat", "partial timeout")
    WearRelayLog.error("chat", "gateway down")

    val kinds = WearRelayLog.entries.value.map { it.kind }
    assertEquals(
      listOf(
        WearRelayLogKind.In,
        WearRelayLogKind.Out,
        WearRelayLogKind.Warn,
        WearRelayLogKind.Error,
      ),
      kinds,
    )
  }

  @Test
  fun ringBufferEvictsOldestBeyondMaxEntries() {
    repeat(60) { WearRelayLog.info("chat", "msg $it") }

    val entries = WearRelayLog.entries.value
    assertEquals(50, entries.size)
    // First retained entry should be msg 10 (10..59 kept).
    assertEquals("msg 10", entries.first().message)
    assertEquals("msg 59", entries.last().message)
  }

  @Test
  fun beginAndEndBalanceInFlightCounter() {
    assertEquals(0, WearRelayLog.inFlight.value)
    WearRelayLog.begin()
    WearRelayLog.begin()
    assertEquals(2, WearRelayLog.inFlight.value)
    WearRelayLog.end()
    assertEquals(1, WearRelayLog.inFlight.value)
    WearRelayLog.end()
    WearRelayLog.end() // over-decrement is a no-op, not a negative value
    assertEquals(0, WearRelayLog.inFlight.value)
  }

  @Test
  fun clearEmptiesEntriesButLeavesInFlightUntouched() {
    WearRelayLog.info("ping", "a")
    WearRelayLog.begin()

    WearRelayLog.clear()

    assertEquals(0, WearRelayLog.entries.value.size)
    assertEquals(1, WearRelayLog.inFlight.value)

    WearRelayLog.end()
  }

  @Test
  fun shortNodeTruncatesLongIds() {
    assertEquals("abcdef…", shortNode("abcdef1234567890"))
    assertEquals("short", shortNode("short"))
  }
}

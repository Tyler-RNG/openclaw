package ai.openclaw.app.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarMarkerParserTest {

  @Test
  fun oneShotStripsMarkerOnOwnLine() {
    val r = parseAvatarMarkers("Hello\n[avatar:happy]\nworld\n")
    assertEquals("Hello\nworld\n", r.cleanedText)
    assertEquals(listOf(AvatarMarker("happy")), r.markers)
  }

  @Test
  fun passesThroughWithNoMarkers() {
    val r = parseAvatarMarkers("no marker here.\nsecond line\n")
    assertEquals("no marker here.\nsecond line\n", r.cleanedText)
    assertTrue(r.markers.isEmpty())
  }

  @Test
  fun leavesInlineMarkerAsLiteral() {
    val r = parseAvatarMarkers("The tag [avatar:happy] is literal here.\n")
    assertEquals("The tag [avatar:happy] is literal here.\n", r.cleanedText)
    assertTrue(r.markers.isEmpty())
  }

  @Test
  fun handlesMultipleMarkersInSequence() {
    val r = parseAvatarMarkers("[avatar:happy]\nA\n[avatar:sad]\nB\n[avatar:neutral]\n")
    assertEquals("A\nB\n", r.cleanedText)
    assertEquals(
      listOf(AvatarMarker("happy"), AvatarMarker("sad"), AvatarMarker("neutral")),
      r.markers,
    )
  }

  @Test
  fun toleratesLeadingTrailingWhitespaceOnMarkerLine() {
    val r = parseAvatarMarkers("hi\n  [avatar:happy]  \nthere\n\t[avatar:sad]\t\nend\n")
    assertEquals("hi\nthere\nend\n", r.cleanedText)
    assertEquals(listOf(AvatarMarker("happy"), AvatarMarker("sad")), r.markers)
  }

  @Test
  fun emitsMarkerAtStreamEndWithoutTrailingNewlineViaFlush() {
    val r = parseAvatarMarkers("Hi\n[avatar:happy]")
    assertEquals("Hi\n", r.cleanedText)
    assertEquals(listOf(AvatarMarker("happy")), r.markers)
  }

  @Test
  fun preservesPartialTrailingNonMarkerLine() {
    val r = parseAvatarMarkers("alpha\nbeta")
    assertEquals("alpha\nbeta", r.cleanedText)
    assertTrue(r.markers.isEmpty())
  }

  @Test
  fun treatsInvalidStateNamesAsLiteral() {
    val r = parseAvatarMarkers("[avatar:has space]\nend\n")
    assertEquals("[avatar:has space]\nend\n", r.cleanedText)
    assertTrue(r.markers.isEmpty())
  }

  @Test
  fun acceptsDashesAndUnderscoresInStateNames() {
    val r = parseAvatarMarkers("[avatar:head-cocked_1]\n")
    assertEquals("", r.cleanedText)
    assertEquals(listOf(AvatarMarker("head-cocked_1")), r.markers)
  }

  @Test
  fun streamingReconstructsMarkerSplitByteByByte() {
    val parser = AvatarMarkerParser()
    val chunks = listOf("[", "avatar", ":", "ha", "ppy", "]", "\n")
    val outBuilder = StringBuilder()
    val markers = mutableListOf<AvatarMarker>()
    for (c in chunks) {
      val r = parser.push(c)
      outBuilder.append(r.cleanedText)
      markers.addAll(r.markers)
    }
    val end = parser.flush()
    outBuilder.append(end.cleanedText)
    markers.addAll(end.markers)
    assertEquals("", outBuilder.toString())
    assertEquals(listOf(AvatarMarker("happy")), markers)
  }

  @Test
  fun streamingEmitsNonMarkerTailImmediately() {
    val parser = AvatarMarkerParser()
    val r = parser.push("hello world")
    assertEquals("hello world", r.cleanedText)
    assertTrue(r.markers.isEmpty())
    val f = parser.flush()
    assertEquals("", f.cleanedText)
    assertTrue(f.markers.isEmpty())
  }

  @Test
  fun streamingBuffersBracketStartInCaseItBecomesMarker() {
    val parser = AvatarMarkerParser()
    val r1 = parser.push("text\n[")
    assertEquals("text\n", r1.cleanedText)
    assertTrue(r1.markers.isEmpty())
    val r2 = parser.push("avatar:happy]\n")
    assertEquals("", r2.cleanedText)
    assertEquals(listOf(AvatarMarker("happy")), r2.markers)
  }

  @Test
  fun resetClearsInFlightBuffer() {
    val parser = AvatarMarkerParser()
    parser.push("[avatar")
    parser.reset()
    val r = parser.push(":happy]\n")
    assertEquals(":happy]\n", r.cleanedText)
    assertTrue(r.markers.isEmpty())
  }
}

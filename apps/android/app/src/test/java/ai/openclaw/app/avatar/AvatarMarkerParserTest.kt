package ai.openclaw.app.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarMarkerParserTest {

    @Test
    fun oneShotStripsInlineMarker() {
        val r = parseAvatarMarkers("Hello <<<happy>>> world")
        assertEquals("Hello  world", r.cleanedText)
        assertEquals(listOf(AvatarMarker("happy")), r.markers)
    }

    @Test
    fun oneShotHandlesMarkerOnOwnLineToo() {
        val r = parseAvatarMarkers("Hello\n<<<happy>>>\nworld\n")
        assertEquals("Hello\n\nworld\n", r.cleanedText)
        assertEquals(listOf(AvatarMarker("happy")), r.markers)
    }

    @Test
    fun passesThroughWithNoMarkers() {
        val r = parseAvatarMarkers("no marker here.\nsecond line\n")
        assertEquals("no marker here.\nsecond line\n", r.cleanedText)
        assertTrue(r.markers.isEmpty())
    }

    @Test
    fun handlesMultipleMarkersInSequence() {
        val r = parseAvatarMarkers("<<<happy>>>A<<<sad>>>B<<<neutral>>>")
        assertEquals("AB", r.cleanedText)
        assertEquals(
            listOf(AvatarMarker("happy"), AvatarMarker("sad"), AvatarMarker("neutral")),
            r.markers,
        )
    }

    @Test
    fun letsMarkerMidSentenceSegmentText() {
        val r = parseAvatarMarkers("I feel <<<happy>>>about this but <<<sad>>>about that.")
        assertEquals("I feel about this but about that.", r.cleanedText)
        assertEquals(listOf(AvatarMarker("happy"), AvatarMarker("sad")), r.markers)
    }

    @Test
    fun emitsMarkerAtStreamEndWithoutTrailingText() {
        val r = parseAvatarMarkers("Hi <<<happy>>>")
        assertEquals("Hi ", r.cleanedText)
        assertEquals(listOf(AvatarMarker("happy")), r.markers)
    }

    @Test
    fun preservesPartialTrailingNonMarker() {
        val r = parseAvatarMarkers("alpha\nbeta")
        assertEquals("alpha\nbeta", r.cleanedText)
        assertTrue(r.markers.isEmpty())
    }

    @Test
    fun treatsInvalidStateNamesAsLiteral() {
        val r = parseAvatarMarkers("<<<has space>>> then <<<>>>")
        assertEquals("<<<has space>>> then <<<>>>", r.cleanedText)
        assertTrue(r.markers.isEmpty())
    }

    @Test
    fun acceptsDashesAndUnderscoresInStateNames() {
        val r = parseAvatarMarkers("<<<head-cocked_1>>>")
        assertEquals("", r.cleanedText)
        assertEquals(listOf(AvatarMarker("head-cocked_1")), r.markers)
    }

    @Test
    fun doesNotStripUnterminatedOpenerAtEndOfStream() {
        val r = parseAvatarMarkers("hi <<<hap")
        assertEquals("hi <<<hap", r.cleanedText)
        assertTrue(r.markers.isEmpty())
    }

    @Test
    fun streamingReconstructsMarkerSplitByteByByte() {
        val parser = AvatarMarkerParser()
        val chunks = listOf("<", "<", "<", "ha", "ppy", ">", ">", ">")
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
    fun streamingBuffersSingleAngleBracketInCaseItExtendsToMarker() {
        val parser = AvatarMarkerParser()
        val r1 = parser.push("text <")
        assertEquals("text ", r1.cleanedText)
        assertTrue(r1.markers.isEmpty())
        val r2 = parser.push("<<happy>>>")
        assertEquals("", r2.cleanedText)
        assertEquals(listOf(AvatarMarker("happy")), r2.markers)
    }

    @Test
    fun streamingBuffersDoubleAngleBracketInCaseItExtendsToMarker() {
        val parser = AvatarMarkerParser()
        val r1 = parser.push("text <<")
        assertEquals("text ", r1.cleanedText)
        assertTrue(r1.markers.isEmpty())
        val r2 = parser.push("<happy>>>")
        assertEquals("", r2.cleanedText)
        assertEquals(listOf(AvatarMarker("happy")), r2.markers)
    }

    @Test
    fun streamingFlushesUnterminatedOpenerAsLiteral() {
        val parser = AvatarMarkerParser()
        parser.push("text <<<incomplete")
        val f = parser.flush()
        assertEquals("<<<incomplete", f.cleanedText)
        assertTrue(f.markers.isEmpty())
    }

    @Test
    fun resetClearsInFlightBuffer() {
        val parser = AvatarMarkerParser()
        parser.push("<<")
        parser.reset()
        val r = parser.push("<happy>>>")
        // After reset, the `<<` buffer was dropped — `<happy>>>` alone isn't
        // a valid opener so it emits as literal.
        assertEquals("<happy>>>", r.cleanedText)
        assertTrue(r.markers.isEmpty())
    }

    @Test
    fun splitByMarkersTagsEachSegmentWithPrecedingMarker() {
        val segments = splitByMarkers("Hi <<<happy>>>I'm glad, <<<sad>>>but also down.")
        assertEquals(
            listOf(
                TextSegmentWithEmotion("Hi ", null),
                TextSegmentWithEmotion("I'm glad, ", "happy"),
                TextSegmentWithEmotion("but also down.", "sad"),
            ),
            segments,
        )
    }

    @Test
    fun splitByMarkersReturnsSingleSegmentWhenNoMarkers() {
        val segments = splitByMarkers("plain text")
        assertEquals(listOf(TextSegmentWithEmotion("plain text", null)), segments)
    }

    @Test
    fun splitByMarkersReturnsEmptyListForEmptyInput() {
        assertTrue(splitByMarkers("").isEmpty())
    }

    @Test
    fun splitByMarkersSkipsEmptySegmentsBetweenAdjacentMarkers() {
        val segments = splitByMarkers("<<<happy>>><<<sad>>>real text")
        assertEquals(
            listOf(TextSegmentWithEmotion("real text", "sad")),
            segments,
        )
    }

    @Test
    fun splitByMarkersTreatsInvalidMarkersAsLiteralWithinEnclosingSegment() {
        val segments = splitByMarkers("prefix <<<has space>>> suffix")
        assertEquals(
            listOf(TextSegmentWithEmotion("prefix <<<has space>>> suffix", null)),
            segments,
        )
    }
}

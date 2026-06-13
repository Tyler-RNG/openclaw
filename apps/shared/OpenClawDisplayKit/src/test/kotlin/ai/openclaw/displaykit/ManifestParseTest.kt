package ai.openclaw.displaykit

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestParseTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun parsesMinimalHeadshotManifest() {
        val manifest = json.decodeFromString<CharacterManifest>(
            """
            {
              "version": 1,
              "agentId": "ginger",
              "modes": ["headshot"],
              "stateMap": { "neutral": "neutral" },
              "content": {
                "headshot": {
                  "animations": {
                    "neutral": {
                      "sequence": {
                        "frames": [{ "ref": "neutral" }],
                        "fps": 12,
                        "loop": "infinite"
                      }
                    }
                  }
                }
              },
              "assets": { "refs": { "neutral": "avatars/ginger/neutral.gif" } }
            }
            """.trimIndent(),
        )
        assertEquals("ginger", manifest.agentId)
        assertEquals(listOf("headshot"), manifest.modes)
        val anim = manifest.content.getValue("headshot").animations.getValue("neutral")
        assertEquals(LoopMode.INFINITE, anim.sequence?.loop)
        assertEquals("avatars/ginger/neutral.gif", manifest.assets.pathFor(FrameRef("neutral")))
    }

    @Test
    fun parsesPhasedAnimationsAndTransitions() {
        val manifest = json.decodeFromString<CharacterManifest>(
            """
            {
              "version": 1,
              "agentId": "ginger",
              "modes": ["headshot"],
              "stateMap": { "thinking": "thinking" },
              "content": {
                "headshot": {
                  "animations": {
                    "thinking": {
                      "intro": { "frames": [{ "ref": "a" }], "fps": 24, "loop": "once" },
                      "loop":  { "frames": [{ "ref": "b" }], "fps": 12, "loop": "infinite" },
                      "outro": { "frames": [{ "ref": "c" }], "fps": 24, "loop": "once", "holdLastFrame": true }
                    }
                  },
                  "transitions": {
                    "*->thinking": "thinking.intro",
                    "*->happy":    { "blend": "crossfade", "ms": 150 }
                  }
                }
              },
              "assets": { "refs": { "a": "p/a", "b": "p/b", "c": "p/c" } }
            }
            """.trimIndent(),
        )
        val thinking = manifest.content.getValue("headshot").animations.getValue("thinking")
        assertNotNull(thinking.intro)
        assertEquals(24, thinking.intro.fps)
        assertEquals(LoopMode.ONCE, thinking.intro.loop)
        assertTrue(thinking.outro?.holdLastFrame == true)

        val transitions = manifest.content.getValue("headshot").transitions.orEmpty()
        val toThinking = transitions.getValue("*->thinking")
        assertIs<TransitionRef.Phase>(toThinking)
        assertEquals("thinking.intro", toThinking.value)

        val toHappy = transitions.getValue("*->happy")
        assertIs<TransitionRef.Crossfade>(toHappy)
        assertEquals(150, toHappy.ms)
    }

    @Test
    fun parsesAtlasContent() {
        val manifest = json.decodeFromString<CharacterManifest>(
            """
            {
              "version": 1,
              "agentId": "ginger",
              "modes": ["headshot"],
              "stateMap": { "neutral": "neutral" },
              "content": {
                "headshot": {
                  "atlas": { "image": "atlas.webp", "size": { "w": 1024, "h": 1024 }, "frameSize": { "w": 256, "h": 256 } },
                  "animations": {
                    "neutral": {
                      "sequence": {
                        "frames": [
                          { "ref": "atlas.webp", "x": 0, "y": 0, "w": 256, "h": 256 }
                        ],
                        "fps": 12,
                        "loop": "infinite"
                      }
                    }
                  }
                }
              },
              "assets": { "refs": { "atlas.webp": "avatars/ginger/atlas.webp" } }
            }
            """.trimIndent(),
        )
        val headshot = manifest.content.getValue("headshot")
        assertNotNull(headshot.atlas)
        assertEquals(1024, headshot.atlas.size.w)
        assertEquals(256, headshot.atlas.frameSize?.w)
        val frame = headshot.animations.getValue("neutral").sequence!!.frames[0]
        assertEquals("atlas.webp", frame.ref)
        assertEquals(0, frame.x)
        assertEquals(256, frame.w)
        assertNull(headshot.transitions)
    }

    @Test
    fun animationEffectiveLoopFallsBackToSequence() {
        val flat = Animation(
            sequence = FrameSequence(listOf(FrameRef("a")), fps = 12, loop = LoopMode.INFINITE),
        )
        assertEquals(flat.sequence, flat.effectiveLoop)

        val phased = Animation(
            loop = FrameSequence(listOf(FrameRef("a")), fps = 12, loop = LoopMode.INFINITE),
        )
        assertEquals(phased.loop, phased.effectiveLoop)
    }
}

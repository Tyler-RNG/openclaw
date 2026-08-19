/**
 * Streaming parser for avatar-state markers embedded in assistant text.
 *
 * Two marker syntaxes are recognized, both only when alone on their own line
 * (leading/trailing whitespace OK); inline occurrences stay literal text:
 *
 * - `[avatar:<state>]` — the gateway-native form, used by `identity.avatar`
 *   multi-state configs.
 * - `<<<state>>>` / `<<<state-N>>>` — the SpriteCore form. The sprite-core
 *   plugin teaches this vocabulary to the model, and the SpriteCore client
 *   SDKs emit it, so the gateway must strip it too or the raw marker reaches
 *   chat transcripts and TTS. `N` is an optional play count.
 *
 * Matching markers are stripped from the visible text and surfaced separately.
 *
 * The parser is stateful across chunks: a marker split mid-token across two
 * chunks is still recognized. Non-marker content is emitted immediately when
 * possible so streaming UX isn't delayed.
 */

export type AvatarMarker = {
  state: string;
  /** Play count from `<<<state-N>>>`; undefined means loop (the default). */
  count?: number;
};

export type AvatarMarkerParseResult = {
  cleanedText: string;
  markers: AvatarMarker[];
};

export type AvatarMarkerParser = {
  push(chunk: string): AvatarMarkerParseResult;
  flush(): AvatarMarkerParseResult;
  reset(): void;
};

const MARKER_LINE_RE = /^[ \t]*\[avatar:([a-zA-Z0-9_-]+)\][ \t]*$/;
const SPRITE_MARKER_LINE_RE =
  /^[ \t]*<<<([a-zA-Z0-9_]+(?:-[a-zA-Z0-9_]+)*?)(?:-(\d+))?>>>[ \t]*$/;

/** Matches either marker syntax, or null when the line is ordinary text. */
function matchMarkerLine(line: string): AvatarMarker | null {
  const native = MARKER_LINE_RE.exec(line);
  if (native) {
    return { state: native[1]! };
  }
  const sprite = SPRITE_MARKER_LINE_RE.exec(line);
  if (sprite) {
    const rawCount = sprite[2];
    return rawCount === undefined
      ? { state: sprite[1]! }
      : { state: sprite[1]!, count: Number(rawCount) };
  }
  return null;
}

/**
 * A line tail could become a marker if it starts with `[` or `<` (optionally
 * preceded by spaces/tabs). Anything else is guaranteed never to match either
 * marker regex and can be emitted immediately.
 */
function tailCouldBeMarker(tail: string): boolean {
  let i = 0;
  while (i < tail.length && (tail[i] === " " || tail[i] === "\t")) {
    i++;
  }
  if (i === tail.length) {
    // whitespace-only tail: could still become `<ws>[avatar:...]` or `<ws><<<state>>>`
    return true;
  }
  return tail[i] === "[" || tail[i] === "<";
}

export function createAvatarMarkerParser(): AvatarMarkerParser {
  let buffer = "";

  function processFinalizedLines(
    text: string,
  ): AvatarMarkerParseResult & { remainder: string } {
    const lastNl = text.lastIndexOf("\n");
    if (lastNl === -1) {
      return { cleanedText: "", markers: [], remainder: text };
    }
    const finalized = text.slice(0, lastNl);
    const tail = text.slice(lastNl + 1);
    const lines = finalized.split("\n");
    const outParts: string[] = [];
    const markers: AvatarMarker[] = [];
    for (const line of lines) {
      const marker = matchMarkerLine(line);
      if (marker) {
        markers.push(marker);
        continue;
      }
      outParts.push(line);
    }
    // Rejoin with newlines; append trailing newline to re-attach the final
    // newline that split() consumed.
    const cleaned = outParts.length > 0 ? outParts.join("\n") + "\n" : "";
    return { cleanedText: cleaned, markers, remainder: tail };
  }

  return {
    push(chunk) {
      if (chunk.length === 0) {
        return { cleanedText: "", markers: [] };
      }
      const combined = buffer + chunk;
      const { cleanedText, markers, remainder } = processFinalizedLines(combined);
      if (remainder.length === 0) {
        buffer = "";
        return { cleanedText, markers };
      }
      if (tailCouldBeMarker(remainder)) {
        buffer = remainder;
        return { cleanedText, markers };
      }
      // Remainder is safe to emit — cannot ever become a marker line.
      buffer = "";
      return { cleanedText: cleanedText + remainder, markers };
    },
    flush() {
      if (buffer.length === 0) {
        return { cleanedText: "", markers: [] };
      }
      const marker = matchMarkerLine(buffer);
      if (marker) {
        buffer = "";
        return { cleanedText: "", markers: [marker] };
      }
      const leftover = buffer;
      buffer = "";
      return { cleanedText: leftover, markers: [] };
    },
    reset() {
      buffer = "";
    },
  };
}

/**
 * Convenience: parse a complete (non-streamed) string in one shot.
 */
export function parseAvatarMarkers(text: string): AvatarMarkerParseResult {
  const parser = createAvatarMarkerParser();
  const first = parser.push(text);
  const last = parser.flush();
  return {
    cleanedText: first.cleanedText + last.cleanedText,
    markers: [...first.markers, ...last.markers],
  };
}

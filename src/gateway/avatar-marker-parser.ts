/**
 * Streaming parser for avatar-state markers embedded in assistant text.
 *
 * A marker is the literal text `[avatar:<state>]` on its own line — nothing
 * else on that line (leading/trailing whitespace OK). Matching markers are
 * stripped from the visible text and surfaced separately. Markers inline with
 * other text on the same line are treated as literal text, not markers.
 *
 * The parser is stateful across chunks: a marker split mid-token across two
 * chunks is still recognized. Non-marker content is emitted immediately when
 * possible so streaming UX isn't delayed.
 */

export type AvatarMarker = { state: string };

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

/**
 * A line tail could become a marker if it starts with `[` (optionally
 * preceded by spaces/tabs). Anything else is guaranteed never to match the
 * marker regex and can be emitted immediately.
 */
function tailCouldBeMarker(tail: string): boolean {
  let i = 0;
  while (i < tail.length && (tail[i] === " " || tail[i] === "\t")) {
    i++;
  }
  if (i === tail.length) {
    // whitespace-only tail: could still become `<ws>[avatar:...]`
    return true;
  }
  return tail[i] === "[";
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
      const match = MARKER_LINE_RE.exec(line);
      if (match) {
        markers.push({ state: match[1]! });
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
      const match = MARKER_LINE_RE.exec(buffer);
      if (match) {
        buffer = "";
        return { cleanedText: "", markers: [{ state: match[1]! }] };
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

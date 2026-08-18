import { describe, expect, it } from "vitest";
import {
  createAvatarMarkerParser,
  parseAvatarMarkers,
} from "./avatar-marker-parser.js";

describe("parseAvatarMarkers (one-shot)", () => {
  it("strips a marker on its own line and reports it", () => {
    const r = parseAvatarMarkers("Hello\n[avatar:happy]\nworld\n");
    expect(r.cleanedText).toBe("Hello\nworld\n");
    expect(r.markers).toEqual([{ state: "happy" }]);
  });

  it("returns raw text unchanged when no marker is present", () => {
    const r = parseAvatarMarkers("no marker here.\nsecond line\n");
    expect(r.cleanedText).toBe("no marker here.\nsecond line\n");
    expect(r.markers).toEqual([]);
  });

  it("leaves inline [avatar:X] intact when other text shares the line", () => {
    const r = parseAvatarMarkers(
      "The tag [avatar:happy] is literal here.\n",
    );
    expect(r.cleanedText).toBe("The tag [avatar:happy] is literal here.\n");
    expect(r.markers).toEqual([]);
  });

  it("handles multiple markers in sequence", () => {
    const r = parseAvatarMarkers(
      "[avatar:happy]\nA\n[avatar:sad]\nB\n[avatar:neutral]\n",
    );
    expect(r.cleanedText).toBe("A\nB\n");
    expect(r.markers).toEqual([
      { state: "happy" },
      { state: "sad" },
      { state: "neutral" },
    ]);
  });

  it("tolerates leading/trailing spaces and tabs inside marker lines", () => {
    const r = parseAvatarMarkers(
      "hi\n  [avatar:happy]  \nthere\n\t[avatar:sad]\t\nend\n",
    );
    expect(r.cleanedText).toBe("hi\nthere\nend\n");
    expect(r.markers).toEqual([{ state: "happy" }, { state: "sad" }]);
  });

  it("emits a marker that ends the stream with no trailing newline via flush", () => {
    const r = parseAvatarMarkers("Hi\n[avatar:happy]");
    expect(r.cleanedText).toBe("Hi\n");
    expect(r.markers).toEqual([{ state: "happy" }]);
  });

  it("preserves a partial non-marker line at end of stream", () => {
    const r = parseAvatarMarkers("alpha\nbeta");
    expect(r.cleanedText).toBe("alpha\nbeta");
    expect(r.markers).toEqual([]);
  });

  it("ignores invalid state names (regex mismatch) and leaves them as text", () => {
    const r = parseAvatarMarkers("[avatar:has space]\nend\n");
    // Not a valid marker → treated as literal on that line.
    expect(r.cleanedText).toBe("[avatar:has space]\nend\n");
    expect(r.markers).toEqual([]);
  });

  it("accepts dashes and underscores in state names", () => {
    const r = parseAvatarMarkers("[avatar:head-cocked_1]\n");
    expect(r.cleanedText).toBe("");
    expect(r.markers).toEqual([{ state: "head-cocked_1" }]);
  });
});

describe("createAvatarMarkerParser (streaming)", () => {
  it("reconstructs a marker split byte-by-byte across pushes", () => {
    const parser = createAvatarMarkerParser();
    const chunks = ["[", "avatar", ":", "ha", "ppy", "]", "\n"];
    const outParts: string[] = [];
    const markers: { state: string }[] = [];
    for (const c of chunks) {
      const r = parser.push(c);
      outParts.push(r.cleanedText);
      markers.push(...r.markers);
    }
    const f = parser.flush();
    outParts.push(f.cleanedText);
    markers.push(...f.markers);
    expect(outParts.join("")).toBe("");
    expect(markers).toEqual([{ state: "happy" }]);
  });

  it("emits non-marker content immediately when the tail can't be a marker", () => {
    const parser = createAvatarMarkerParser();
    const r = parser.push("hello world");
    expect(r.cleanedText).toBe("hello world");
    expect(r.markers).toEqual([]);
    // No buffering of normal text means flush produces nothing extra.
    const f = parser.flush();
    expect(f.cleanedText).toBe("");
    expect(f.markers).toEqual([]);
  });

  it("buffers a tail starting with [ in case it becomes a marker", () => {
    const parser = createAvatarMarkerParser();
    const r1 = parser.push("text\n[");
    // "[" alone could still become a marker so it must be held.
    expect(r1.cleanedText).toBe("text\n");
    expect(r1.markers).toEqual([]);
    const r2 = parser.push("avatar:happy]\n");
    expect(r2.cleanedText).toBe("");
    expect(r2.markers).toEqual([{ state: "happy" }]);
  });

  it("emits a buffered partial bracket as literal text when it never completes a marker", () => {
    const parser = createAvatarMarkerParser();
    parser.push("[avatar:");
    // This one proves the recovery path: next input arrives that isn't a
    // valid marker continuation — everything must come out as text on flush.
    parser.push("happy] and more text\n");
    const f = parser.flush();
    // No markers emitted because the line had content after the closing bracket.
    expect(f.markers).toEqual([]);
    // Nothing left in the buffer to flush — the full line already flushed on \n.
    expect(f.cleanedText).toBe("");
  });

  it("reset() clears in-flight buffer state", () => {
    const parser = createAvatarMarkerParser();
    parser.push("[avatar");
    parser.reset();
    // After reset, prior buffered partial is dropped.
    const r = parser.push(":happy]\n");
    expect(r.cleanedText).toBe(":happy]\n");
    expect(r.markers).toEqual([]);
  });
});

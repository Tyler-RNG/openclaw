import { describe, expect, it } from "vitest";
import {
  buildAvatarStateInstruction,
  isAgentAvatarStatesConfig,
  isValidAvatarStateName,
} from "./identity-avatar-states.js";

const VALID_CFG = {
  kind: "states" as const,
  default: "neutral",
  states: {
    neutral: { file: "neutral.gif", description: "resting" },
    happy: { file: "happy.gif", description: "warm, supportive" },
    sad: { file: "sad.gif" },
  },
};

describe("isValidAvatarStateName", () => {
  it("accepts typical names", () => {
    expect(isValidAvatarStateName("happy")).toBe(true);
    expect(isValidAvatarStateName("head-cocked")).toBe(true);
    expect(isValidAvatarStateName("state_1")).toBe(true);
  });
  it("rejects empty / whitespace / symbols", () => {
    expect(isValidAvatarStateName("")).toBe(false);
    expect(isValidAvatarStateName(" happy")).toBe(false);
    expect(isValidAvatarStateName("ha ppy")).toBe(false);
    expect(isValidAvatarStateName("happy!")).toBe(false);
    expect(isValidAvatarStateName("../../etc")).toBe(false);
  });
});

describe("isAgentAvatarStatesConfig", () => {
  it("accepts a well-formed config", () => {
    expect(isAgentAvatarStatesConfig(VALID_CFG)).toBe(true);
  });

  it("accepts description-less entries", () => {
    expect(
      isAgentAvatarStatesConfig({
        kind: "states",
        default: "a",
        states: { a: { file: "a.gif" } },
      }),
    ).toBe(true);
  });

  it("rejects plain strings", () => {
    expect(isAgentAvatarStatesConfig("happy.gif")).toBe(false);
  });

  it("rejects missing kind", () => {
    expect(
      isAgentAvatarStatesConfig({ default: "a", states: { a: { file: "a.gif" } } }),
    ).toBe(false);
  });

  it("rejects when default doesn't exist in states", () => {
    expect(
      isAgentAvatarStatesConfig({
        kind: "states",
        default: "missing",
        states: { a: { file: "a.gif" } },
      }),
    ).toBe(false);
  });

  it("rejects empty states map", () => {
    expect(
      isAgentAvatarStatesConfig({
        kind: "states",
        default: "a",
        states: {},
      }),
    ).toBe(false);
  });

  it("rejects invalid state names", () => {
    expect(
      isAgentAvatarStatesConfig({
        kind: "states",
        default: "a b",
        states: { "a b": { file: "a.gif" } },
      }),
    ).toBe(false);
  });

  it("rejects entries with non-string or empty file", () => {
    expect(
      isAgentAvatarStatesConfig({
        kind: "states",
        default: "a",
        states: { a: { file: "" } },
      }),
    ).toBe(false);
    expect(
      isAgentAvatarStatesConfig({
        kind: "states",
        default: "a",
        states: { a: { file: 123 } },
      }),
    ).toBe(false);
  });

  it("rejects non-string instruction", () => {
    expect(
      isAgentAvatarStatesConfig({ ...VALID_CFG, instruction: 42 }),
    ).toBe(false);
  });
});

describe("buildAvatarStateInstruction", () => {
  it("uses the explicit instruction verbatim when provided", () => {
    const text = buildAvatarStateInstruction({
      ...VALID_CFG,
      instruction: "  Custom instruction body.  ",
    });
    expect(text).toBe("Custom instruction body.");
  });

  it("auto-generates from state descriptions", () => {
    const text = buildAvatarStateInstruction(VALID_CFG);
    // Covers the marker format, visible rules, state list, default callout.
    expect(text).toContain("[avatar:<state>]");
    expect(text).toContain("- neutral: resting");
    expect(text).toContain("- happy: warm, supportive");
    // Description-less entries show only the name.
    expect(text).toContain("- sad\n");
    expect(text).toContain("Default state: neutral.");
    // Instruction must not leak the marker system to the user.
    expect(text).toContain("Do not mention this marker system");
  });
});

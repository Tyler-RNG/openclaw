import { Type } from "@sinclair/typebox";
import { NonEmptyString } from "./primitives.js";

// Display capabilities a client advertises in `caps` at pair time. The gateway
// uses these to decide which manifest modes to populate for that client.
export const DISPLAY_CAP_SPRITE_HEADSHOT = "display:sprite-headshot" as const;
export const DISPLAY_CAP_SPRITE_FULLBODY = "display:sprite-fullbody" as const;
export const DISPLAY_CAP_TEXT = "display:text" as const;
export const DISPLAY_CAP_TTS = "display:tts" as const;

export const DISPLAY_CAPS = [
  DISPLAY_CAP_SPRITE_HEADSHOT,
  DISPLAY_CAP_SPRITE_FULLBODY,
  DISPLAY_CAP_TEXT,
  DISPLAY_CAP_TTS,
] as const;

// Render modes a character manifest can describe. Clients pick the ones that
// match their caps. Open string so future modes (e.g. "rig-skeletal") don't
// break the schema; DISPLAY_MODE_* constants below are the recommended set.
export const DISPLAY_MODE_HEADSHOT = "headshot" as const;
export const DISPLAY_MODE_FULLBODY = "fullbody" as const;

const LoopModeSchema = Type.String({ enum: ["infinite", "once", "ping-pong"] });

// A single source rectangle inside an atlas image. For non-atlas frame sources
// only `ref` is set (points at a whole-image asset) and rect coords are omitted.
const FrameRefSchema = Type.Object(
  {
    ref: NonEmptyString,
    x: Type.Optional(Type.Integer({ minimum: 0 })),
    y: Type.Optional(Type.Integer({ minimum: 0 })),
    w: Type.Optional(Type.Integer({ minimum: 1 })),
    h: Type.Optional(Type.Integer({ minimum: 1 })),
  },
  { additionalProperties: false },
);

const FrameSequenceSchema = Type.Object(
  {
    frames: Type.Array(FrameRefSchema, { minItems: 1 }),
    fps: Type.Number({ minimum: 1, maximum: 120 }),
    loop: LoopModeSchema,
    holdLastFrame: Type.Optional(Type.Boolean()),
    iterations: Type.Optional(Type.Integer({ minimum: 1 })),
  },
  { additionalProperties: false },
);

// An animation is either a single sequence (flat) or a phased trio. The flat
// form is the common case; phases are for states that need smooth entry/exit.
const AnimationSchema = Type.Object(
  {
    description: Type.Optional(NonEmptyString),
    sequence: Type.Optional(FrameSequenceSchema),
    intro: Type.Optional(FrameSequenceSchema),
    loop: Type.Optional(FrameSequenceSchema),
    outro: Type.Optional(FrameSequenceSchema),
  },
  { additionalProperties: false },
);

// Transition descriptor that runtimes play while swapping animations. Either
// a named phase ("thinking.intro") or an inline blend directive.
const TransitionRefSchema = Type.Union([
  NonEmptyString,
  Type.Object(
    {
      blend: Type.String({ enum: ["crossfade"] }),
      ms: Type.Integer({ minimum: 1, maximum: 10_000 }),
    },
    { additionalProperties: false },
  ),
]);

// Per-mode data carried by the manifest. Each mode bundles an optional atlas
// image ref, a per-animation table, and a state-to-animation defaults map.
const ModeContentSchema = Type.Object(
  {
    // Optional atlas image ref + pixel dimensions for the packed atlas format.
    atlas: Type.Optional(
      Type.Object(
        {
          image: NonEmptyString,
          size: Type.Object(
            {
              w: Type.Integer({ minimum: 1 }),
              h: Type.Integer({ minimum: 1 }),
            },
            { additionalProperties: false },
          ),
          frameSize: Type.Optional(
            Type.Object(
              {
                w: Type.Integer({ minimum: 1 }),
                h: Type.Integer({ minimum: 1 }),
              },
              { additionalProperties: false },
            ),
          ),
        },
        { additionalProperties: false },
      ),
    ),
    animations: Type.Record(NonEmptyString, AnimationSchema),
    transitions: Type.Optional(Type.Record(NonEmptyString, TransitionRefSchema)),
  },
  { additionalProperties: false },
);

// Asset bundle the client should fetch to render the manifest. Paths are
// gateway-asset-endpoint relative (served under `/openclaw-assets/<path>`).
const AssetBundleSchema = Type.Object(
  {
    // Whole-image assets keyed by the `ref` values used in FrameRefs (including
    // atlas images). Value is a relative path under the asset endpoint root.
    refs: Type.Record(NonEmptyString, NonEmptyString),
  },
  { additionalProperties: false },
);

export const CharacterManifestSchema = Type.Object(
  {
    version: Type.Literal(1),
    agentId: NonEmptyString,
    name: Type.Optional(NonEmptyString),
    // The modes this manifest carries content for. Open string so new render
    // modes (e.g. "rig-skeletal") can be added without schema churn; current
    // well-known values are "headshot" and "fullbody".
    modes: Type.Array(NonEmptyString, { minItems: 1 }),
    // Default agent-state → animation-name map. Clients may override locally,
    // but the gateway-provided map is authoritative so multiple clients agree.
    stateMap: Type.Record(NonEmptyString, NonEmptyString),
    // Per-mode content. A key here must also appear in `modes`.
    content: Type.Record(NonEmptyString, ModeContentSchema),
    assets: AssetBundleSchema,
  },
  { additionalProperties: false },
);

export const NodeGetCharacterManifestParamsSchema = Type.Object(
  {
    agentId: NonEmptyString,
    // Optional mode filter: when set, the gateway returns content only for the
    // requested modes. Omit to receive everything the agent has authored.
    modes: Type.Optional(Type.Array(NonEmptyString, { minItems: 1 })),
  },
  { additionalProperties: false },
);

export const NodeGetCharacterManifestResultSchema = Type.Object(
  {
    manifest: CharacterManifestSchema,
    // Monotonic version to let clients cache and detect updates without
    // diffing the whole payload.
    revision: Type.Integer({ minimum: 0 }),
  },
  { additionalProperties: false },
);

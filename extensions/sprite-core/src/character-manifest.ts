import fs from "node:fs/promises";
import path from "node:path";
import { resolveStateDir } from "openclaw/plugin-sdk/state-paths";
import { isAtlasAvatarConfig } from "./prompting.js";
import type {
  SpriteCoreAgentEntry,
  SpriteCoreAssetsConfig,
  SpriteCoreAvatarAtlasConfig,
  SpriteCoreAvatarLoopMode,
  SpriteCoreAvatarTransition,
  SpriteCoreConfig,
  SpriteCoreEmotionDirective,
} from "./types.js";

// Mirror of the wire-stable display caps + modes from
// `src/gateway/protocol/schema/display.ts`. Inlined so the plugin doesn't
// import the heavy ajv-compiled protocol barrel. Keep these values in sync
// with the upstream definitions if they ever change.
const DISPLAY_CAP_SPRITE_HEADSHOT = "display:sprite-headshot" as const;
const DISPLAY_CAP_SPRITE_FULLBODY = "display:sprite-fullbody" as const;
const DISPLAY_MODE_HEADSHOT = "headshot" as const;
const DISPLAY_MODE_FULLBODY = "fullbody" as const;

// Subset of CharacterManifest the synthesizer produces. Keep field shapes in
// sync with `CharacterManifestSchema` in src/gateway/protocol/schema/display.ts.
export type CharacterManifest = {
  version: 1;
  agentId: string;
  name?: string;
  modes: string[];
  stateMap: Record<string, string>;
  content: Record<string, ModeContent>;
  assets: { refs: Record<string, string> };
  emotions?: Record<string, { directive?: SpriteCoreEmotionDirective }>;
};

type FrameRef = {
  ref: string;
  x?: number;
  y?: number;
  w?: number;
  h?: number;
};

type FrameSequence = {
  frames: FrameRef[];
  fps: number;
  loop: SpriteCoreAvatarLoopMode;
  holdLastFrame?: boolean;
  iterations?: number;
};

type Animation = {
  description?: string;
  sequence?: FrameSequence;
  intro?: FrameSequence;
  loop?: FrameSequence;
  outro?: FrameSequence;
};

type ModeContent = {
  atlas?: {
    image: string;
    size: { w: number; h: number };
    frameSize?: { w: number; h: number };
  };
  animations: Record<string, Animation>;
  transitions?: Record<string, string | { blend: "crossfade"; ms: number }>;
};

type Synthesized = { mode: string; content: ModeContent; assets: Record<string, string> };

const DEFAULT_ATLAS_FPS = 12;
const DEFAULT_ATLAS_LOOP: SpriteCoreAvatarLoopMode = "infinite";

export type BuildCharacterManifestResult =
  | { ok: true; manifest: CharacterManifest; revision: number }
  | {
      ok: false;
      code: "unknown-agent" | "no-avatar" | "unsupported-kind" | "atlas-unreadable";
      message: string;
    };

export type BuildCharacterManifestInput = {
  /** Plugin config snapshot — read fresh per call so config reload is observed. */
  pluginConfig: SpriteCoreConfig | undefined;
  agentId: string;
  /** Optional request-side mode filter; when set, intersects with advertised modes. */
  modes?: readonly string[];
  /** Caps advertised by the connected client. `undefined` = operator mode, no filter. */
  caps?: readonly string[];
  /** Override for the assets root; defaults to plugin-config-resolved value. Tests inject. */
  assetsDir?: string;
  /** Override for reading the atlas JSON from disk; used in tests. */
  readAtlasManifest?: (absolutePath: string) => Promise<unknown>;
  /** Optional agent display name (the manifest exposes it for UI). */
  agentName?: string;
};

export function resolveAssetsDirForManifest(cfg: SpriteCoreConfig | undefined): string {
  const raw = readAssetsDir(cfg?.assets);
  return path.isAbsolute(raw) ? path.resolve(raw) : path.resolve(resolveStateDir(), raw);
}

function readAssetsDir(cfg: SpriteCoreAssetsConfig | undefined): string {
  const v = cfg?.assetsDir;
  return typeof v === "string" && v.trim().length > 0 ? v.trim() : "./assets";
}

export async function buildCharacterManifest(
  input: BuildCharacterManifestInput,
): Promise<BuildCharacterManifestResult> {
  const agent = input.pluginConfig?.agents?.[input.agentId];
  if (!agent) {
    return {
      ok: false,
      code: "unknown-agent",
      message: `unknown agentId for sprite-core: ${input.agentId}`,
    };
  }
  const avatar = agent.avatar;
  if (!avatar) {
    return {
      ok: false,
      code: "no-avatar",
      message: `agent "${input.agentId}" has no SpriteCore avatar configured`,
    };
  }
  if (!isAtlasAvatarConfig(avatar)) {
    return { ok: false, code: "unsupported-kind", message: "avatar kind not recognized" };
  }

  const atlasResult = await synthesizeFromAtlas(avatar, input);
  if (!atlasResult.ok) {
    return atlasResult;
  }
  const synthesized = atlasResult.synthesized;

  // v1: only the headshot mode is authored. Filter the advertised set against
  // request modes + client display caps so clients always receive a
  // self-consistent manifest — `manifest.modes` exactly matches `manifest.content`.
  const advertisedModes = [synthesized.mode];
  const allowedModes = filterModes(advertisedModes, input.modes, input.caps);

  const content: Record<string, ModeContent> = {};
  for (const mode of allowedModes) {
    if (mode === synthesized.mode) {
      content[mode] = synthesized.content;
    }
  }

  const stateMap = buildStateMap(synthesized.content);
  const emotions = buildWireEmotions(agent);
  const manifest: CharacterManifest = {
    version: 1,
    agentId: input.agentId,
    ...(input.agentName ? { name: input.agentName } : {}),
    modes: allowedModes,
    stateMap,
    content,
    assets: { refs: allowedModes.length > 0 ? synthesized.assets : {} },
    ...(emotions ? { emotions } : {}),
  };

  return { ok: true, manifest, revision: computeRevision(manifest) };
}

/**
 * Project per-agent emotion config onto the wire shape: only the `directive`
 * is shipped to clients. Descriptions stay server-side (they feed the
 * injected system prompt) so clients can't inadvertently leak them.
 * Returns `undefined` when no entry has a non-empty directive — skips adding
 * the field to the manifest so the wire shape stays compact.
 */
function buildWireEmotions(
  agent: SpriteCoreAgentEntry,
): Record<string, { directive?: SpriteCoreEmotionDirective }> | undefined {
  const src = agent.emotions;
  if (!src || typeof src !== "object") {
    return undefined;
  }
  const out: Record<string, { directive?: SpriteCoreEmotionDirective }> = {};
  let anyDirective = false;
  for (const [name, entry] of Object.entries(src)) {
    if (!entry || typeof entry !== "object") {
      continue;
    }
    const directive = sanitizeEmotionDirective(entry.directive);
    if (directive) {
      out[name] = { directive };
      anyDirective = true;
    } else {
      // Entry exists but no voice overrides — still include the key so
      // clients see the full configured state list (useful for capability
      // introspection), but with an empty object.
      out[name] = {};
    }
  }
  if (Object.keys(out).length === 0) {
    return undefined;
  }
  // If no entry carried any directive fields, there's nothing actionable
  // for the client to apply — drop the entire map to keep the wire lean.
  return anyDirective ? out : undefined;
}

function sanitizeEmotionDirective(
  raw: SpriteCoreEmotionDirective | undefined,
): SpriteCoreEmotionDirective | undefined {
  if (!raw || typeof raw !== "object") {
    return undefined;
  }
  const out: SpriteCoreEmotionDirective = {};
  let any = false;
  if (typeof raw.voiceId === "string" && raw.voiceId.trim().length > 0) {
    out.voiceId = raw.voiceId.trim();
    any = true;
  }
  if (isUnitNumber(raw.stability)) {
    out.stability = raw.stability;
    any = true;
  }
  if (isUnitNumber(raw.similarity)) {
    out.similarity = raw.similarity;
    any = true;
  }
  if (isUnitNumber(raw.style)) {
    out.style = raw.style;
    any = true;
  }
  if (typeof raw.speakerBoost === "boolean") {
    out.speakerBoost = raw.speakerBoost;
    any = true;
  }
  if (typeof raw.speed === "number" && Number.isFinite(raw.speed) && raw.speed > 0) {
    out.speed = raw.speed;
    any = true;
  }
  if (typeof raw.audioTag === "string" && raw.audioTag.trim().length > 0) {
    out.audioTag = raw.audioTag.trim();
    any = true;
  }
  return any ? out : undefined;
}

function isUnitNumber(v: unknown): v is number {
  return typeof v === "number" && Number.isFinite(v) && v >= 0 && v <= 1;
}

// ---------- atlas synthesis ----------

type AtlasAnimationJson =
  | {
      frames: string[];
      fps?: number;
      loop?: SpriteCoreAvatarLoopMode;
      holdLastFrame?: boolean;
      iterations?: number;
    }
  | {
      intro?: AtlasAnimationSequenceJson;
      loop?: AtlasAnimationSequenceJson;
      outro?: AtlasAnimationSequenceJson;
    };

type AtlasAnimationSequenceJson = {
  frames: string[];
  fps?: number;
  loop?: SpriteCoreAvatarLoopMode;
  holdLastFrame?: boolean;
  iterations?: number;
};

type AtlasManifestJson = {
  image?: string;
  size?: { w: number; h: number };
  frameSize?: { w: number; h: number };
  frames?: Record<string, { x: number; y: number; w: number; h: number }>;
  animations?: Record<string, AtlasAnimationJson>;
  transitions?: Record<string, SpriteCoreAvatarTransition>;
};

type AtlasSynthesisResult =
  | { ok: true; synthesized: Synthesized }
  | Extract<BuildCharacterManifestResult, { ok: false }>;

async function synthesizeFromAtlas(
  cfg: SpriteCoreAvatarAtlasConfig,
  input: BuildCharacterManifestInput,
): Promise<AtlasSynthesisResult> {
  const assetsDir = input.assetsDir ?? resolveAssetsDirForManifest(input.pluginConfig);
  const manifestRel = cfg.manifest;
  const manifestAbs = path.resolve(assetsDir, manifestRel);

  let raw: unknown;
  try {
    raw = input.readAtlasManifest
      ? await input.readAtlasManifest(manifestAbs)
      : JSON.parse(await fs.readFile(manifestAbs, "utf8"));
  } catch (err) {
    return {
      ok: false,
      code: "atlas-unreadable",
      message: `failed to read atlas manifest at ${manifestRel}: ${(err as Error).message}`,
    };
  }
  if (!raw || typeof raw !== "object") {
    return {
      ok: false,
      code: "atlas-unreadable",
      message: `atlas manifest ${manifestRel} is not an object`,
    };
  }
  const atlas = raw as AtlasManifestJson;
  const imageFile =
    typeof atlas.image === "string" && atlas.image.trim().length > 0 ? atlas.image.trim() : null;
  if (!imageFile || !atlas.size || !atlas.frames || !atlas.animations) {
    return {
      ok: false,
      code: "atlas-unreadable",
      message: `atlas manifest ${manifestRel} missing required fields (image, size, frames, animations)`,
    };
  }

  // Asset refs for atlas: a single whole-image ref keyed by its on-disk
  // filename. Frame refs reuse that ref with explicit x/y/w/h rects.
  const atlasRefKey = imageFile;
  const manifestDir = path.posix.dirname(manifestRel.split(path.sep).join(path.posix.sep));
  const imagePathRel =
    manifestDir === "." || manifestDir === "" ? imageFile : `${manifestDir}/${imageFile}`;
  const assets: Record<string, string> = { [atlasRefKey]: imagePathRel };

  const animations: Record<string, Animation> = {};
  for (const [name, entry] of Object.entries(atlas.animations)) {
    animations[name] = translateAtlasAnimation({
      entry,
      frames: atlas.frames,
      atlasRefKey,
    });
  }

  const content: ModeContent = {
    atlas: {
      image: atlasRefKey,
      size: { w: atlas.size.w, h: atlas.size.h },
      ...(atlas.frameSize ? { frameSize: { w: atlas.frameSize.w, h: atlas.frameSize.h } } : {}),
    },
    animations,
  };
  if (atlas.transitions && Object.keys(atlas.transitions).length > 0) {
    content.transitions = translateTransitions(atlas.transitions);
  }

  return {
    ok: true,
    synthesized: { mode: DISPLAY_MODE_HEADSHOT, content, assets },
  };
}

function translateAtlasAnimation(params: {
  entry: AtlasAnimationJson;
  frames: Record<string, { x: number; y: number; w: number; h: number }>;
  atlasRefKey: string;
}): Animation {
  const flat = params.entry as {
    frames?: string[];
    fps?: number;
    loop?: SpriteCoreAvatarLoopMode;
    holdLastFrame?: boolean;
    iterations?: number;
  };
  if (Array.isArray(flat.frames)) {
    return {
      sequence: framesToSequence({
        frames: flat.frames,
        fps: flat.fps,
        loop: flat.loop,
        holdLastFrame: flat.holdLastFrame,
        iterations: flat.iterations,
        framesMap: params.frames,
        atlasRefKey: params.atlasRefKey,
      }),
    };
  }
  const phased = params.entry as {
    intro?: AtlasAnimationSequenceJson;
    loop?: AtlasAnimationSequenceJson;
    outro?: AtlasAnimationSequenceJson;
  };
  const out: Animation = {};
  if (phased.intro) {
    out.intro = framesToSequence({
      ...phased.intro,
      framesMap: params.frames,
      atlasRefKey: params.atlasRefKey,
    });
  }
  if (phased.loop) {
    out.loop = framesToSequence({
      ...phased.loop,
      framesMap: params.frames,
      atlasRefKey: params.atlasRefKey,
    });
  }
  if (phased.outro) {
    out.outro = framesToSequence({
      ...phased.outro,
      framesMap: params.frames,
      atlasRefKey: params.atlasRefKey,
    });
  }
  return out;
}

function framesToSequence(params: {
  frames: string[];
  fps?: number;
  loop?: SpriteCoreAvatarLoopMode;
  holdLastFrame?: boolean;
  iterations?: number;
  framesMap: Record<string, { x: number; y: number; w: number; h: number }>;
  atlasRefKey: string;
}): FrameSequence {
  const frames: FrameRef[] = params.frames.map((key) => {
    const rect = params.framesMap[key];
    if (!rect) {
      // Unknown frame — emit a plain ref so the runtime can surface a warning
      // rather than silently drop.
      return { ref: params.atlasRefKey };
    }
    return {
      ref: params.atlasRefKey,
      x: rect.x,
      y: rect.y,
      w: rect.w,
      h: rect.h,
    };
  });
  return {
    frames,
    fps: params.fps ?? DEFAULT_ATLAS_FPS,
    loop: params.loop ?? DEFAULT_ATLAS_LOOP,
    ...(params.holdLastFrame ? { holdLastFrame: true } : {}),
    ...(typeof params.iterations === "number" ? { iterations: params.iterations } : {}),
  };
}

function translateTransitions(
  src: Record<string, SpriteCoreAvatarTransition>,
): Record<string, string | { blend: "crossfade"; ms: number }> {
  const out: Record<string, string | { blend: "crossfade"; ms: number }> = {};
  for (const [pattern, t] of Object.entries(src)) {
    if (typeof t === "string") {
      out[pattern] = t;
    } else if (t && typeof t === "object" && t.blend === "crossfade") {
      out[pattern] = { blend: "crossfade", ms: t.ms };
    }
  }
  return out;
}

function filterModes(
  advertised: readonly string[],
  requested: readonly string[] | undefined,
  caps: readonly string[] | undefined,
): string[] {
  const requestedSet = requested ? new Set(requested) : null;
  const capsHasDisplay = !!caps?.some((c) => c.startsWith("display:"));
  return advertised.filter((mode) => {
    if (requestedSet && !requestedSet.has(mode)) {
      return false;
    }
    if (capsHasDisplay && !modeAllowedByCaps(mode, caps ?? [])) {
      return false;
    }
    return true;
  });
}

function modeAllowedByCaps(mode: string, caps: readonly string[]): boolean {
  if (mode === DISPLAY_MODE_HEADSHOT) {
    return caps.includes(DISPLAY_CAP_SPRITE_HEADSHOT);
  }
  if (mode === DISPLAY_MODE_FULLBODY) {
    return caps.includes(DISPLAY_CAP_SPRITE_FULLBODY);
  }
  return true;
}

function buildStateMap(content: ModeContent): Record<string, string> {
  const map: Record<string, string> = {};
  for (const name of Object.keys(content.animations)) {
    map[name] = name;
  }
  return map;
}

// FNV-1a 32-bit content hash so the same manifest always produces the same
// revision without a counter. Collisions are fine — revision only needs to
// change when the manifest changes.
function computeRevision(manifest: CharacterManifest): number {
  const bytes = Buffer.from(JSON.stringify(manifest), "utf8");
  let hash = 0x811c9dc5;
  for (let i = 0; i < bytes.length; i++) {
    hash ^= bytes[i];
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash & 0x7fffffff;
}

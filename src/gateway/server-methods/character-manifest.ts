import fs from "node:fs/promises";
import path from "node:path";
import {
  isAgentAvatarAtlasConfig,
  isAgentAvatarSpritesConfig,
  isAgentAvatarStatesConfig,
  type AgentAvatarAtlasConfig,
  type AgentAvatarSpritesConfig,
  type AgentAvatarStatesConfig,
} from "../../agents/identity-avatar-states.js";
import { resolveAgentIdentity } from "../../agents/identity.js";
import { resolveStateDir } from "../../config/paths.js";
import type {
  AgentAvatarLoopMode,
  AgentAvatarSpriteSequence,
  AgentAvatarSpriteState,
  AgentAvatarTransition,
} from "../../config/types.base.js";
import type { GatewayHttpAssetsConfig } from "../../config/types.gateway.js";
import type { OpenClawConfig } from "../../config/types.openclaw.js";
import {
  type CharacterManifest,
  DISPLAY_CAP_SPRITE_FULLBODY,
  DISPLAY_CAP_SPRITE_HEADSHOT,
  DISPLAY_MODE_FULLBODY,
  DISPLAY_MODE_HEADSHOT,
} from "../protocol/index.js";

type ModeContent = CharacterManifest["content"][string];
type Animation = ModeContent["animations"][string];
type FrameSequence = NonNullable<Animation["sequence"]>;
type FrameRef = FrameSequence["frames"][number];
type Phase = "intro" | "loop" | "outro";
type PhaseOrFlat = Phase | null;

type Synthesized = { mode: string; content: ModeContent; assets: Record<string, string> };

const DEFAULT_SPRITE_FPS = 12;
const DEFAULT_SPRITE_LOOP: AgentAvatarLoopMode = "infinite";

export type BuildCharacterManifestResult =
  | { ok: true; manifest: CharacterManifest; revision: number }
  | {
      ok: false;
      code: "unknown-agent" | "no-avatar" | "unsupported-kind" | "atlas-unreadable";
      message: string;
    };

export type BuildCharacterManifestInput = {
  cfg: OpenClawConfig;
  agentId: string;
  /** Optional request-side mode filter; when set, intersects with advertised modes. */
  modes?: readonly string[];
  /** Caps advertised by the connected client. `undefined` = operator mode, no filter. */
  caps?: readonly string[];
  /** Directory the gateway asset endpoint serves; atlas manifests resolve relative to here. */
  assetsDir?: string;
  /** Override for reading the atlas JSON from disk; used in tests. */
  readAtlasManifest?: (absolutePath: string) => Promise<unknown>;
};

// Resolve the assets root the same way the asset HTTP endpoint does so paths we
// emit in `assets.refs` match what the client will `GET /openclaw-assets/<path>`.
export function resolveAssetsDirForManifest(cfg: OpenClawConfig): string {
  const assets: GatewayHttpAssetsConfig | undefined = cfg.gateway?.http?.endpoints?.assets;
  const raw =
    typeof assets?.assetsDir === "string" && assets.assetsDir.trim().length > 0
      ? assets.assetsDir.trim()
      : "./assets";
  return path.isAbsolute(raw) ? path.resolve(raw) : path.resolve(resolveStateDir(), raw);
}

export async function buildCharacterManifest(
  input: BuildCharacterManifestInput,
): Promise<BuildCharacterManifestResult> {
  const identity = resolveAgentIdentity(input.cfg, input.agentId);
  if (!identity) {
    return { ok: false, code: "unknown-agent", message: `unknown agentId: ${input.agentId}` };
  }
  const avatar = identity.avatar;
  if (avatar === undefined || typeof avatar === "string") {
    return {
      ok: false,
      code: "no-avatar",
      message: "agent has no structured avatar (states/sprites/atlas) configured",
    };
  }

  let synthesized: Synthesized;
  if (isAgentAvatarStatesConfig(avatar)) {
    synthesized = synthesizeFromStates(avatar);
  } else if (isAgentAvatarSpritesConfig(avatar)) {
    synthesized = synthesizeFromSprites(avatar);
  } else if (isAgentAvatarAtlasConfig(avatar)) {
    const atlasResult = await synthesizeFromAtlas(avatar, input);
    if (!atlasResult.ok) {
      return atlasResult;
    }
    synthesized = atlasResult.synthesized;
  } else {
    return { ok: false, code: "unsupported-kind", message: "avatar kind not recognized" };
  }

  // v1: only the headshot mode is authored. Keep the filter honest against the
  // requested modes + client caps so clients always receive a self-consistent
  // manifest — the modes listed in `manifest.modes` are exactly those present
  // in `manifest.content`.
  const advertisedModes = [synthesized.mode];
  const allowedModes = filterModes(advertisedModes, input.modes, input.caps);

  const content: Record<string, ModeContent> = {};
  for (const mode of allowedModes) {
    if (mode === synthesized.mode) {
      content[mode] = synthesized.content;
    }
  }

  const stateMap = buildStateMap(synthesized.content);
  const manifest: CharacterManifest = {
    version: 1,
    agentId: input.agentId,
    ...(identity.name ? { name: identity.name } : {}),
    modes: allowedModes,
    stateMap,
    content,
    assets: { refs: allowedModes.length > 0 ? synthesized.assets : {} },
  };

  return { ok: true, manifest, revision: computeRevision(manifest) };
}

// ---------- kind: "states" (legacy GIF) ----------

function synthesizeFromStates(cfg: AgentAvatarStatesConfig): Synthesized {
  const animations: Record<string, Animation> = {};
  const refs: Record<string, string> = {};
  for (const [name, entry] of Object.entries(cfg.states)) {
    refs[name] = entry.file;
    animations[name] = {
      ...(entry.description ? { description: entry.description } : {}),
      sequence: {
        frames: [{ ref: name }],
        fps: DEFAULT_SPRITE_FPS,
        loop: "infinite",
      },
    };
  }
  return {
    mode: DISPLAY_MODE_HEADSHOT,
    content: { animations },
    assets: refs,
  };
}

// ---------- kind: "sprites" (per-frame images) ----------

function synthesizeFromSprites(cfg: AgentAvatarSpritesConfig): Synthesized {
  const format = cfg.format ?? "webp";
  const animations: Record<string, Animation> = {};
  const refs: Record<string, string> = {};

  for (const [name, rawState] of Object.entries(cfg.states)) {
    const { description, phases } = unwrapSpriteState(rawState);
    const anim: Animation = description ? { description } : {};
    for (const [phase, seq] of phases) {
      const { sequence, assetRefs } = buildSpriteSequence({
        stateName: name,
        phase,
        seq,
        basePath: cfg.basePath,
        format,
      });
      Object.assign(refs, assetRefs);
      if (phase === null) {
        anim.sequence = sequence;
      } else {
        anim[phase] = sequence;
      }
    }
    animations[name] = anim;
  }

  const content: ModeContent = { animations };
  if (cfg.transitions && Object.keys(cfg.transitions).length > 0) {
    content.transitions = translateTransitions(cfg.transitions);
  }

  return { mode: DISPLAY_MODE_HEADSHOT, content, assets: refs };
}

function unwrapSpriteState(state: AgentAvatarSpriteState): {
  description?: string;
  phases: Array<[PhaseOrFlat, AgentAvatarSpriteSequence]>;
} {
  if (isFlatSpriteState(state)) {
    const { description, ...seq } = state;
    return { description, phases: [[null, seq]] };
  }
  const phases: Array<[PhaseOrFlat, AgentAvatarSpriteSequence]> = [];
  if (state.intro) {
    phases.push(["intro", state.intro]);
  }
  phases.push(["loop", state.loop]);
  if (state.outro) {
    phases.push(["outro", state.outro]);
  }
  return { description: state.description, phases };
}

function isFlatSpriteState(
  state: AgentAvatarSpriteState,
): state is AgentAvatarSpriteSequence & { description?: string } {
  return (
    typeof (state as AgentAvatarSpriteSequence).count === "number" &&
    typeof (state as { loop?: unknown }).loop !== "object"
  );
}

function buildSpriteSequence(params: {
  stateName: string;
  phase: PhaseOrFlat;
  seq: AgentAvatarSpriteSequence;
  basePath: string;
  format: string;
}): { sequence: FrameSequence; assetRefs: Record<string, string> } {
  const count = Math.max(1, Math.floor(params.seq.count));
  const width = count >= 100 ? 3 : 2;
  const refs: Record<string, string> = {};
  const frames: FrameRef[] = [];
  for (let i = 0; i < count; i++) {
    const padded = String(i).padStart(width, "0");
    const refKey =
      params.phase === null
        ? `${params.stateName}/${padded}`
        : `${params.stateName}/${params.phase}/${padded}`;
    const filePath =
      params.phase === null
        ? `${params.basePath}/${params.stateName}/${padded}.${params.format}`
        : `${params.basePath}/${params.stateName}/${params.phase}/${padded}.${params.format}`;
    refs[refKey] = filePath;
    frames.push({ ref: refKey });
  }
  const sequence: FrameSequence = {
    frames,
    fps: params.seq.fps ?? DEFAULT_SPRITE_FPS,
    loop: params.seq.loop ?? DEFAULT_SPRITE_LOOP,
    ...(params.seq.holdLastFrame ? { holdLastFrame: true } : {}),
    ...(typeof params.seq.iterations === "number" ? { iterations: params.seq.iterations } : {}),
  };
  return { sequence, assetRefs: refs };
}

// ---------- kind: "atlas" (packed atlas + sibling JSON) ----------

type AtlasAnimationJson =
  | {
      frames: string[];
      fps?: number;
      loop?: AgentAvatarLoopMode;
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
  loop?: AgentAvatarLoopMode;
  holdLastFrame?: boolean;
  iterations?: number;
};

type AtlasManifestJson = {
  image?: string;
  size?: { w: number; h: number };
  frameSize?: { w: number; h: number };
  frames?: Record<string, { x: number; y: number; w: number; h: number }>;
  animations?: Record<string, AtlasAnimationJson>;
  transitions?: Record<string, AgentAvatarTransition>;
};

type AtlasSynthesisResult =
  | { ok: true; synthesized: Synthesized }
  | Extract<BuildCharacterManifestResult, { ok: false }>;

async function synthesizeFromAtlas(
  cfg: AgentAvatarAtlasConfig,
  input: BuildCharacterManifestInput,
): Promise<AtlasSynthesisResult> {
  const assetsDir = input.assetsDir ?? resolveAssetsDirForManifest(input.cfg);
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

  // Asset refs for atlas: a single whole-image ref keyed by its on-disk filename.
  // Frame refs reuse the atlas image ref with explicit x/y/w/h rects.
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
    loop?: AgentAvatarLoopMode;
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
  loop?: AgentAvatarLoopMode;
  holdLastFrame?: boolean;
  iterations?: number;
  framesMap: Record<string, { x: number; y: number; w: number; h: number }>;
  atlasRefKey: string;
}): FrameSequence {
  const frames: FrameRef[] = params.frames.map((key) => {
    const rect = params.framesMap[key];
    if (!rect) {
      // Missing rect means the atlas packer skipped it; emit a plain ref so the
      // runtime can surface "unknown frame" in logs rather than silently drop.
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
    fps: params.fps ?? DEFAULT_SPRITE_FPS,
    loop: params.loop ?? DEFAULT_SPRITE_LOOP,
    ...(params.holdLastFrame ? { holdLastFrame: true } : {}),
    ...(typeof params.iterations === "number" ? { iterations: params.iterations } : {}),
  };
}

// ---------- shared helpers ----------

function translateTransitions(
  src: Record<string, AgentAvatarTransition>,
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
  // Detect the presence of display caps — only then do we treat `caps` as a
  // filter. A client that never advertised any display cap (typical for CLI
  // operators) gets everything.
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
  // Unknown modes default allowed — the manifest author chose to emit them and
  // a future cap constant will refine this when it lands.
  return true;
}

function buildStateMap(content: ModeContent): Record<string, string> {
  // v1: identity mapping from agent state name to animation name. The
  // synthesizer authors animations keyed by state, so Idle→neutral etc. can
  // come through later once agent state vocabulary is formalized.
  const map: Record<string, string> = {};
  for (const name of Object.keys(content.animations)) {
    map[name] = name;
  }
  return map;
}

// FNV-1a 32-bit hash so the same manifest bytes always produce the same
// revision without needing a counter or persistent store. Collisions are fine
// here — revision only needs to change when the manifest changes, which is
// exactly what a content hash gives us.
function computeRevision(manifest: CharacterManifest): number {
  const bytes = Buffer.from(JSON.stringify(manifest), "utf8");
  let hash = 0x811c9dc5;
  for (let i = 0; i < bytes.length; i++) {
    hash ^= bytes[i];
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  // Clamp to a non-negative 31-bit integer so the schema's `minimum: 0`
  // constraint holds even under JSON round-tripping via languages without an
  // unsigned-int type.
  return hash & 0x7fffffff;
}

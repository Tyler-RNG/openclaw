/**
 * Resolves public avatar sources for configured agent identities.
 */
import fs from "node:fs";
import path from "node:path";
import { normalizeOptionalString } from "@openclaw/normalization-core/string-coerce";
import type { OpenClawConfig } from "../config/types.openclaw.js";
import { normalizeAgentId } from "../routing/session-key.js";
import {
  AVATAR_MAX_BYTES,
  hasAvatarUriScheme,
  isAvatarDataUrl,
  isAvatarHttpUrl,
  isWindowsAbsolutePath,
  isPathWithinRoot,
  isSupportedLocalAvatarExtension,
} from "../shared/avatar-policy.js";
import { resolveUserPath } from "../utils.js";
import { resolveAgentWorkspaceDir, resolveDefaultAgentId } from "./agent-scope.js";
import {
  buildAvatarStateInstruction,
  isAgentAvatarStatesConfig,
  type AgentAvatarStatesConfig,
} from "./identity-avatar-states.js";
import { loadAgentIdentityFromWorkspace } from "./identity-file.js";
import { resolveAgentIdentity } from "./identity.js";

// Agent avatar resolution for UI/public surfaces. Remote/data sources are
// allowed directly; local files must stay inside the agent workspace and satisfy
// shared avatar policy limits.
export type AgentAvatarResolution =
  | { kind: "none"; reason: string; source?: string }
  | { kind: "local"; filePath: string; source: string }
  | { kind: "remote"; url: string; source: string }
  | { kind: "data"; url: string; source: string }
  | {
      kind: "states";
      default: string;
      states: AgentAvatarStatesConfig["states"];
      instruction: string;
    };

type AgentAvatarPublicSourceInput = {
  kind: AgentAvatarResolution["kind"];
  source?: string | null;
};

const PUBLIC_AVATAR_SOURCE_MAX_CHARS = 256;
const PUBLIC_DATA_AVATAR_HEADER_MAX_CHARS = 64;

function resolveRawAvatarValue(
  cfg: OpenClawConfig,
  agentId: string,
  opts?: { includeUiOverride?: boolean },
): unknown {
  const normalizedAgentId = normalizeAgentId(agentId);
  const defaultAgentId = normalizeAgentId(resolveDefaultAgentId(cfg));
  const fromUiConfig = cfg.ui?.assistant?.avatar;
  if (opts?.includeUiOverride) {
    // UI override only wins for the default agent unless callers explicitly ask
    // for it as a final fallback for non-default agents.
    if (normalizedAgentId === defaultAgentId && fromUiConfig !== undefined) {
      return fromUiConfig;
    }
  }
  const fromConfig = resolveAgentIdentity(cfg, normalizedAgentId)?.avatar;
  if (fromConfig !== undefined) {
    return fromConfig;
  }
  const workspace = resolveAgentWorkspaceDir(cfg, normalizedAgentId);
  const fromIdentity = loadAgentIdentityFromWorkspace(workspace)?.avatar;
  if (fromIdentity !== undefined) {
    return fromIdentity;
  }
  return opts?.includeUiOverride ? fromUiConfig : undefined;
}

function resolveAvatarSource(
  cfg: OpenClawConfig,
  agentId: string,
  opts?: { includeUiOverride?: boolean },
): string | null {
  const raw = resolveRawAvatarValue(cfg, agentId, opts);
  if (typeof raw !== "string") {
    return null;
  }
  return normalizeOptionalString(raw) ?? null;
}

function resolveExistingPath(value: string): string {
  try {
    return fs.realpathSync(value);
  } catch {
    return path.resolve(value);
  }
}

function resolveLocalAvatarPath(params: {
  raw: string;
  workspaceDir: string;
}): { ok: true; filePath: string } | { ok: false; reason: string } {
  const workspaceRoot = resolveExistingPath(params.workspaceDir);
  const raw = params.raw;
  const resolved =
    raw.startsWith("~") || path.isAbsolute(raw)
      ? resolveUserPath(raw)
      : path.resolve(workspaceRoot, raw);
  const realPath = resolveExistingPath(resolved);
  // Resolve symlinks before the workspace check so local avatar paths cannot
  // escape the workspace through link traversal.
  if (!isPathWithinRoot(workspaceRoot, realPath)) {
    return { ok: false, reason: "outside_workspace" };
  }
  if (!isSupportedLocalAvatarExtension(realPath)) {
    return { ok: false, reason: "unsupported_extension" };
  }
  try {
    const stat = fs.statSync(realPath);
    if (!stat.isFile()) {
      return { ok: false, reason: "missing" };
    }
    if (stat.size > AVATAR_MAX_BYTES) {
      return { ok: false, reason: "too_large" };
    }
  } catch {
    return { ok: false, reason: "missing" };
  }
  return { ok: true, filePath: realPath };
}

function isSafeRelativeAvatarSource(source: string): boolean {
  if (
    source.length > PUBLIC_AVATAR_SOURCE_MAX_CHARS ||
    source.startsWith("~") ||
    path.isAbsolute(source) ||
    isWindowsAbsolutePath(source) ||
    (hasAvatarUriScheme(source) && !isWindowsAbsolutePath(source)) ||
    source.includes("\0")
  ) {
    return false;
  }
  const parts = source.replace(/\\/g, "/").split("/");
  return parts.every((part) => part !== "..");
}

/** Return a safe public description of the configured avatar source. */
export function resolvePublicAgentAvatarSource(
  resolved: AgentAvatarPublicSourceInput,
): string | undefined {
  const source = normalizeOptionalString(resolved.source) ?? null;
  if (!source) {
    return undefined;
  }
  if (isAvatarDataUrl(source)) {
    // Data URLs can be large and sensitive; expose only the media/header prefix.
    const commaIndex = source.indexOf(",");
    const header =
      commaIndex > 0
        ? source.slice(0, Math.min(commaIndex, PUBLIC_DATA_AVATAR_HEADER_MAX_CHARS))
        : source.slice(0, PUBLIC_DATA_AVATAR_HEADER_MAX_CHARS);
    return `${header},...`;
  }
  if (isAvatarHttpUrl(source)) {
    return "remote URL";
  }
  return isSafeRelativeAvatarSource(source) ? source : undefined;
}

/** Resolve the effective avatar for an agent, including config and IDENTITY.md. */
export function resolveAgentAvatar(
  cfg: OpenClawConfig,
  agentId: string,
  opts?: { includeUiOverride?: boolean },
): AgentAvatarResolution {
  const raw = resolveRawAvatarValue(cfg, agentId, opts);
  if (isAgentAvatarStatesConfig(raw)) {
    return {
      kind: "states",
      default: raw.default,
      states: raw.states,
      instruction: buildAvatarStateInstruction(raw),
    };
  }
  const source = resolveAvatarSource(cfg, agentId, opts);
  if (!source) {
    return { kind: "none", reason: "missing" };
  }
  if (isAvatarHttpUrl(source)) {
    return { kind: "remote", url: source, source };
  }
  if (isAvatarDataUrl(source)) {
    return { kind: "data", url: source, source };
  }
  const workspaceDir = resolveAgentWorkspaceDir(cfg, agentId);
  const resolved = resolveLocalAvatarPath({ raw: source, workspaceDir });
  if (!resolved.ok) {
    return { kind: "none", reason: resolved.reason, source };
  }
  return { kind: "local", filePath: resolved.filePath, source };
}

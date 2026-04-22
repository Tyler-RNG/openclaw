/**
 * Resolver for plugin-registered system-prompt contributions.
 *
 * Callers collect contributions from every loaded plugin whose `register()`
 * called `api.registerSystemPromptContribution`. Ordering is deterministic
 * (sorted by plugin id) so the rendered prompt block stays byte-identical
 * across turns — critical for prompt-cache reuse (see the repo's
 * "Prompt Cache Stability" guidance).
 *
 * Pairs with:
 * - `src/plugins/provider-runtime.ts#resolveProviderSystemPromptContribution`
 *   (provider-specific hook, unchanged) — invoked separately by callers.
 * - `src/agents/merge-system-prompt-contributions.ts` — merges the provider
 *   result with the list this resolver returns into a single contribution.
 */

import type { SystemPromptContribution } from "../agents/system-prompt-contribution.js";
import type { OpenClawConfig } from "../config/types.openclaw.js";
import type { PluginSystemPromptContributionRegistration } from "./registry-types.js";
import { getPluginRegistryState } from "./runtime-state.js";
import type { SystemPromptContributionContext } from "./types.js";

export type CollectPluginSystemPromptContributionsParams = {
  /**
   * Resolved OpenClaw config. Currently unused by the collector — the active
   * plugin registry is the source of truth — but accepted so the call site
   * shape matches `resolveProviderSystemPromptContribution` and so future
   * policy (e.g., config-level disable flags) has a place to land.
   */
  config?: OpenClawConfig;
  workspaceDir?: string;
  env?: NodeJS.ProcessEnv;
  context: SystemPromptContributionContext;
};

/**
 * Collect non-null contributions from all registered plugin hooks.
 *
 * Returns results in deterministic plugin-id order. Individual plugin hooks
 * that throw are swallowed (with a silent skip) so one misbehaving plugin
 * cannot break the prompt assembly for every agent.
 */
export function collectPluginSystemPromptContributions(
  params: CollectPluginSystemPromptContributionsParams,
): SystemPromptContribution[] {
  const registrations = readRegistrations();
  if (registrations.length === 0) {
    return [];
  }
  const results: SystemPromptContribution[] = [];
  for (const reg of registrations) {
    let contribution: SystemPromptContribution | null | undefined;
    try {
      contribution = reg.contribute(params.context);
    } catch {
      // Plugin hook threw — skip. A diagnostic path would belong in the
      // registry layer; the caller shouldn't see a partial/corrupted prompt.
      continue;
    }
    if (!contribution) {
      continue;
    }
    results.push(contribution);
  }
  return results;
}

function readRegistrations(): PluginSystemPromptContributionRegistration[] {
  const state = getPluginRegistryState();
  const registry = state?.activeRegistry;
  if (!registry) {
    return [];
  }
  const list = registry.systemPromptContributions;
  if (!Array.isArray(list) || list.length === 0) {
    return [];
  }
  // Deterministic order by pluginId for prompt-cache stability. toSorted
  // returns a new array without mutating the registry's stored order.
  return list.toSorted((a, b) => {
    if (a.pluginId < b.pluginId) {
      return -1;
    }
    if (a.pluginId > b.pluginId) {
      return 1;
    }
    return 0;
  });
}

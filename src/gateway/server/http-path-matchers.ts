// Pure path-matcher functions shared between the HTTP dispatcher in
// `server-http.ts` and each endpoint's handler module (e.g. `assets-http.ts`,
// `tts-http.ts`). Kept in a dependency-free module so the dispatcher can
// import them eagerly without pulling in the handler's full runtime graph —
// handlers themselves stay behind the lazy-import boundary.

export function isAssetsHttpPath(pathname: string): boolean {
  return pathname === "/openclaw-assets" || pathname.startsWith("/openclaw-assets/");
}

const STREAM_TTS_PATHS: ReadonlySet<string> = new Set(["/stream/tts", "/tts"]);

export function isStreamTtsHttpPath(pathname: string): boolean {
  return STREAM_TTS_PATHS.has(pathname);
}

/**
 * Path matchers for built-in API endpoints whose handlers are feature-flagged.
 * Lets the dispatcher respond with a clear 503 JSON when the path is a known
 * API but the flag is off — instead of cascading to the control-UI SPA and
 * returning `text/html`, which was the nastiest possible error to diagnose.
 * Keyed by the config path that gates the endpoint so the error message can
 * tell the caller exactly what to toggle.
 */
export const FEATURE_FLAGGED_API_PATHS: ReadonlyArray<{
  configPath: string;
  matches: (pathname: string) => boolean;
}> = [
  { configPath: "gateway.http.endpoints.assets.enabled", matches: isAssetsHttpPath },
  { configPath: "gateway.http.endpoints.streamTts.enabled", matches: isStreamTtsHttpPath },
];

# Gateway Data Plane

Canonical implementation of the HTTP data plane described in
[`docs/gateway/data-plane.md`](../../docs/gateway/data-plane.md) — the
out-of-process counterpart to the Gateway's WebSocket control plane that
serves binary assets and streams media.

**Use this when your Gateway runs as a compiled binary** and you can't
patch its HTTP layer directly. Once native data-plane support lands in the
Gateway, this sidecar can be retired.

## Layout

```
gateway-data-plane/
├── README.md          ← you are here
└── sidecar/           ← Node/Express implementation (see its README)
    ├── server.js
    ├── package.json
    ├── config.example.json
    ├── test.js
    └── systemd/
        ├── openclaw-sidecar.service.example
        └── sidecar.env.example
```

See [`sidecar/README.md`](./sidecar/README.md) for architecture, install,
systemd, security notes, and verification commands.

## Quick start

```bash
cd sidecar
npm install
cp config.example.json config.json      # fill in your tokens (git-ignored)
ELEVENLABS_API_KEY=... node server.js
```

Then add the matching `dataPlane` block to the Gateway's `openclaw.json`:

```json
{
  "dataPlane": {
    "baseUrl": "https://<machine>.<tailnet>.ts.net",
    "publicAssets": false,
    "streamTts": true
  }
}
```

Restart the Gateway and the Android companion app's
`config.get → dataPlane` will pick up the new values on reconnect.

# ScreenStream Uni-Fi

Unified ScreenStream web viewer and WebSocket signaling stack.

## Components

- `web/` — browser-based ScreenStream viewer
- `signaling/` — WebSocket signaling relay
- `docker-compose.yml` — runs both services together

## Architecture

Browser ⇄ WebSocket signaling ⇄ Android

WebRTC carries the actual media directly between the browser and Android when network conditions allow. The signaling service only relays WebRTC signaling/control messages.

## Run

```bash
docker compose up -d --build
```

Web UI is exposed on `127.0.0.1:8090` and signaling on `127.0.0.1:8091` by default.

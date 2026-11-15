# RPAuto (RP自动化)

> **Root-free Android app hosting & automation platform** — licensed under [MIT](LICENSE).

RPAuto creates a **VirtualDisplay** on your Android device (no root required), hosts a third-party
app inside it, and exposes **screen viewing, input injection, app hosting and automation** to the
outside world over REST, MCP and video streaming (MJPEG / WebRTC). It is designed for **local
network or USB only** — no public / internet exposure.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-3DDC84.svg)]()
[![API: 24+](https://img.shields.io/badge/API-24+-blue.svg)]()
[![arch: arm64](https://img.shields.io/badge/arch-arm64--v8a-lightgrey.svg)]()

## Highlights

- **No root** — uses the shell context obtained via wireless-debugging pairing to create a private
  `trusted VirtualDisplay` and inject input.
- **Two hosting modes**
  - **Mode A (VirtualDisplay, default)** — the hosted app runs on a private virtual screen.
  - **Mode B (main screen)** — the hosted app is brought to the foreground via MediaProjection
    capture, unlocking structured UI reading and stable gestures on ROMs where a virtual screen's
    accessibility tree is unavailable (e.g. MIUI).
- **Host & operate any app** — screenshot, tap, swipe, key injection, text input (incl. Chinese),
  and read the UI tree (flat / hierarchical / XPath / resourceId).
- **Multiple external interfaces** — REST API, MCP server (Streamable HTTP + SSE), MJPEG stream,
  WebRTC console, and an in-browser playback/control client (`/client`).
- **Automation service** — an isolated `:node` foreground service with a built-in Node engine
  (v26, arm64). Write JS scripts and run them **immediately / on an interval / at a scheduled time /
  via cron**.
- **Token auth** — `Authorization: Bearer`, `X-Auth-Token` or `?token=`.

## Interface & Documentation

| Doc | Content |
|-----|---------|
| [docs/API.md](docs/API.md) | Network access, auth, session bootstrap, REST endpoints, task API, UI reading |
| [docs/MCP.md](docs/MCP.md) | MCP Streamable HTTP / SSE, JSON-RPC methods, tool list & call examples |
| [docs/Client.md](docs/Client.md) | In-browser playback/control client (`/client`) |
| [docs/WebRTC.md](docs/WebRTC.md) | Low-latency WebRTC console (`/webrtc`) |
| [docs/MJPEG.md](docs/MJPEG.md) | MJPEG stream endpoint, auth, viewers & concurrency limits |
| [docs/TASK.md](docs/TASK.md) | Full JS script API (`host.invoke`) for automation tasks |

## Quick Start

```bash
# 1) Forward the port over USB (recommended for development)
adb forward tcp:8099 tcp:8099

# 2) Health check (no token required)
curl http://localhost:8099/health

# 3) Establish a hosted session (needs prior wireless-debugging pairing once)
curl -X POST http://localhost:8099/debug/session/start \
  -H 'Content-Type: application/json' \
  -d '{"connectPort":<adbd port>}'

# 4) Check status
curl -H "Authorization: Bearer <token>" http://localhost:8099/status

# 5) Host an app and capture a frame
curl -X POST http://localhost:8099/session/launch \
  -H "Authorization: Bearer <token>" -H 'Content-Type: application/json' \
  -d '{"pkg":"com.android.settings"}'
curl -H "Authorization: Bearer <token>" http://localhost:8099/frame -o screen.png
```

> **First run**: enable **wireless debugging** on the device, pair it once with a pairing code
> (设置 → 开发者选项 → 无线调试 → 使用配对码配对设备), then start a session. The cached
> `connectPort` is restored automatically after an app restart.
>
> Do **not** use `adb reverse` — it makes adbd occupy the device-side port and the app fails to bind.

### LLM / Agent access (MCP)

Add to your MCP client:

```json
{
  "mcpServers": {
    "RPAuto": {
      "url": "http://192.168.x.x:8099/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

23 tools are exposed (`tools/list`), e.g. `screen_capture`, `screen_tap`, `ui_query` (XPath),
`app_launch`, `task_create`, `task_run`, `logs`. For an authoritative list of signatures call
`task_docs` before having a model generate scripts.

### Automation script example

```js
await host.invoke('screen.launch', { pkg: 'com.android.settings' });
await new Promise(r => setTimeout(r, 2000));
const info = await host.invoke('screen.info', {});
await host.invoke('screen.swipe', {
  x1: info.w / 2, y1: info.h * 0.75,
  x2: info.w / 2, y2: info.h * 0.25, durMs: 500
});
const f = await host.invoke('screen.frame', {});
await host.invoke('log', { msg: 'captured ok=' + f.ok });
```

## Project Structure

| Module | Responsibility |
|--------|----------------|
| `app` | Config UI, presentation modes, automation service, runtime wiring |
| `core/shell` | Root-free ADB client, shell helper, VirtualDisplay & input injection |
| `core/controller` | `ScreenController` — the single write entry (hosting session, screen control) |
| `core/server` | Ktor REST, MCP, MJPEG, WebRTC, playback client, unified logging |
| `core/script` | Automation task model, cron parsing, Node bridge |
| `core/webrtc` | WebRTC push / signaling |

All external-facing modules and presentation modes go through the single `ScreenController`,
so user touch, REST, MCP and automation scripts operate on the **same** hosted instance.

## Build

```bash
./gradlew :app:assembleDebug
```

- minSdk 24, compileSdk 36, **arm64-v8a only**.
- Signing keystore is **not** committed; copy `keystore.properties.sample` to
  `keystore.properties` and provide your own keys (see `docs/release-signing.md`).

## Constraints & Notes

- Local network (same Wi-Fi) or USB `adb forward` only — **no public internet, no TURN/relay**.
- The automation service runs in a separate `:node` process; it does not auto-start on device boot —
  launch the app or trigger via alarm / interface.
- Reading the UI tree (Mode A on some ROMs, e.g. MIUI) may be unavailable — fall back to
  `screen.frame` + coordinates, or use Mode B for structured UI reading.
- Sensitive values (real tokens, device IPs, credentials) are never committed to this repo.

## License

This project is open source under the [MIT License](LICENSE). You are free to use, modify,
distribute and even use it commercially, as long as you retain the original copyright and
permission notice.

> Use it only on devices you own or are authorized to control, and comply with your local laws.
> The author is not responsible for any misuse of this software.

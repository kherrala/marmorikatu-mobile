# Marmorikatu Mobile

Kotlin Multiplatform app (Android + iOS, Compose Multiplatform) for the
[Marmorikatu home automation system](../marmorikatu-home-automation). Family
phones get vital diagnostics plus control of lights, HVAC status, and the TV;
the same app will later run on the shelf tablet as the kiosk replacement.

The UI design system arrives from a separate design session — the current
`DebugScreen` is a temporary developer screen that exercises every capability
below the UI.

## Architecture

Two Gradle modules plus the Xcode wrapper:

- **`core`** — everything below the UI: config, transports, repositories,
  voice plumbing. Pure Kotlin, no Compose.
- **`composeApp`** — Compose Multiplatform UI (currently the debug screen)
  and app entry points. Builds the iOS framework (`ComposeApp`).
- **`iosApp`** — Xcode project embedding the framework.

### Transports (hybrid by design)

| Transport | Used for | Endpoint |
|---|---|---|
| MQTT (MQTTastic, TCP) | Live state (retained topics, instant snapshot) + single light commands | `freenas.kherrala.fi:1883` |
| MCP (official Kotlin SDK, streamable HTTP) | Light catalog + batch commands, TV (Harmony), heat pump/sauna/energy/prices reads, weather/news/calendar | `http://192.168.1.160:3001/mcp/` |
| claude-bridge (Ktor + hand-rolled SSE/NDJSON) | AI chat streaming, Whisper STT, Piper TTS, announcements feed | `http://192.168.1.160:3002` |
| Direct HTTP | Nysse bus departures | `http://192.168.1.160:3010` |

Repositories (`fi.marmorikatu.core.repository.*`) hide the transport mix;
the UI layer only ever sees interfaces and `Flow`s. Light toggles are
optimistic: `Reconciler` shows the desired state immediately and reverts if
the retained topic doesn't confirm within 20 s (PLC republishes ~13 s).

Indices present in `marmorikatu/lights` but unnamed in
`marmorikatu/names/lights` (e.g. 21, 27) are gaps in the PLC's `Controls[]`
array and are deliberately never rendered or controllable — the backend
skips them too.

### Networking model

Plain HTTP everywhere. On the LAN it just works; away from home the UniFi
gateway VPN puts the phone on the LAN — the app is deliberately unaware of
the difference. The LAN is the security boundary (the backend services have
no authentication by design). Android allows cleartext via
`network_security_config.xml`; iOS via ATS local-networking exceptions.

If `freenas.kherrala.fi` doesn't resolve over the VPN, override the MQTT host
with the NAS LAN IP in the debug screen's settings.

### Voice

Pluggable engines behind `SpeechToText` / `SpeechOutput`:

- **Server** (default-quality path): record m4a → `POST /transcribe`
  (faster-whisper on the GPU box) → `POST /chat/stream` → per-sentence Piper
  WAV clips played through `AudioPlayer`. Same Finnish "house voice" as the
  kiosk.
- **Native**: Android `SpeechRecognizer`/`TextToSpeech`; iOS
  `AVSpeechSynthesizer` (TTS works; native iOS STT deliberately stubbed until
  the AVAudioEngine capture path is built). Runtime capability checks decide
  the fallback; both are A/B-switchable on the debug screen.

### Heat pump: read-only on purpose

The backend `indoor` service republishes `INDR_T` to the Thermia every 60 s,
and persistent register writes wear the pump's flash. The app therefore ships
**no HVAC write path** until a safe setpoint/bias knob exists server-side
(see backend follow-ups below).

## Building

```bash
# Android (requires Android SDK; JDK 17)
./gradlew :composeApp:assembleDebug

# Unit tests (payload parsers vs live-captured fixtures, SSE/NDJSON, reconciler)
./gradlew :core:testDebugUnitTest

# iOS framework compile check (requires Xcode for linking; klib compile works without)
./gradlew :core:compileKotlinIosArm64

# Full iOS app: open iosApp/iosApp.xcodeproj in Xcode and run
```

## Live verification checklist

Pre-flight from a LAN machine:

```bash
curl http://192.168.1.160:3002/health          # bridge + MCP + models
curl http://192.168.1.160:3001/health          # MCP server
curl -N http://192.168.1.160:3002/announcements/stream --max-time 25
mosquitto_sub -h freenas.kherrala.fi -t 'marmorikatu/#' -v -C 12
```

App acceptance (emulator → simulator → physical → cellular+VPN):

1. Fresh launch → connection tiles green ≤ 5 s; room temps appear instantly
   (retained snapshot).
2. Toggle a light in-app → physical light switches, state confirms ≤ 15 s;
   toggle externally (`mosquitto_pub -h freenas.kherrala.fi -t
   'marmorikatu/light/51/set' -m 'true' -q 1`, idx 51 = Biljardipöytä) →
   app reflects it.
3. Kill Wi-Fi 30 s → tiles red → restore → auto-recovery without restart.
4. Background 1 min → foreground → MQTT reconnects, announcements resume
   via `Last-Event-ID`.
5. Finnish voice round trip: "Sytytä biljardipöydän valo" → transcript →
   tool call → audio reply → light on.
6. TV tile lists Harmony activities (needs `HARMONY_HUB_HOST` server-side)
   or shows a clean error.
7. iPhone first run: Local Network + Microphone prompts appear with Finnish
   rationale; a denial shows as red tiles, not silent hangs.
8. Repeat 1–2 on cellular + UniFi VPN.

## Backend follow-ups (in ../marmorikatu-home-automation)

1. Set `HARMONY_HUB_HOST` in `.env` + restart the `mcp` service (TV prereq).
2. Add a safe HVAC setpoint/bias knob on the `indoor` service (REST or MCP
   tool) — the app must never write `ThermIQ/marmorikatu/set` directly.
3. Optional: `audio=false` flag on `/chat/stream` so native-TTS clients skip
   unused base64 WAV payloads.
4. Optional: publish weather/news/calendar ports if richer widget data than
   the MCP tools provide is ever needed.

## Known gaps

- **iOS native STT is stubbed.** `PlatformStt` on iOS reports a clear error so
  the engine switcher falls back to server Whisper; live capture needs
  `AVAudioEngine` + `SFSpeechAudioBufferRecognitionRequest`. Native TTS
  (`AVSpeechSynthesizer`, fi-FI) is implemented.
- **iOS was compiled, not run.** No Xcode on the current dev machine, so the
  iOS framework compiles for `iosArm64`/`iosSimulatorArm64` but the app has
  not been launched on a simulator/device. Checklist items 1–8 are verified on
  Android against the live system.
- **`AVAudioSession.recordPermission` is deprecated** on iOS 17+ in favour of
  `AVAudioApplication`; still functional, worth migrating when Xcode is set up.
- **No history charts yet** — InfluxDB Flux client is deferred until the design
  session needs them.

## Test fixtures

`core/src/commonTest/.../fixtures/MqttFixtures.kt` contains retained payloads
captured verbatim from the live broker (2026-07-09). If the PLC publisher
schema changes, re-capture with any MQTT client subscribed to
`marmorikatu/#` and update the constants.

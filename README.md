# Marmorikatu Mobile

Kotlin Multiplatform app (Android + iOS, Compose Multiplatform) for the
[Marmorikatu home automation system](../marmorikatu-home-automation). Family
phones get vital diagnostics plus control of lights, HVAC status, and the TV;
the same app will later run on the shelf tablet as the kiosk replacement.

The UI implements the design system from the Claude Design session (see below).
A developer diagnostics screen still lives behind a long-press on the
"Marmorikatu" kicker, and under Asetukset → Diagnostiikka.

## Architecture

Two Gradle modules plus the Xcode wrapper:

- **`core`** — everything below the UI: config, transports, repositories,
  voice plumbing. Pure Kotlin, no Compose.
- **`composeApp`** — Compose Multiplatform UI: theme, icons, the component
  library, six screens, and the three surfaces. Builds the iOS framework.
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

**Light commands are paced.** The PLC silently drops commands that arrive faster
than its scan cycle: a measured burst of eight publishes landed as seven, with no
error anywhere. `DefaultLightsRepository` therefore queues commands and spaces
them 150 ms apart (the backend's own batch helper uses 100 ms). `LightsPacingTest`
locks this in.

**Room identity lives in `Rooms`.** The PLC's MQTT keys and the legacy InfluxDB
field names disagree — `yk_aatu` is stored as `MH_Seela` — so labelling a chart
from an Influx field name would show the wrong child's bedroom.

### Networking model

Plain HTTP everywhere. On the LAN it just works; away from home the UniFi
gateway VPN puts the phone on the LAN — the app is deliberately unaware of
the difference. The LAN is the security boundary (the backend services have
no authentication by design). Android allows cleartext via
`network_security_config.xml`; iOS via ATS local-networking exceptions.

If `freenas.kherrala.fi` doesn't resolve over the VPN, override the MQTT host
with the NAS LAN IP in the diagnostics screen.

Announcements can keep arriving while the app is closed: **Asetukset → Kuuntele
taustalla** starts an Android foreground service that posts a notification per
event. Priority-0 alerts always vibrate, regardless of the haptics preference.

### Voice

Pluggable engines behind `SpeechToText` / `SpeechOutput`:

- **Server** (default-quality path): record m4a → `POST /transcribe`
  (faster-whisper on the GPU box) → `POST /chat/stream` → per-sentence Piper
  WAV clips played through `AudioPlayer`. Same Finnish "house voice" as the
  kiosk.
- **Native**: Android `SpeechRecognizer`/`TextToSpeech`; iOS `SFSpeechRecognizer`
  streaming AVAudioEngine buffers, plus `AVSpeechSynthesizer`. Runtime capability
  checks decide the fallback (Finnish STT is not guaranteed on every iOS device).
  Native engines are the default; both are switchable in Asetukset. Listening
  times out after 12 s, and recording is capped at 30 s.

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

# Full iOS app: open iosApp/iosApp.xcodeproj in Xcode and run.
# From the CLI the arch must be pinned — Gradle declares only iosSimulatorArm64
# (MQTTastic publishes no iosX64), so a generic simulator destination fails:
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -sdk iphonesimulator -configuration Debug ARCHS=arm64 ONLY_ACTIVE_ARCH=YES build
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

## Design system

The UI implements the Claude Design project *Mobile app design system integration*
(`MarmorikatuApp.dc.html` + six screens). Tokens, type and motion come from that
project's `_ds/` sources:

- `theme/` — colour tokens (dark + daylight), the three families (Space Grotesk,
  Hanken Grotesk, IBM Plex Mono, instanced from variable fonts and bundled), the
  4px spacing grid, radii, and the signature glow/breathe/pulse motion.
- `icons/MkIcons.kt` — 69 Phosphor glyphs generated from upstream SVG paths (no
  Compose Phosphor library exists). Regenerate with `scratchpad/gen_icons.py`.
- `components/` — the 33-component library (Card, StatTile, AreaLightCard,
  ClimateCard, VoiceDock, NavRail, LineChart, PriceBars, …).
- `screens/` + `shell/` — the six screens and the phone / kid / tablet surfaces.

Every measured value is a mono, tabular readout; UPPERCASE is reserved for mono
micro-labels. **The design's sample values are placeholders** — the app renders
repository data or an honest "Ei tietoa".

## Known gaps

- **iOS runs, but only on the simulator.** Launched on an iPhone 17 Pro
  simulator (iOS 26.5) against the live house: MQTT, MCP, the announcements SSE
  feed, fonts and icons all work. Not yet run on a physical iPhone, where the
  Local Network prompt and the microphone are the things to watch.
- **iOS native STT is unverified.** The `SFSpeechRecognizer` + `AVAudioEngine`
  path compiles and links, but the simulator has no usable microphone, so it has
  never transcribed anything. If Finnish is unsupported on the device it falls
  back to server Whisper automatically.
- **`AVAudioSession.recordPermission` is deprecated** on iOS 17+ in favour of
  `AVAudioApplication`; still functional, worth migrating.
- **Heat pump feed is dead.** The ThermIQ collector last wrote to InfluxDB on
  2026-06-26, so COP, hot water and the indoor setpoint have no live source. The
  app shows "Ei tietoa" and a read-only "—" target rather than a stale number.
  Compressor state still works: it is derived from the pump's own energy meter.
- **iOS background mode is impossible** without server push. Android keeps the
  announcements stream alive in a foreground service; iOS suspends sockets. The
  settings sheet says so rather than offering a dead switch.
- **Camera stills only arrive inside announcements.** There is no camera API, so
  a card shows an image only when the announcer attached one.

## Test fixtures

`core/src/commonTest/.../fixtures/MqttFixtures.kt` contains retained payloads
captured verbatim from the live broker (2026-07-09). If the PLC publisher
schema changes, re-capture with any MQTT client subscribed to
`marmorikatu/#` and update the constants.

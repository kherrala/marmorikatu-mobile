# Architecture & data flow

How Marmorikatu Mobile is put together: a two-module Kotlin Multiplatform split, repositories that hide a hybrid transport mix behind Flows, three auto-selected UI surfaces, optimistic light control, and a connection lifecycle tied to app foreground/background.

## Module split

Two Gradle modules plus an Xcode wrapper. `settings.gradle.kts` includes only the first two; `iosApp` is a plain Xcode project, not a Gradle module.

- **`core`** (`fi.marmorikatu.core`) — everything below the UI: config, transports, repositories, models, connection lifecycle, audio, haptics, and speech. Pure Kotlin, **no Compose**. `core/build.gradle.kts` applies only the Kotlin Multiplatform, serialization, and Android-library plugins, and targets `androidTarget()`, `iosArm64()`, and `iosSimulatorArm64()`. Its dependencies are the transport/plumbing stack (coroutines, kotlinx-serialization, Ktor client, Koin, Kermit, multiplatform-settings, the MCP Kotlin SDK, and MQTTastic).
- **`composeApp`** (`fi.marmorikatu.app`) — the Compose Multiplatform UI: theme, icons, the component library, the seven screens, and the three surfaces. `composeApp/build.gradle.kts` adds the Compose plugins, declares `api(project(":core"))`, and builds the static `ComposeApp` framework (`isStatic = true`, `export(project(":core"))`) consumed by iOS.
- **`iosApp`** — the Xcode project that embeds the `ComposeApp` framework and launches the shared UI (bundle id `fi.marmorikatu.app`).

The dependency arrow only ever points one way: `composeApp` depends on `core`; `core` never sees Compose or the UI.

## Repositories hide the transport mix

The core principle: the UI never touches a socket or an HTTP call. Everything in `core/src/commonMain/kotlin/fi/marmorikatu/core/repository/` exposes a plain interface plus Kotlin `Flow`s, and internally fans a request out to whichever transport is appropriate (MQTT, MCP, the claude-bridge, InfluxDB, or direct HTTP — see [Protocols](protocols.md) for the full map).

Two illustrative repositories:

- **`LightsRepository`** (`repository/LightsRepository.kt`) exposes `lights: StateFlow<List<Light>>` (the catalog joined with live state and optimistic pending commands), a `controlFailures: SharedFlow<Int>`, and suspend commands `setLight`/`setAll`/`setFloor`. Internally `DefaultLightsRepository` merges an MCP-provided name/floor catalog with the retained MQTT light-state and light-names topics, and routes commands to MQTT or MCP.
- **`ClimateRepository`** (`repository/ClimateRepository.kt`) exposes read-only `StateFlow`s for room temperatures, ventilation, cooling, PLC status, live Ruuvi readings keyed by sensor name, and a decoded heat-pump view, plus suspend reads (`airQuality`, `hvacSummary`, `temperatureHistory`, …) that pull from MCP or InfluxDB. It is deliberately read-only in v1 — there is no heat-pump write path, because the backend republishes the setpoint on its own cadence and register writes wear the pump's flash.

Repositories are singletons wired with **Koin**. `core/di/CoreModule.kt` binds each interface to its `Default…` implementation and constructs the transports, a shared `CoroutineScope(SupervisorJob() + Dispatchers.Default)`, and the `ConnectionManager`. The app side adds view-models and UI-scoped singletons in `composeApp/.../di/AppModule.kt` (with `ScreenModules.kt` and `InitKoin.kt`). The two speech engines per interface are registered under named qualifiers (`serverStt`/`platformStt`, `serverTts`/`platformTts`) so the UI can A/B-switch them — see [Cross-platform](cross-platform.md).

## Three UI surfaces

The same binary drives three surfaces, defined by `enum class Surface { Phone, Kid, Tablet }` in `composeApp/.../shell/AppState.kt`. Two are picked automatically from the window; one is an explicit mode.

- **Phone** — the default portrait layout with a bottom tab bar.
- **Tablet / kiosk** — a wide landscape dashboard with a left navigation rail (`TabletKotiDashboard` on the Koti tab), for an always-on shelf tablet.
- **Kid** — a deliberately reduced surface (greeting, the child's own light, shared rooms, a big voice button). A parent switches it on; it is persisted as `kidMode` in config and survives a restart.

**Auto-selection** lives in `shell/MarmorikatuApp.kt`. On every size change, if the surface is not `Kid`, it recomputes: a device whose short side is at least `TABLET_MIN_WIDTH_DP` (720) is a tablet, and any device turned landscape (`widthDp > heightDp`) also counts as wide — wide picks `Surface.Tablet`, otherwise `Surface.Phone`. (A phone showing a detail chart forces landscape only for the chart, so it is exempted from the landscape rule to avoid swapping the whole app to the kiosk.) `ShellViewModel.setSurface` also flips `ConnectionManager.keepAlive` on for the tablet surface, so the plugged-in kiosk keeps its connections live in the background.

**Kiosk density up-scaling** lives in `composeApp/.../App.kt`. Only the large-canvas kiosk is scaled; phones stay 1:1. When `rawW >= TABLET_MIN_DP` (720) or the window is landscape, the app computes `scale = (rawW / KIOSK_TARGET_DP).coerceIn(1f, 1.6f)` with `KIOSK_TARGET_DP = 880`, then provides a `LocalDensity` with `density * scale` and passes the *scaled-down* dp dimensions to `MarmorikatuApp`. The effect is a whole-UI zoom so the rail and every readout are legible from across a hallway.

## Optimistic light control

Light toggles are optimistic: the desired state shows immediately and is reverted if the retained MQTT state topic doesn't confirm it in time.

The bookkeeping is `repository/Reconciler.kt`, a small generic `Reconciler<K, V>` with a **20 s default deadline** (the PLC republishes state roughly every 13 s). `commandSent(key, desired)` records a pending optimistic value with an expiry; `observed(key, actual)` clears it once transport truth matches; `expireOverdue()` drops entries past their deadline and returns the keys that timed out. It is intentionally not thread-safe — `DefaultLightsRepository` confines all access under a `Mutex`, because commands arrive on the UI coroutine while confirmations arrive on the MQTT collector.

In `DefaultLightsRepository`:

- A background loop calls `expireOverdue()` every 5 s; timed-out keys are reverted to transport truth and emitted on `controlFailures`.
- Commands are **paced**, not fired in a burst: the PLC silently drops commands that arrive faster than its scan cycle, so single commands are queued on an unbounded `Channel` and a single consumer spaces them `COMMAND_SPACING_MS = 150` ms apart.
- Each single command's `deliver` uses the **fast path when MQTT is connected** (publish to the light-set topic at QoS 1); when MQTT is disconnected it **falls back to MCP** (`mcp.setLight`). A send that throws cancels the optimistic entry and reports a failure.
- **Batch** commands (`setAll`, `setFloor`) always go through MCP so the server can pace them per PLC cycle.
- Indices present in the raw light state but without a name are gaps in the PLC's control array; `publishLocked` never renders them, so a phantom output can't be toggled.

## Connection lifecycle

`core/lifecycle/ConnectionManager.kt` is the single orchestrator. It ties the MQTT client, the claude-bridge health poll, the announcements stream, and the lights-catalog refresh to app foreground/background transitions and to host-config changes.

- **Foreground/background.** `start()` collects `combine(appForeground.isForeground, keepAlive)` with `collectLatest`. When active it launches `mqttLoop()`, `healthLoop()`, and `catalogRefresh()` and calls `announcements.start()`. When it goes inactive it waits a short `GRACE_MS` (5 s) grace — so quick app switches don't churn connections — then cancels those jobs, stops announcements, and disconnects MQTT under `NonCancellable`. `keepAlive` (set for the tablet kiosk) keeps connections up even while backgrounded.
- **Host-config changes.** `mqttLoop()` wraps its retry loop in `configStore.config.collectLatest { … }`, so changing the broker host/port in settings tears the loop down and reconnects against the new host without an app restart.
- **Adaptive backoff.** Every retry loop shares `core/lifecycle/Reconnect.kt`'s `reconnectDelay(failures)`: quick retries at 1, 2, 4, 8, 16, 30 s for the first ~minute (a transient drop at home — a broker restart, a Wi-Fi handover, the VPN tunnel coming up), then relaxing to `SLOW_POLL` (5 min) once failure is clearly not transient (the phone is simply away from home). A connection that held at least `CONNECTED_HOLD` (45 s) proves reachability and resets the backoff to the snappy end; the bridge health loop polls every `HEALTHY_POLL` (30 s) while the house answers and relaxes to the same curve when it stops. The relaxed tail is a battery measure for an away, backgrounded device; the always-reachable kiosk never leaves the fast phase.

The boot splash in `App.kt` is driven by this same state — `ConnectionManager.mqttState` and `bridgeHealthy` — so a broken home network shows as broken (with a manual dismiss after a grace period) rather than a silent stall.

Background announcement delivery on Android (a foreground service) is covered in [Cross-platform](cross-platform.md).

## Security boundary

By design, none of the backend services authenticate: all traffic is plain HTTP and unencrypted MQTT on the home LAN, and a VPN is the only remote entry point onto that LAN. The app is deliberately unaware of whether it is home or away — the same endpoints work either way, and the LAN (with the VPN gateway) is the security boundary. This is an intentional choice for a private, VPN-gated home network, not an oversight; no credentials belong in the app or this repo.

## See also

- [Protocols](protocols.md) — the transport map (MQTT / MCP / claude-bridge / InfluxDB / HTTP) and payload details.
- [Alarms](alarms.md) — the heat-pump / ventilation / Ruuvi-sensor / sauna alert catalog and the attention-strip tiers.
- [Cross-platform](cross-platform.md) — expect/actual, the iOS framework, speech engines, and platform specifics.

# Communication protocols

How Marmorikatu Mobile talks to its self-hosted backend: one live MQTT feed
plus several request/response HTTP services, each hidden behind a repository so
the UI only ever sees `Flow`s and suspend functions. All transports live under
`core/src/commonMain/kotlin/fi/marmorikatu/core/transport/`.

> **Security context.** None of these services authenticate — the home LAN
> (reachable remotely only through a UniFi VPN) is the security boundary, so
> traffic is plain HTTP and unencrypted MQTT by design. See
> [Architecture](architecture.md) for why.

## Transport map

| Transport | Library | Used for |
|---|---|---|
| **MQTT** | MQTTastic (`org.meshtastic:mqtt-client`, TCP) | Live retained device state (instant snapshot on connect, ~13 s PLC republish), single-light commands (fast path), the ThermIQ heat-pump register dump, and the Ruuvi Gateway sensor feed. |
| **MCP** | MCP Kotlin SDK (streamable HTTP) | Light catalog + batched light commands, Harmony TV control, and reads for heat pump, room temperatures, sauna, electricity prices, air quality, energy, weather, news, calendar, and a daily report. |
| **claude-bridge** | Ktor client (SSE + NDJSON) | Assistant chat streaming, voice transcription and speech, and the announcements push feed with `Last-Event-ID` resume. |
| **InfluxDB** | Ktor client (Flux over HTTP) | Deep time-series history for the charts (the MCP data tool caps at 100 rows). |
| **Widget HTTP** | Ktor client | Nysse bus departures (the only directly published widget endpoint). |

The shared request/response client is built in
`core/.../transport/http/HttpClientFactory.kt` (OkHttp on Android, Darwin on
iOS; 5 s connect / 15 s request timeouts). Streaming endpoints override the
request timeout per call.

## MQTT

Source: `core/.../transport/mqtt/` (`MqttTopics.kt`, `PlcPayloads.kt`,
`RuuviPayloads.kt`, `MqttClient.kt`, `MqttasticClient.kt`).

`MqttasticClient` is the only file that imports the MQTTastic library; the rest
of the app sees the `MqttClient` interface (a `connectionState` `StateFlow` and
a `messages` `SharedFlow`). The library negotiates MQTT 5.0 with a 3.1.1
fallback over TCP, with a 30 s keep-alive and auto-reconnect. A **clean session**
is always used: retained topics replay the full house state immediately after
subscribe, so no local persistence is needed. Subscriptions are made at
QoS 0 (`AT_MOST_ONCE`); the default publish QoS is 1 (`AT_LEAST_ONCE`).

### Retained `marmorikatu/*` state topics

The WAGO PLC publisher owns the `marmorikatu/*` tree. All of these are
**retained**, so subscribing yields an instant full snapshot; the PLC then
republishes roughly every 13 s. The app subscribes to the set in
`MqttTopics.STATE_SUBSCRIPTIONS`:

| Topic | Payload shape (parser in `PlcPayloads`) |
|---|---|
| `marmorikatu/lights` | `{"1":false,"17":true,…}` → index → on |
| `marmorikatu/names/lights` | `{"1":"Kylpyhuone",…}`; blank names are PLC array gaps |
| `marmorikatu/outlets` | keyed by name, `{"ulkopistorasia":false}` |
| `marmorikatu/temperatures` | room °C, plus non-room extras (supply duct, cooling battery) |
| `marmorikatu/heating` | per-circuit heating demand percent |
| `marmorikatu/cooling` | `{"pumppu_jaahdytys":…,"jaahdytyspumppu":…}` booleans |
| `marmorikatu/ventilation` | HRV temps, RH, and alarm flags (keys matched case-insensitively) |
| `marmorikatu/status` | PLC publish/error/command counters, Modbus link |
| `marmorikatu/energy/heatpump`, `marmorikatu/energy/extra` | OR-WE-517 meter fields (`Total_Active_Power`, `Total_Active_Energy`, …) |

Every parser tolerates unknown keys and skips malformed entries rather than
throwing (payloads were captured verbatim from the live broker; see the
fixtures used by the shared tests). A room-name lookup (`Rooms`) reconciles the
MQTT keys with the legacy InfluxDB field names.

### Single-light command topic

One light is toggled by publishing to the path shape:

```
marmorikatu/light/<index>/set     payload "true" | "false"     QoS 1, retain=false
```

Built by `MqttTopics.lightSet(index)` / `lightSetPayload(on)` and published from
`LightsRepository`. This is the fast path; batch commands go through MCP so the
server can pace them per PLC cycle (see [Architecture](architecture.md) for the
optimistic/paced control model).

### ThermIQ heat-pump register dump

The heat pump is bridged separately from the PLC, under its own root (**not
retained**, so the first value can lag a publish cycle after connect):

- `ThermIQ/marmorikatu/data` — the Thermia register dump. Registers arrive as
  `dNN` (decimal index) or `rNN` (hex index) depending on the ThermIQ REGFMT
  setting; `PlcPayloads.parseThermiq` handles both, combines integer + 0.1 °C
  decimal registers for indoor/target, decodes the `d16` component bitfield and
  the `d19`/`d20` alarm bitfields, and filters the −40 "sensor not connected"
  sentinel.
- `ThermIQ/marmorikatu/read` — publishing an **empty** payload (QoS 0) asks the
  bridge to read all registers and republish `…/data` immediately. Used on
  pull-to-refresh from `ClimateRepository` so the heat-pump tiles don't wait for
  the next periodic publish.

### Ruuvi Gateway feed

`ruuvi/<gw_mac>/<tag_mac>` — one message per tag, **already decoded to JSON** by
the gateway (temperature, humidity, pressure, CO₂, PM2.5, VOC, NOx, voltage,
rssi, per-measurement `ts`), so `RuuviPayloads.parse` reads fields directly with
no hex parsing. The subscription uses a `#` wildcard, which also carries
non-Ruuvi BLE devices (they lack an `id` and are discarded); tags not in the
name map are ignored. Overall volume is only ~5–10 msg/s, within the client's
message buffer.

Alarm decoding for ventilation and the heat pump lives in [Alarms](alarms.md).

## MCP

Source: `core/.../transport/mcp/` (`McpConnection.kt`, `McpApi.kt`).

A single lazily-established MCP session over **streamable HTTP** to the building-
automation server (`StreamableHttpClientTransport` on the shared Ktor client).
`McpConnection` and `McpApi` are the only files allowed to import the MCP SDK,
to contain v0.x churn.

**JSON-result convention.** `callToolJson` calls a tool and returns its **first
text content parsed as JSON**. Non-JSON text is wrapped as a JSON string
primitive so prose tools still work. A result with `isError == true`, text
starting with `"Error"`, or a JSON object carrying an `error` key becomes an
`McpToolFailure`. Each call has a 15 s timeout; a transport failure is retried
**once**, but only for idempotent tools — an absolute `set_light {light, on}`
survives a replay, a relative `harmony_send_command` (e.g. `VolumeUp`) does not,
so it is marked non-idempotent. Latency of the last round-trip is exposed on
`McpState.Connected`.

Tool categories (`DefaultMcpApi`):

- **Reads** (JSON or prose): `get_thermia_status`, `get_room_temperatures`,
  `get_sauna_status`, `get_electricity_prices`, `get_air_quality`,
  `get_energy_consumption`, `get_weather_forecast`, `get_news_headlines`,
  `get_calendar_events`, `get_daily_report`.
- **Light control**: `list_lights`, `set_light`, `set_all_lights`,
  `set_lights_by_floor` (batches paced server-side per PLC cycle).
- **TV control (Harmony Hub)**: `harmony_list_activities`,
  `harmony_current_activity`, `harmony_start_activity`, `harmony_send_command`,
  `harmony_power_off`. The Harmony tools answer in **prose** ("Harmony
  activities:\n1. Watch TV"), not JSON, so the wrappers parse the numbered list.

## claude-bridge

Source: `core/.../transport/bridge/BridgeApi.kt`, with the stream readers in
`core/.../transport/http/SseReader.kt` and `NdjsonReader.kt`.

The bridge handles conversation, voice I/O, and the announcements push channel.

### Announcements stream (SSE)

`GET /announcements/stream` is a long-lived SSE feed (request timeout
effectively infinite; 60 s socket timeout). On (re)connect the caller passes the
last seen id as a **`Last-Event-ID`** header for gapless resume. The stream
interleaves **keepalive/heartbeat** frames (empty or `{}`) with real
announcements about every 20 s — three missed keepalives (60 s) count as a dead
connection. `GET /announcements/history?limit=N` backfills recent events.

### Chat / voice event stream

`POST /chat/stream` streams a conversation turn as **SSE over POST**. Because
Ktor's SSE plugin is GET-shaped, `readSseEvents` is a hand-rolled SSE parser
over the raw response channel. Events are typed (`ChatEvent`):

- `Done` (terminal — final response text + tool calls),
- `Audio` (base64 WAV + optional text), `Text`, `ToolUse`, `Screenshot`.

A single malformed event is skipped rather than tearing down the stream.

Voice endpoints:

- `POST /transcribe` — multipart audio upload; returns the Finnish transcript
  (`{"text": …}`).
- `POST /tts` — server-side Piper TTS; one WAV clip **per sentence**, streamed
  back as **NDJSON** (one JSON object per line, `readNdjson`), each with a
  base64 `audio` field.
- `GET /cached/<kind>` — pre-rendered speech clips.
- `GET /health` — status + tool count.

See [Cross-platform](cross-platform.md) for how native vs. server voice I/O is
selected per device.

## InfluxDB

Source: `core/.../transport/influx/FluxClient.kt`.

A minimal InfluxDB 2.x query client — the only source of history deep enough for
the charts, because the MCP data tool caps results at 100 rows. Queries are
**Flux** posted over HTTP to `/api/v2/query` with `Accept: application/csv` and a
`application/vnd.flux` body. Responses are InfluxDB's **annotated CSV** (`#`-prefixed annotation rows, then a
header row, then data), parsed column-by-column (`parseAnnotatedCsv`) because
the columns shift between queries.

Query helpers include downsampled `history(measurement, fields, range, every,
tag…)` with `aggregateWindow(mean)`, `latest(…)` (last value per field, optional
tag filter), `latestString(…)` for non-numeric fields, and a derived
heat-recovery efficiency series computed in Flux exactly as the Grafana
dashboard does. An optional tag filter disambiguates series that several sensors
share (e.g. a specific Ruuvi `sensor_name`). Any failure returns null/empty so
callers fall back gracefully.

## Widget HTTP

Source: `core/.../transport/widgets/BusApi.kt`.

`BusApi.departures()` fetches Nysse bus departures from `GET /api/departures` as
JSON (`BusDepartures`). This is the **only** widget with a directly published
host port. The weather, news, and calendar widgets are container-internal and
are reached through **MCP** tools (`get_weather_forecast`, `get_news_headlines`,
`get_calendar_events`) rather than a dedicated HTTP endpoint.

## See also

- [Architecture](architecture.md) — module layout, repositories, the
  optimistic/paced light-control model, and the connection lifecycle.
- [Alarms](alarms.md) — ventilation and heat-pump alarm bitfield decoding.
- [Cross-platform](cross-platform.md) — platform actuals (HTTP engine, audio,
  voice) behind the `expect`/`actual` transports.

# Alarms & alerts

Every alert Marmorikatu Mobile can raise, where it comes from, and the exact
threshold that trips it. All alerts surface on the Koti dashboard's **attention
strip** (`MkAttentionStrip`); each is an `AttentionItem` with one of three
tiers — `alarm`, `warn`, `info`. See [Architecture](architecture.md) for the
transport map and [Protocols](protocols.md) for the payload shapes.

## Attention strip model

The strip is built by `buildAttention` in
`composeApp/src/commonMain/kotlin/fi/marmorikatu/app/screens/KotiViewModel.kt`
and rendered by `MkAttentionStrip`
(`composeApp/.../components/FeedbackCamera.kt`). It is "quiet until it matters":
with no items it collapses to a single calm "Kaikki kunnossa" row; otherwise it
lists the abnormal conditions, most severe first.

`AttentionItem(status, icon, text, value)` carries the tier in `status`. The
strip sorts by rank `alarm` → `0`, `warn` → `1`, `info` → `2`, so alarms always
lead and calendar reminders sink to the bottom.

| Tier | Meaning | Sort |
|---|---|---|
| `alarm` | A real fault or safety condition needing attention now. | first |
| `warn` | Something off-nominal — a warmed fridge, a sensor fault, a filter due. | middle |
| `info` | Non-fault context: today's / tomorrow's family calendar events. | last |

**Info-tier calendar reminders.** `calendarReminders` turns today's
still-upcoming and tomorrow's family-calendar events into `info` rows (e.g.
"Kesäjuhlat" → "tänään 20:00"). Only those two imminent days feed the strip,
today's already-passed events are dropped, and the list is capped at 3 so a busy
day can't flood it.

## Heat-pump (ThermIQ) faults

The heat pump publishes a Modbus register dump on its own MQTT topic. The alarm
bitfields **d19** (pressure / flow / brine / motor) and **d20** (sensor faults)
are decoded in `core/.../transport/mqtt/PlcPayloads.kt` into the
`HeatPumpAlarm` enum
(`core/src/commonMain/kotlin/fi/marmorikatu/core/model/AirQuality.kt`). Each
enum value carries its `d<reg>:<bit>` reference, shown verbatim as the alert's
value. Faults are only evaluated while the register feed is live
(`heatPump.available`); a feed older than 30 min falls back to "Ei tietoa"
rather than replaying a stale fault.

| Code | Enum | Plain meaning | Tier |
|---|---|---|---|
| `d19:0` | `HighPressure` | High-pressure switch tripped (refrigerant high-side). | alarm |
| `d19:1` | `LowPressure` | Low-pressure switch tripped (refrigerant low-side). | alarm |
| `d19:2` | `MotorBreaker` | Compressor motor protector / breaker tripped. | alarm |
| `d19:3` | `LowBrineFlow` | Ground-loop brine flow too low. | alarm |
| `d19:4` | `LowBrineTemp` | Incoming brine temperature too low. | alarm |
| `d20:0` | `OutdoorSensor` | Outdoor temperature sensor fault. | warn |
| `d20:1` | `SupplySensor` | Supply-line (meno) sensor fault. | warn |
| `d20:2` | `ReturnSensor` | Return-line (paluu) sensor fault. | warn |
| `d20:3` | `HotWaterSensor` | Hot-water tank sensor fault. | warn |
| `d20:4` | `IndoorSensor` | Indoor temperature sensor fault. | warn |
| `d20:5` | `PhaseOrder` | Incorrect mains phase order / phase fault. | alarm |
| `d20:6` | `Overheating` | Compressor / system overheating. | alarm |

Tiering follows `hpAlarmItem`: the five d19 faults plus `PhaseOrder` and
`Overheating` are `alarm`; the five sensor faults are `warn`.

### Aux (resistance) heater running

`HeatPumpStatus.auxHeaterActive` is decoded from register **d13** (bit 0 = 3 kW
element, bit 1 = 6 kW element). When it is set and the feed is live, the strip
adds a `warn` row "Maalämpö · lisävastus käytössä" — the pump alone can't keep
up and it's burning expensive resistance power. The current COP is shown
alongside when known.

## Ventilation (MVHR)

The ventilation unit publishes CamelCase alarm flags on its MQTT topic, decoded
in `PlcPayloads.kt` into the `VentAlarm` enum
(`core/src/commonMain/kotlin/fi/marmorikatu/core/model/Climate.kt`). Every
active alarm becomes a strip row via `ventAlarmItem`. Nine are boolean flags;
`TempSensor` fires on a non-zero numeric fault code.

| Enum | Source flag | Meaning | Tier |
|---|---|---|---|
| `FreezingDanger` | `AlarmFreezingDanger` | Heater-coil / heat-exchanger freeze risk. | alarm |
| `AfterheaterOverheat` | `AlarmOverheatAfter` | After-heater over-temperature. | alarm |
| `SupplyFanFailure` | `AlarmFanFailureSA` | Supply-air fan failure. | alarm |
| `ExhaustFanFailure` | `AlarmFanFailureEA` | Exhaust-air fan failure. | alarm |
| `FilterGuard` | `AlarmFilterGuard` | Filter needs changing. | warn |
| `LowEfficiency` | `AlarmEfficiency` | Heat-recovery efficiency low. | warn |
| `TempDeviation` | `AlarmTempDeviation` | Air-temperature deviation from target. | warn |
| `IrSensor` | `AlarmIRSensor` | IR sensor fault. | warn |
| `TempSensor` | `alarmtempsensor` (non-zero) | Temperature-sensor fault code. | warn |
| `ServiceReminder` | `AlarmServiceReminder` | Scheduled-service reminder. | warn |

**`FreezingDanger` is an always-shown safety alert.** It is the coil-freeze
condition and is also mirrored onto `HvacSummary.freezingDanger` /
`Ventilation.freezingDanger` so downstream views can flag it independently of
the strip.

## Ruuvi sensor alerts

Built by `ruuviAlerts` in `KotiViewModel.kt` from the live Ruuvi tag map. All
value alerts are **freshness-guarded**: the local `fresh(r)` helper requires a
reading with `tsEpoch > 0` that is no older than `RUUVI_OFFLINE_SECONDS`
(30 min). A stale tag never emits a warm/CO₂/battery alarm — it gets the
"anturi ei vastaa" offline row instead, so the strip can't show a contradictory
value from a dead feed.

| Alert | Condition | Constant | Tier |
|---|---|---|---|
| Freezer warm | freezer temp `>` −15 °C | `FREEZER_WARM_C = -15.0` | alarm |
| Fridge warm | fridge temp `>` 8 °C | `FRIDGE_WARM_C = 8.0` | warn |
| CO₂ high | air-quality tag CO₂ `>` 1200 ppm | `CO2_HIGH_PPM = 1200` | warn |
| PM2.5 high | air-quality tag PM2.5 `>` 25 µg/m³ | `PM25_HIGH = 25.0` | warn |
| Low battery | temperature-compensated voltage (see below) | — | warn |
| Sensor offline | no reading for `>` 30 min | `RUUVI_OFFLINE_SECONDS = 30 min` | warn |

**Temperature-compensated low battery.** A CR2477 coin cell's voltage sags in
the cold, so a healthy battery in the freezer or outdoors reads well below the
room-temperature line and a flat threshold would false-alarm. `batteryLow`
picks the threshold from the tag's own temperature (following Ruuvi's
cold-adjusted guidance):

| Tag temperature | Low-battery threshold |
|---|---|
| `< −20 °C` | 2.0 V |
| `< 0 °C` | 2.3 V |
| `< 20 °C` | 2.4 V |
| otherwise | 2.5 V |

Only the single weakest still-reporting tag under its threshold is surfaced, so
one flat cell doesn't spawn a row per sensor.

**Tile staleness.** Independently of the strip, a live-sensor KPI tile whose
reading is older than `STALE_DIM_SECONDS` (6 min) dims as stale; the
snapshot-timed tiles carry a just-now fetch stamp and never trip this.

## Sauna

Two independent conditions decide the sauna state, combined in `refresh` /
`buildAttention` (`KotiViewModel.kt`) over the sauna's Ruuvi temperature history
(`saunaHeatState` in
`core/src/commonMain/kotlin/fi/marmorikatu/core/repository/ClimateRepository.kt`).

**"Sauna on"** = the backend reports it climbing (`is_heating`) **OR** the
temperature trend shows it *holding* hot at setpoint — a plateau a single
reading can't distinguish from a cool-down. The history read is only paid for
when the sauna might be on (climbing, already latched, or the last reading is at
least `SAUNA_MAYBE_HOT_C = 45 °C`), so a cold sauna doesn't query every cycle.

- `saunaHeatingOnsetIso` pins the **ignition time**: the earliest sample in the
  contiguous run that stands `SAUNA_ONSET_DELTA_C = 3 °C` above the window's idle
  baseline. A within-session door-opening dip stays above the threshold, so the
  run isn't split. The ignition is latched once, then elapsed time counts
  forward.
- `saunaHolding` reads the recent trend to tell "holding" from "cooling": below
  `SAUNA_HOT_FLOOR_C = 50 °C` it is off outright; above it, the sauna counts as
  cooling only when the latest reading has fallen `SAUNA_FALL_MARGIN_C = 3 °C`
  below the recent-window peak **and** is still lower than ~15 min earlier.
  A löyly dip that recovers and normal setpoint jitter both stay "holding".

**Escalation.** While on, the strip shows a `warn` row "Sauna on päällä" (value:
elapsed on-time, or the current temperature until ignition is pinned). Once it
has run `SAUNA_LONG_SECONDS` (2 h) it escalates to an `alarm` row "Sauna on
ollut päällä pitkään" with the elapsed duration.

## Summary

| Alert | Source | Threshold | Tier |
|---|---|---|---|
| Heat-pump high pressure | ThermIQ `d19:0` | bit set | alarm |
| Heat-pump low pressure | ThermIQ `d19:1` | bit set | alarm |
| Heat-pump motor breaker | ThermIQ `d19:2` | bit set | alarm |
| Heat-pump low brine flow | ThermIQ `d19:3` | bit set | alarm |
| Heat-pump low brine temp | ThermIQ `d19:4` | bit set | alarm |
| Heat-pump phase order | ThermIQ `d20:5` | bit set | alarm |
| Heat-pump overheating | ThermIQ `d20:6` | bit set | alarm |
| Heat-pump sensor faults ×5 | ThermIQ `d20:0..4` | bit set | warn |
| Aux resistance heater on | ThermIQ `d13` bit 0/1 | 3 or 6 kW element on | warn |
| MVHR freezing danger | ventilation `AlarmFreezingDanger` | flag set | alarm |
| MVHR after-heater overheat | ventilation `AlarmOverheatAfter` | flag set | alarm |
| MVHR supply-fan failure | ventilation `AlarmFanFailureSA` | flag set | alarm |
| MVHR exhaust-fan failure | ventilation `AlarmFanFailureEA` | flag set | alarm |
| MVHR filter / efficiency / deviation / IR / temp-sensor / service | ventilation flags | flag set (temp-sensor: non-zero code) | warn |
| Freezer warm | Ruuvi freezer tag | `>` −15 °C, reading fresh | alarm |
| Fridge warm | Ruuvi fridge tag | `>` 8 °C, reading fresh | warn |
| CO₂ high | Ruuvi air-quality tag | `>` 1200 ppm, reading fresh | warn |
| PM2.5 high | Ruuvi air-quality tag | `>` 25 µg/m³, reading fresh | warn |
| Low battery | any Ruuvi tag | temp-compensated 2.0–2.5 V | warn |
| Sensor offline | any Ruuvi tag | no reading `>` 30 min | warn |
| Sauna on | sauna Ruuvi history + backend | climbing OR holding ≥ 50 °C | warn |
| Sauna on too long | sauna on-time | `≥` 2 h | alarm |
| Calendar reminder | family calendar | today (upcoming) + tomorrow, ≤ 3 | info |

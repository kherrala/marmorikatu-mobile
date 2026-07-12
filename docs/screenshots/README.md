# Reference screenshots

Captures of each view (light theme), taken with
[`scripts/capture-demo.sh`](../../scripts/capture-demo.sh) against the live
house. The family-calendar entries in `13-kalenteri.png` are blurred; everything
else is real house data.

| File | View |
|---|---|
| `01-koti-home.png` | Koti — weather, attention strip, door camera |
| `02-koti-presets-temps.png` | Koti — light presets + room temperatures |
| `03-valot-lights.png` | Valot — scene presets, floor tabs, area cards |
| `04-valot-expanded.png` | Valot — an area card expanded to per-fixture toggles |
| `05-ilmasto-temperatures.png` | Ilmasto — temperature history + rooms |
| `06-ilmasto-air-quality.png` | Ilmasto — air quality |
| `07-ilmasto-ruuvi-sensors.png` | Ilmasto — Ruuvi sensors (Anturit) |
| `08-ilmasto-heatpump.png` | Ilmasto — heat pump (Maalämpö) |
| `09-ilmasto-ventilation-cooling.png` | Ilmasto — ventilation (LTO) + cooling |
| `10-energia-price.png` | Energia — spot electricity price |
| `11-energia-consumption.png` | Energia — consumption by appliance |
| `12-bussit.png` | Bussit — Nysse departures |
| `13-kalenteri.png` | Kalenteri — waste schedule + (blurred) family calendar |
| `14-tapahtumat-camera.png` | Tapahtumat — announcement feed + live camera |
| `15-voice-assistant.png` | Voice assistant — listening + quick commands |
| `voice-assistant.gif` | Voice assistant — short demo clip |

Regenerate them all with:

```bash
scripts/capture-demo.sh --gif        # auto-picks a device; --gif records the voice clip
```

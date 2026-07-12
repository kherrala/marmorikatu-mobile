package fi.marmorikatu.app.screens

import androidx.compose.ui.graphics.vector.ImageVector
import fi.marmorikatu.app.format.Fmt
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.core.model.ElectricityPrices
import fi.marmorikatu.core.model.Light
import fi.marmorikatu.core.model.SpotPrice
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// ── Heating optimization ("Lämmityksen optimointi") ──────────────────────────

/** A price band in the heating-optimization forecast; PreHeat is the design's 4th tier. */
enum class HeatTier { Cheap, Normal, Expensive, PreHeat }

/** A tone role for an optimizer row / tag; mapped to a theme colour in the UI. */
enum class OptiTone { Warn, Accent, Ok, Ink }

/** One hour of the forecast bar strip. */
data class TierBar(val label: String, val tier: HeatTier, val current: Boolean)

/** One key/value row under the forecast. */
data class OptiRow(val key: String, val value: String, val tone: OptiTone)

data class HeatingOpti(
    val tierBars: List<TierBar>,
    val nowTierLabel: String,
    val nowTone: OptiTone,
    /** The heat pump's applied indoor-setpoint correction (°C), or null if unknown. */
    val biasC: Double?,
    val rows: List<OptiRow>,
)

// ── Light usage ("Valojen käyttö") ───────────────────────────────────────────

data class AreaUse(val name: String, val icon: ImageVector, val hoursLabel: String, val pct: Int)
data class AutoRule(val label: String, val icon: ImageVector, val count: Int)

data class LightUsage(
    val onNow: Int,
    val total: Int,
    val kwhToday: String?,
    val autoOffToday: Int,
    /** Total fixture on-time today across all floors (e.g. "12 t 40 min"), or null. */
    val totalUseLabel: String?,
    val areaRows: List<AreaUse>,
    val autoRules: List<AutoRule>,
)

// ── Cost tracking ("Kulutus" range selector + "Kustannustrendi") ──────────────

/** A selectable window for the cost/consumption section; maps to a Flux range. */
enum class EnergyRange(val tab: String, val window: String, val flux: String, val every: String) {
    H24("24 h", "tänään", "-24h", "2h"),
    D7("7 pv", "7 pv", "-7d", "1d"),
    D30("30 pv", "30 pv", "-30d", "3d"),
    Y1("12 kk", "12 kk", "-365d", "1mo"),
}

/**
 * The Kulutus + Kustannustrendi view for one [EnergyRange]. [cost]/[avg] come
 * from the backend `get_energy_cost`; [peak]/[cheap] are today's spot extremes
 * (only known for the 24 h window, else null); [trend] is the per-bucket metered
 * kWh sparkline.
 */
data class CostView(
    val window: String,
    val kwh: String?,
    val cost: String?,
    val avg: String?,
    val peak: String?,
    val cheap: String?,
    val consumers: List<EnergyComponent>,
    val trend: List<Float>,
)

/**
 * Today's cheapest and most-expensive spot hours as display labels
 * (e.g. "11,2 c · klo 18" / "1,8 c · klo 03"), from the day's own curve.
 * Both null when the curve is empty. Only meaningful for the 24 h window —
 * the longer ranges have no per-hour price series to scan.
 */
fun priceExtremes(today: List<SpotPrice>): Pair<String?, String?> {
    if (today.isEmpty()) return null to null
    fun label(sp: SpotPrice?): String? =
        sp?.let { "${Fmt.comma(it.centsPerKwh, 1)} c · klo ${pad2(localHour(it.time) ?: 0)}" }
    return label(today.maxByOrNull { it.centsPerKwh }) to label(today.minByOrNull { it.centsPerKwh })
}

private val HELSINKI = TimeZone.of("Europe/Helsinki")

/** Hours before an expensive block that become "pre-heat", matching the backend default. */
private const val PRE_HEAT_HOURS = 2

@OptIn(ExperimentalTime::class)
private fun localHour(iso: String): Int? =
    runCatching { Instant.parse(iso).toLocalDateTime(HELSINKI).hour }.getOrNull()

private fun pad2(v: Int): String = v.toString().padStart(2, '0')

private fun tierLabel(t: HeatTier): String = when (t) {
    HeatTier.Cheap -> "Halpa"
    HeatTier.Normal -> "Normaali"
    HeatTier.Expensive -> "Kallis"
    HeatTier.PreHeat -> "Esilämmitys"
}

fun tierTone(t: HeatTier): OptiTone = when (t) {
    HeatTier.Cheap -> OptiTone.Ok
    HeatTier.Normal -> OptiTone.Ink
    HeatTier.Expensive -> OptiTone.Warn
    HeatTier.PreHeat -> OptiTone.Accent
}

/**
 * Build the heating-optimization view from today's spot prices and the
 * optimizer's live telemetry. The per-hour tier forecast mirrors the backend
 * `heating_optimizer`: cheap ≤ its cheap threshold, expensive ≥ its expensive
 * threshold, else normal; then the [PRE_HEAT_HOURS] hours immediately before
 * each expensive block are promoted to "pre-heat" (charge the thermal mass while
 * power is still cheap). The forecast covers the remaining hours of today (up to
 * 12) — tomorrow's curve isn't fetched, so late evening shows a shorter strip.
 *
 * @param optimizer the latest `indoor_publisher` readings: `total_bias`,
 *        `cheap_threshold`, `expensive_threshold`.
 */
@OptIn(ExperimentalTime::class)
fun buildHeatingOpti(prices: ElectricityPrices, optimizer: Map<String, Double>): HeatingOpti? {
    if (prices.today.isEmpty()) return null
    val cheap = optimizer["cheap_threshold"] ?: prices.cheapThreshold ?: return null
    val exp = (optimizer["expensive_threshold"] ?: prices.expensiveThreshold ?: return null)
        .let { if (it <= cheap) cheap + 1.0 else it }

    // Mean price per local hour of today.
    val byHour = prices.today
        .mapNotNull { sp -> localHour(sp.time)?.let { it to sp.centsPerKwh } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, v) -> v.average() }

    val nowHour = Clock.System.now().toLocalDateTime(HELSINKI).hour
    val hours = (nowHour..23).filter { byHour.containsKey(it) }.take(12)
    if (hours.isEmpty()) return null

    fun classify(cents: Double): HeatTier = when {
        cents <= cheap -> HeatTier.Cheap
        cents >= exp -> HeatTier.Expensive
        else -> HeatTier.Normal
    }
    val tiers = hours.map { classify(byHour.getValue(it)) }.toMutableList()
    for (i in tiers.indices) {
        if (tiers[i] == HeatTier.Expensive) {
            for (j in (i - PRE_HEAT_HOURS until i)) {
                if (j >= 0 && (tiers[j] == HeatTier.Cheap || tiers[j] == HeatTier.Normal)) {
                    tiers[j] = HeatTier.PreHeat
                }
            }
        }
    }
    val bars = hours.mapIndexed { i, h -> TierBar(pad2(h), tiers[i], current = i == 0) }
    val nowTier = tiers.first()

    val rows = buildList {
        prices.currentCentsPerKwh?.let {
            add(OptiRow("Nykyinen jakso", "${tierLabel(nowTier)} · ${Fmt.comma(it, 1)} c/kWh", tierTone(nowTier)))
        }
        // Pre-heat window ahead, if any (first contiguous run of PreHeat hours).
        val firstPre = tiers.indexOfFirst { it == HeatTier.PreHeat }
        if (firstPre >= 0) {
            var last = firstPre
            while (last + 1 < tiers.size && tiers[last + 1] == HeatTier.PreHeat) last++
            add(OptiRow("Esilämmitys", "${pad2(hours[firstPre])}–${pad2((hours[last] + 1) % 24)} · lataa massaa", OptiTone.Accent))
        }
        // Next cheap hour ahead.
        val nextCheapIdx = tiers.indexOfFirst { it == HeatTier.Cheap }
        if (nextCheapIdx >= 0) {
            val h = hours[nextCheapIdx]
            val price = byHour.getValue(h)
            val whenLabel = if (nextCheapIdx == 0) "nyt" else "${pad2(h)}:00"
            add(OptiRow("Seuraava halpa", "$whenLabel · ${Fmt.comma(price, 1)} c/kWh", OptiTone.Ok))
        }
        add(OptiRow("Hintakynnykset", "halpa ≤ ${Fmt.comma(cheap, 1)} · kallis ≥ ${Fmt.comma(exp, 1)} c", OptiTone.Ink))
    }

    return HeatingOpti(
        tierBars = bars,
        nowTierLabel = "${tierLabel(nowTier)} jakso",
        nowTone = tierTone(nowTier),
        biasC = optimizer["total_bias"],
        rows = rows,
    )
}

private fun floorIcon(floor: String): ImageVector = when (floor.lowercase()) {
    "alakerta" -> MkIcons.HouseLine
    "yläkerta" -> MkIcons.Stack
    "kellari" -> MkIcons.Stairs
    "ulko" -> MkIcons.TreeEvergreen
    else -> MkIcons.Lightbulb
}

/** The optimizer's `category` tag → a friendly Finnish rule label + icon. */
private fun ruleFor(category: String): Pair<String, ImageVector> = when (category.lowercase()) {
    "co2_auto" -> "CO₂-auto" to MkIcons.Wind
    "sauna_laude" -> "Sauna-LED" to MkIcons.Flame
    "porch_schedule" -> "Ulkovalo · hämärä" to MkIcons.SunHorizon
    "toilet" -> "WC-ajastin" to MkIcons.Toilet
    "general" -> "Tyhjä talo" to MkIcons.Moon
    else -> category.replaceFirstChar { it.uppercase() } to MkIcons.Lightbulb
}

private fun hoursLabel(hours: Double): String {
    val total = (hours * 60).roundToInt()
    val h = total / 60
    val m = total % 60
    return if (h > 0) "$h t ${pad2(m)} min" else "$m min"
}

/**
 * Build the light-usage view: on/total from the live catalog, today's lighting
 * kWh from the consumption estimate, per-floor on-time and the automatic-off
 * counts from InfluxDB.
 */
fun buildLightUsage(
    lights: List<Light>,
    lightingKwh: Double?,
    onTimeByFloor: Map<String, Double>,
    autoOffCounts: Map<String, Double>,
): LightUsage {
    val maxHours = onTimeByFloor.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val areaRows = onTimeByFloor.entries
        .filter { it.value >= 0.05 }
        .sortedByDescending { it.value }
        .map { (floor, h) -> AreaUse(floor, floorIcon(floor), hoursLabel(h), (h / maxHours * 100).roundToInt().coerceIn(0, 100)) }
    val autoRules = autoOffCounts.entries
        .filter { it.value >= 1 }
        .sortedByDescending { it.value }
        .map { (cat, n) -> val (label, icon) = ruleFor(cat); AutoRule(label, icon, n.roundToInt()) }
    val totalHours = onTimeByFloor.values.sum()
    return LightUsage(
        onNow = lights.count { it.displayedOn },
        total = lights.size,
        kwhToday = lightingKwh?.let { Fmt.oneDecimal(it) },
        autoOffToday = autoOffCounts.values.sum().roundToInt(),
        // Compact form ("12,7 t") to fit the narrow stat cell, unlike the per-row labels.
        totalUseLabel = totalHours.takeIf { it >= 0.05 }?.let { "${Fmt.comma(it, 1)} t" },
        areaRows = areaRows,
        autoRules = autoRules,
    )
}

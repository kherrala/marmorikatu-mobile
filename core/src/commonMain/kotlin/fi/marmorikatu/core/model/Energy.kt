package fi.marmorikatu.core.model

import kotlinx.serialization.Serializable

/**
 * Live meter reading from `marmorikatu/energy/heatpump` or `energy/extra`
 * (OR-WE-517 meters behind the PLC).
 */
@Serializable
data class EnergyReading(
    /** Which meter: `heatpump` or `extra`. */
    val meter: String,
    val powerKw: Double? = null,
    val energyKwh: Double? = null,
    val raw: Map<String, Double> = emptyMap(),
)

/** One quarter-hour's spot electricity price (from MCP `get_electricity_prices`). */
@Serializable
data class SpotPrice(
    val time: String,
    val centsPerKwh: Double,
)

/**
 * How the whole stack classifies a spot price. Mirrors the backend
 * `heating_optimizer` tiers (CHEAP / NORMAL / EXPENSIVE) that drive heating
 * and the spoken announcements; PRE_HEAT is a heating hint, not a price band,
 * so it maps to [Normal] here.
 */
enum class PriceTier { Cheap, Normal, Expensive }

/** Today's spot prices plus the current hour, as the MCP tool reports them. */
@Serializable
data class ElectricityPrices(
    val currentCentsPerKwh: Double? = null,
    val currentHour: String? = null,
    val minCentsPerKwh: Double? = null,
    val maxCentsPerKwh: Double? = null,
    val avgCentsPerKwh: Double? = null,
    /** 15-minute resolution across the day. */
    val today: List<SpotPrice> = emptyList(),
    /** Tomorrow's prices once published (~14:00 the day before); empty until then. */
    val tomorrow: List<SpotPrice> = emptyList(),
) {
    /**
     * Cheap/expensive thresholds derived the same way as the backend
     * `heating_optimizer`: the day's 25th/75th percentiles, but **clamped to
     * absolute cents** so a flat cheap day still reads as cheap. Without the
     * clamp a purely relative rule tags the day's priciest-but-still-cheap hour
     * (e.g. 0.9 c/kWh) as "expensive", which is what this fixes.
     */
    val cheapThreshold: Double?
        get() = percentile(0.25)?.coerceIn(CHEAP_MIN, CHEAP_MAX)

    val expensiveThreshold: Double?
        get() {
            val exp = percentile(0.75)?.coerceIn(EXPENSIVE_MIN, EXPENSIVE_MAX) ?: return null
            // Guard against the clamps overlapping on unusual days.
            val cheap = cheapThreshold
            return if (cheap != null && exp <= cheap) cheap + 1.0 else exp
        }

    /** Classify one price into its band using the clamped thresholds. */
    fun tierOf(cents: Double): PriceTier {
        val cheap = cheapThreshold
        val exp = expensiveThreshold
        return when {
            cheap != null && cents <= cheap -> PriceTier.Cheap
            exp != null && cents >= exp -> PriceTier.Expensive
            else -> PriceTier.Normal
        }
    }

    /**
     * The current price's band computed locally — the fallback used when the
     * authoritative `heating_optimizer.tier` from InfluxDB is unavailable.
     */
    val currentTierFallback: PriceTier?
        get() = currentCentsPerKwh?.let { tierOf(it) }

    val isExpensiveNow: Boolean
        get() = currentTierFallback == PriceTier.Expensive

    /** Linear-interpolated percentile of today's prices, or null if empty. */
    private fun percentile(p: Double): Double? {
        val sorted = today.map { it.centsPerKwh }.sorted()
        if (sorted.isEmpty()) return null
        if (sorted.size == 1) return sorted[0]
        val pos = p * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = (lo + 1).coerceAtMost(sorted.size - 1)
        val frac = pos - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }

    companion object {
        // Absolute clamps matching the backend heating_optimizer env defaults.
        const val CHEAP_MIN = 3.0
        const val CHEAP_MAX = 6.0
        const val EXPENSIVE_MIN = 5.0
        const val EXPENSIVE_MAX = 15.0
    }
}

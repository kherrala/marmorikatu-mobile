package fi.marmorikatu.core.model

import kotlinx.serialization.Serializable

/**
 * Event from the announcer service via claude-bridge
 * `GET /announcements/stream` (SSE) or `/announcements/history`.
 */
@Serializable
data class Announcement(
    val id: Long,
    val text: String,
    /** e.g. `light_on`, `sauna_on`, `hvac_alarm`, `price_tier`. */
    val kind: String,
    /** 0 = critical (bypasses quiet hours) … 3 = verbose. */
    val priority: Int,
    /** Dedup key, e.g. `light_on:44`. */
    val key: String,
    /** Unix seconds. */
    val ts: Double,
    /** Optional base64 image (e.g. door camera snapshot). */
    val image: String? = null,
)

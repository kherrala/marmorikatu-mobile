package fi.marmorikatu.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Semantic colour tokens from the design system's `tokens/colors.css`.
 * Dark is the primary theme (kiosk heritage: near-black, calm, teal
 * signature glow); light is the daylight variant for phones outdoors.
 */
@Immutable
data class MkColors(
    // Neutrals
    val bg0: Color,
    val appBg: Color,
    val surfaceInset: Color,
    val surfaceCard: Color,
    val surfaceRaised: Color,
    val track: Color,
    val hairline: Color,
    val borderSubtle: Color,
    val borderStrong: Color,
    // Text
    val inkHi: Color,
    val inkMid: Color,
    val inkLo: Color,
    val inkOnAccent: Color,
    val inkOnWarm: Color,
    // Brand
    val accent: Color,
    val accentStrong: Color,
    val accentDim: Color,
    val accentBorder: Color,
    val accentGlow: Color,
    val warm: Color,
    val warmDim: Color,
    val warmBorder: Color,
    // Status (priority-aligned with the announcer)
    val statusOk: Color,
    val statusOkDim: Color,
    val statusWarn: Color,
    val statusWarnInk: Color,
    val statusWarnDim: Color,
    val statusAlarm: Color,
    val statusAlarmInk: Color,
    val statusAlarmDim: Color,
    val statusInfo: Color,
    val statusInfoDim: Color,
    val statusIdle: Color,
    // Data-viz
    val vizOutdoor: Color,
    val vizRoom: Color,
    val vizSecondary: Color,
    val vizTertiary: Color,
    val vizAccent: Color,
    val vizAlarm: Color,
    val vizGrid: Color,
    val vizAxis: Color,
    val isDark: Boolean,
) {
    /** Announcement priority tiers, 0 critical → 3 debug. */
    fun priority(tier: Int): Color = when (tier) {
        0 -> statusAlarm
        1 -> statusWarn
        2 -> statusOk
        else -> statusIdle
    }

    /** Colour for a semantic status name used across the design system. */
    fun status(name: String?): Color = when (name) {
        "ok" -> statusOk
        "warn" -> statusWarn
        "alarm" -> statusAlarm
        "info" -> statusInfo
        "accent" -> accent
        else -> inkMid
    }

    fun statusDim(name: String?): Color = when (name) {
        "ok" -> statusOkDim
        "warn" -> statusWarnDim
        "alarm" -> statusAlarmDim
        "info" -> statusInfoDim
        "accent" -> accentDim
        else -> Color.Transparent
    }
}

val MkDarkColors = MkColors(
    bg0 = Color(0xFF0B0E12),
    appBg = Color(0xFF0E1116),
    surfaceInset = Color(0xFF12171E),
    surfaceCard = Color(0xFF171C23),
    surfaceRaised = Color(0xFF1E242C),
    track = Color(0xFF242B34),
    hairline = Color(0xFF2C333D),
    borderSubtle = Color(0x12FFFFFF),
    borderStrong = Color(0x1FFFFFFF),
    inkHi = Color(0xFFE8EDF2),
    inkMid = Color(0xFF9AA6B2),
    inkLo = Color(0xFF5E6975),
    inkOnAccent = Color(0xFF07130F),
    inkOnWarm = Color(0xFF241800),
    accent = Color(0xFF35E0C8),
    accentStrong = Color(0xFF4FF0D8),
    accentDim = Color(0x1F35E0C8),
    accentBorder = Color(0x4D35E0C8),
    accentGlow = Color(0x5935E0C8),
    warm = Color(0xFFFFB347),
    warmDim = Color(0x1FFFB347),
    warmBorder = Color(0x4DFFB347),
    statusOk = Color(0xFF4BD18A),
    statusOkDim = Color(0x1F4BD18A),
    statusWarn = Color(0xFFFFB347),
    statusWarnInk = Color(0xFFFFB347),
    statusWarnDim = Color(0x1AFFB347),
    statusAlarm = Color(0xFFE5484D),
    statusAlarmInk = Color(0xFFFF7A80),
    statusAlarmDim = Color(0x1CE5484D),
    statusInfo = Color(0xFF5AA2FF),
    statusInfoDim = Color(0x1F5AA2FF),
    statusIdle = Color(0xFF5E6975),
    vizOutdoor = Color(0xFF5AA2FF),
    vizRoom = Color(0xFFFFB347),
    vizSecondary = Color(0xFF4BD18A),
    vizTertiary = Color(0xFFC77DFF),
    vizAccent = Color(0xFF35E0C8),
    vizAlarm = Color(0xFFE5484D),
    vizGrid = Color(0x0DFFFFFF),
    vizAxis = Color(0xFF5E6975),
    isDark = true,
)

val MkLightColors = MkColors(
    bg0 = Color(0xFFE4E9EF),
    appBg = Color(0xFFEEF1F5),
    surfaceInset = Color(0xFFF6F8FA),
    surfaceCard = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    track = Color(0xFFE5EAEF),
    hairline = Color(0xFFDDE3E9),
    borderSubtle = Color(0x1714202D),
    borderStrong = Color(0x2914202D),
    inkHi = Color(0xFF16202B),
    inkMid = Color(0xFF55636F),
    inkLo = Color(0xFF93A0AC),
    inkOnAccent = Color(0xFFFFFFFF),
    inkOnWarm = Color(0xFFFFFFFF),
    accent = Color(0xFF0E9E8B),
    accentStrong = Color(0xFF0B8577),
    accentDim = Color(0x1A0E9E8B),
    accentBorder = Color(0x470E9E8B),
    accentGlow = Color(0x380E9E8B),
    warm = Color(0xFFE07B39),
    warmDim = Color(0x1AE07B39),
    warmBorder = Color(0x42E07B39),
    statusOk = Color(0xFF1F9D57),
    statusOkDim = Color(0x1A1F9D57),
    statusWarn = Color(0xFFC9781F),
    statusWarnInk = Color(0xFFB56A16),
    statusWarnDim = Color(0x1AC9781F),
    statusAlarm = Color(0xFFD64550),
    statusAlarmInk = Color(0xFFC13540),
    statusAlarmDim = Color(0x17D64550),
    statusInfo = Color(0xFF2F6FD0),
    statusInfoDim = Color(0x1A2F6FD0),
    statusIdle = Color(0xFF93A0AC),
    vizOutdoor = Color(0xFF2F6FD0),
    vizRoom = Color(0xFFD98324),
    vizSecondary = Color(0xFF1F9D57),
    vizTertiary = Color(0xFF8A4FD0),
    vizAccent = Color(0xFF0E9E8B),
    vizAlarm = Color(0xFFD64550),
    vizGrid = Color(0x1214202D),
    vizAxis = Color(0xFF93A0AC),
    isDark = false,
)

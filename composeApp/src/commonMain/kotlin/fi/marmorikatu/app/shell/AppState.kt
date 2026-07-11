package fi.marmorikatu.app.shell

import androidx.compose.ui.graphics.vector.ImageVector
import fi.marmorikatu.app.icons.MkIcons

/**
 * The six destinations of the app, in the order the design's tab bar shows
 * them. The tablet nav rail shows the same set, abbreviating "Tapahtumat"
 * to "Loki".
 */
enum class Tab(
    val key: String,
    val label: String,
    val railLabel: String = label,
    /** The screen title shown in the header; Koti is greeting-driven. */
    val title: String?,
    val icon: ImageVector,
    val iconActive: ImageVector? = null,
) {
    Koti("koti", "Koti", title = null, icon = MkIcons.House, iconActive = MkIcons.HouseFill),
    Valot("valot", "Valot", title = "Valot", icon = MkIcons.Lightbulb, iconActive = MkIcons.LightbulbFill),
    Ilmasto("ilmasto", "Ilmasto", title = "Ilmasto", icon = MkIcons.Thermometer, iconActive = MkIcons.ThermometerFill),
    Energia("energia", "Energia", title = "Energia", icon = MkIcons.Lightning, iconActive = MkIcons.LightningFill),
    Bussit("bussit", "Bussit", title = "Bussit", icon = MkIcons.Bus, iconActive = MkIcons.BusFill),
    // MkIcons has no calendar glyph yet; Clock stands in until one is added.
    Kalenteri("kalenteri", "Kalenteri", title = "Kalenteri", icon = MkIcons.Clock),
    Tapahtumat("loki", "Tapahtumat", railLabel = "Loki", title = "Tapahtumat", icon = MkIcons.Bell, iconActive = MkIcons.BellFill),
}

/**
 * Which of the three surfaces the design describes is being shown.
 *
 * On a real device this follows the window size — a phone gets [Phone], the
 * shelf tablet gets [Tablet]. [Kid] is a deliberate mode a parent switches
 * on, not a size class.
 */
enum class Surface { Phone, Kid, Tablet }

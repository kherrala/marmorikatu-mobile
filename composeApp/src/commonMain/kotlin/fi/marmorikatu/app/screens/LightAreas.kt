package fi.marmorikatu.app.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.core.model.Floor

/** One fixture in an area: its PLC light id and the short label the design shows. */
data class AreaLight(val id: Int, val label: String)

/** A named group of fixtures on one floor — the unit the Valot screen renders as a card. */
data class LightArea(
    val key: String,
    val floor: Floor,
    val name: String,
    val icon: ImageVector,
    /** Per-area accent for the card icon (design `hue`) — makes rooms scannable. */
    val hue: Color,
    val lights: List<AreaLight>,
)

private val Accent = Color(0xFF35C3A6)

/**
 * The house's light areas, transcribed verbatim from the Claude design
 * (`MarmorikatuApp.dc.html` → `vaAreas`): the grouping, friendly per-fixture
 * labels, icons, per-area hue and floor assignment all come from the design,
 * keyed on the real PLC light ids so the live retained state maps straight on.
 */
val LIGHT_AREAS: List<LightArea> = listOf(
    LightArea("ala_keittio", Floor.ALAKERTA, "Keittiö", MkIcons.CookingPot, Color(0xFFFFB347), listOf(
        AreaLight(8, "Katto"), AreaLight(40, "Kattovalo"), AreaLight(2, "Kaapisto ylä"),
        AreaLight(7, "Kaapisto ala"), AreaLight(41, "Ikkunavalo"))),
    LightArea("ala_olohuone", Floor.ALAKERTA, "Olohuone", MkIcons.Armchair, Accent, listOf(
        AreaLight(54, "Kattovalo"), AreaLight(55, "Kattovalo 2"), AreaLight(5, "LED-nauha"),
        AreaLight(46, "Ikkuna"))),
    LightArea("ala_ruokailu", Floor.ALAKERTA, "Ruokailu", MkIcons.ForkKnife, Color(0xFFE8A06A), listOf(
        AreaLight(19, "Kattovalo"), AreaLight(20, "Ikkuna"))),
    LightArea("ala_eteinen", Floor.ALAKERTA, "Eteinen", MkIcons.Door, Color(0xFF8FB7FF), listOf(
        AreaLight(35, "Eteinen"), AreaLight(37, "Tuulikaappi"), AreaLight(36, "Vaatehuone"))),
    LightArea("ala_mh", Floor.ALAKERTA, "MH alakerta", MkIcons.Briefcase, Color(0xFF9DB4D0), listOf(
        AreaLight(17, "Kattovalo"), AreaLight(18, "Ikkuna"))),
    LightArea("ala_sauna", Floor.ALAKERTA, "Sauna & kylpyhuone", MkIcons.ThermometerHot, Color(0xFFFF9A5C), listOf(
        AreaLight(4, "Laude LED"), AreaLight(38, "Siivousvalo"), AreaLight(1, "Kylpyhuone"))),
    LightArea("ala_khh", Floor.ALAKERTA, "Kodinhoitohuone", MkIcons.WashingMachine, Color(0xFF7FD4C0), listOf(
        AreaLight(6, "LED"), AreaLight(56, "Kattovalo"), AreaLight(43, "Vaatehuone"))),
    LightArea("ala_portaikko", Floor.ALAKERTA, "Portaikko", MkIcons.Stairs, Color(0xFFC4A0E8), listOf(
        AreaLight(42, "Portaikko"))),
    LightArea("ala_wc", Floor.ALAKERTA, "WC", MkIcons.Toilet, Color(0xFF7FA8E8), listOf(
        AreaLight(44, "Katto"), AreaLight(45, "Peilivalo"))),
    LightArea("ulko_tekninen", Floor.ALAKERTA, "Tekninen tila", MkIcons.GearSix, Color(0xFF9AA7B8), listOf(
        AreaLight(39, "Tekninen tila"))),
    LightArea("yla_aula", Floor.YLAKERTA, "Aula", MkIcons.Stack, Accent, listOf(
        AreaLight(26, "Kattovalo"), AreaLight(3, "LED"), AreaLight(25, "Rappuset"),
        AreaLight(24, "Ikkunavalo"))),
    // PLC indices 22/23 and 28/30 were mapped to the wrong bedrooms — Aarni's card
    // toggled Seela's fixtures and vice versa — so the id sets are swapped here to
    // match the physical rooms (kept in sync with LIGHT_LABELS in the backend).
    LightArea("yla_aarni", Floor.YLAKERTA, "Aarni", MkIcons.Bed, Color(0xFF8FB7FF), listOf(
        AreaLight(28, "Kattovalo"), AreaLight(30, "Ikkunavalo"))),
    LightArea("yla_seela", Floor.YLAKERTA, "Seela", MkIcons.Bed, Color(0xFFF2A7C3), listOf(
        AreaLight(22, "Kattovalo"), AreaLight(23, "Ikkunavalo"))),
    LightArea("yla_aikuiset", Floor.YLAKERTA, "Aikuiset", MkIcons.Bed, Color(0xFFC4A0E8), listOf(
        AreaLight(33, "Kattovalo"), AreaLight(32, "Ikkunavalo"), AreaLight(31, "Vaatehuone"))),
    LightArea("yla_kylpy", Floor.YLAKERTA, "Kylpyhuone", MkIcons.Bathtub, Color(0xFF7FC8E8), listOf(
        AreaLight(29, "Katto"), AreaLight(34, "Peilivalo"))),
    LightArea("kel_teatteri", Floor.KELLARI, "Teatteri / työhuone", MkIcons.FilmSlate, Color(0xFFC48FD9), listOf(
        AreaLight(51, "Biljardivalo"), AreaLight(49, "Loisteputki etu"), AreaLight(50, "Loisteputki taka"))),
    LightArea("kel_wc", Floor.KELLARI, "WC", MkIcons.Toilet, Color(0xFF7FA8E8), listOf(
        AreaLight(52, "WC"))),
    LightArea("kel_varasto", Floor.KELLARI, "Varasto", MkIcons.Package, Color(0xFFB8AB90), listOf(
        AreaLight(53, "Varasto"))),
    LightArea("ulko_varasto", Floor.ULKO, "Autokatos", MkIcons.Warehouse, Color(0xFF9DB4D0), listOf(
        AreaLight(59, "Autokatos"), AreaLight(61, "Varasto"), AreaLight(60, "Ulkovalo"))),
    LightArea("ulko_piha", Floor.ULKO, "Ulko- ja pihavalot", MkIcons.TreeEvergreen, Color(0xFF6EC98F), listOf(
        AreaLight(47, "Sisäänkäynti"), AreaLight(48, "Terassi"))),
)

/** Floors in the order the design's Valot screen stacks them (outdoors first). */
val VALOT_FLOOR_ORDER: List<Floor> = listOf(Floor.ULKO, Floor.ALAKERTA, Floor.KELLARI, Floor.YLAKERTA)

/** Display name + icon for a floor heading / tab, per the design. */
fun valotFloorMeta(floor: Floor): Pair<String, ImageVector> = when (floor) {
    Floor.ULKO -> "Ulko" to MkIcons.TreeEvergreen
    Floor.ALAKERTA -> "Alakerta" to MkIcons.HouseLine
    Floor.KELLARI -> "Kellari" to MkIcons.Stairs
    Floor.YLAKERTA -> "Yläkerta" to MkIcons.Stack
}

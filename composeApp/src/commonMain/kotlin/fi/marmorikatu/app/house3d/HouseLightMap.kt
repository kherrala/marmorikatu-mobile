package fi.marmorikatu.app.house3d

/**
 * Bridges the GLB's `Light_*` / `Room_*` node names to the app's light-area
 * catalog (`screens/LightAreas.kt`, keyed by [fi.marmorikatu.app.screens.LightArea.key]).
 *
 * This is a hand-maintained mapping — the model and the PLC light catalog were
 * authored independently, so there is no programmatic join. A wrong entry only
 * mislabels which room a fixture glows in; it can't crash.
 */
object HouseLightMap {

    /**
     * GLB light-anchor name → the light-area key whose on-state drives its glow.
     * Names match the consolidated `lights` in the current `cameras.json` (the
     * export merges each switch's 4–6 series LEDs into one anchor, so there is one
     * entry per switch, not per bulb).
     */
    val anchorToArea: Map<String, String> = mapOf(
        // 1. krs
        "Light_1krs_ET" to "ala_eteinen",
        "Light_1krs_TK" to "ala_eteinen",
        "Light_1krs_VH" to "ala_eteinen",
        "Light_1krs_VH2" to "ala_eteinen",
        "Light_1krs_KHH" to "ala_khh",
        "Light_1krs_KT" to "ala_keittio",
        "Light_1krs_SAAREKE" to "ala_keittio",   // island pendants
        "Light_1krs_RUOKAILU" to "ala_ruokailu",
        "Light_1krs_OH" to "ala_olohuone",
        "Light_1krs_IKKUNA" to "ala_olohuone",    // decorative window lights over the wing glazing
        "Light_1krs_LH" to "ala_sauna",
        "Light_1krs_PH" to "ala_sauna",
        "Light_1krs_MH" to "ala_mh",
        "Light_1krs_PORRAS" to "ala_portaikko",
        "Light_1krs_WC" to "ala_wc",
        "Light_1krs_TEKN" to "ulko_tekninen",
        // Kellari
        "Light_kellari_VAR1_1" to "kel_teatteri",
        "Light_kellari_VAR1_2" to "kel_teatteri",
        "Light_kellari_WC" to "kel_wc",
        "Light_kellari_VAR2_1" to "kel_varasto",
        "Light_kellari_VAR2_2" to "kel_varasto",
        // Katos / autokatos
        "Light_katos_1" to "ulko_varasto",
        "Light_katos_2" to "ulko_varasto",
        "Light_katos_VAR" to "ulko_varasto",
        "Light_ulko_katos" to "ulko_varasto",
        // Ulko
        "Light_ulko_etuovi_1" to "ulko_piha",
        "Light_ulko_etuovi_2" to "ulko_piha",
        "Light_ulko_terassi_1" to "ulko_piha",
        "Light_ulko_terassi_2" to "ulko_piha",
        "Light_ulko_parveke" to "ulko_piha",
        "Light_ulko_piha_1" to "ulko_piha",
        "Light_ulko_piha_2" to "ulko_piha",
        "Light_ulko_piha_3" to "ulko_piha",
        "Light_ulko_tekn" to "ulko_tekninen",
        // 2. krs
        "Light_2krs_AULA" to "yla_aula",
        "Light_2krs_AULA_KATTO" to "yla_aula",
        "Light_2krs_KPH" to "yla_kylpy",
        "Light_2krs_MH" to "yla_aikuiset",
        "Light_2krs_VH" to "yla_aikuiset",
        "Light_2krs_MH2" to "yla_aarni",
        "Light_2krs_MH3" to "yla_seela",
    )

    /** GLB room-patch name → the light-area keys physically inside that room. */
    val roomToAreas: Map<String, List<String>> = mapOf(
        // The open-plan wing is now three separately-focusable zones.
        "Room_1krs_KT" to listOf("ala_keittio"),
        "Room_1krs_RUOKAILU" to listOf("ala_ruokailu"),
        "Room_1krs_OH" to listOf("ala_olohuone"),
        "Room_1krs_ET" to listOf("ala_eteinen"),
        "Room_1krs_ET2" to listOf("ala_eteinen"),
        "Room_1krs_KHH" to listOf("ala_khh"),
        "Room_1krs_LH" to listOf("ala_sauna"),
        "Room_1krs_PH" to listOf("ala_sauna"),
        "Room_1krs_MH" to listOf("ala_mh"),
        "Room_1krs_PORRAS" to listOf("ala_portaikko"),
        "Room_1krs_TEKN" to listOf("ulko_tekninen"),
        "Room_1krs_TK" to listOf("ala_eteinen"),
        "Room_1krs_VH" to listOf("ala_eteinen"),
        "Room_1krs_VH2" to listOf("ala_eteinen"),
        "Room_1krs_WC" to listOf("ala_wc"),
        "Room_kellari_VAR1" to listOf("kel_teatteri"),
        "Room_kellari_VAR2" to listOf("kel_varasto"),
        "Room_kellari_WC" to listOf("kel_wc"),
        "Room_katos_AUTOKATOS" to listOf("ulko_varasto"),
        "Room_katos_VAR" to listOf("ulko_varasto"),
        "Room_2krs_AULA" to listOf("yla_aula"),
        "Room_2krs_KPH" to listOf("yla_kylpy"),
        "Room_2krs_MH" to listOf("yla_aikuiset"),
        "Room_2krs_MH2" to listOf("yla_aarni"),
        "Room_2krs_MH3" to listOf("yla_seela"),
        "Room_2krs_VH" to listOf("yla_aikuiset"),
    )

    /** Friendly Finnish room title for the selection panel, keyed by patch name. */
    fun roomTitle(roomName: String): String = ROOM_TITLES[roomName] ?: roomName
        .removePrefix("Room_").replace('_', ' ')

    private val ROOM_TITLES: Map<String, String> = mapOf(
        "Room_1krs_KT_RUOKAILU_OH" to "Keittiö · Ruokailu · Olohuone",
        "Room_1krs_KT" to "Keittiö",
        "Room_1krs_RUOKAILU" to "Ruokailu",
        "Room_1krs_OH" to "Olohuone",
        "Room_1krs_ET" to "Eteinen",
        "Room_1krs_ET2" to "Tuulikaappi",
        "Room_1krs_KHH" to "Kodinhoitohuone",
        "Room_1krs_LH" to "Löylyhuone",
        "Room_1krs_PH" to "Pesuhuone",
        "Room_1krs_MH" to "Makuuhuone",
        "Room_1krs_PORRAS" to "Portaikko",
        "Room_1krs_TEKN" to "Tekninen tila",
        "Room_1krs_TK" to "Tuulikaappi",
        "Room_1krs_VH" to "Vaatehuone",
        "Room_1krs_VH2" to "Vaatehuone",
        "Room_1krs_WC" to "WC",
        "Room_kellari_VAR1" to "Teatteri · työhuone",
        "Room_kellari_VAR2" to "Varasto",
        "Room_kellari_WC" to "WC",
        "Room_katos_AUTOKATOS" to "Autokatos",
        "Room_katos_VAR" to "Ulkovarasto",
        "Room_2krs_AULA" to "Aula",
        "Room_2krs_KPH" to "Kylpyhuone",
        "Room_2krs_MH" to "Aikuisten makuuhuone",
        "Room_2krs_MH2" to "Aarni",
        "Room_2krs_MH3" to "Seela",
        "Room_2krs_VH" to "Vaatehuone",
    )
}

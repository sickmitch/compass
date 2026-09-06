package org.compass.cng.ui.route

/**
 * Presentation-only interpretation of Valhalla's stable maneuver type identifiers.
 *
 * Navigation and routing continue to use the original maneuver. This model only chooses the
 * vector glyph and its accessibility label, keeping localized instruction parsing as a bounded
 * fallback for an unknown future type.
 */
internal data class ManeuverVisual(
    val type: Int?,
    val family: ManeuverVisualFamily,
    val direction: ManeuverDirection = ManeuverDirection.NONE,
    val accessibilityLabel: String,
)

internal enum class ManeuverVisualFamily {
    UNKNOWN,
    START,
    DESTINATION,
    STRAIGHT,
    TURN,
    U_TURN,
    RAMP,
    EXIT,
    KEEP,
    MERGE,
    ROUNDABOUT_ENTER,
    ROUNDABOUT_EXIT,
    FERRY_ENTER,
    FERRY_EXIT,
    TRANSIT,
    TRANSIT_TRANSFER,
    TRANSIT_REMAIN,
    TRANSIT_CONNECTION_START,
    TRANSIT_CONNECTION_TRANSFER,
    TRANSIT_CONNECTION_DESTINATION,
    POST_TRANSIT_CONNECTION_DESTINATION,
}

internal enum class ManeuverDirection {
    NONE,
    STRAIGHT,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    U_TURN_RIGHT,
    U_TURN_LEFT,
    SHARP_LEFT,
    LEFT,
    SLIGHT_LEFT,
}

internal fun maneuverVisual(type: Int?, instruction: String?): ManeuverVisual = when (type) {
    0 -> visual(type, ManeuverVisualFamily.STRAIGHT, ManeuverDirection.STRAIGHT, "Prosegui")
    1 -> visual(type, ManeuverVisualFamily.START, ManeuverDirection.STRAIGHT, "Partenza")
    2 -> visual(type, ManeuverVisualFamily.START, ManeuverDirection.RIGHT, "Partenza verso destra")
    3 -> visual(type, ManeuverVisualFamily.START, ManeuverDirection.LEFT, "Partenza verso sinistra")
    4 -> visual(type, ManeuverVisualFamily.DESTINATION, label = "Destinazione")
    5 -> visual(type, ManeuverVisualFamily.DESTINATION, ManeuverDirection.RIGHT, "Destinazione a destra")
    6 -> visual(type, ManeuverVisualFamily.DESTINATION, ManeuverDirection.LEFT, "Destinazione a sinistra")
    7 -> visual(type, ManeuverVisualFamily.STRAIGHT, ManeuverDirection.STRAIGHT, "La strada prosegue")
    8 -> visual(type, ManeuverVisualFamily.STRAIGHT, ManeuverDirection.STRAIGHT, "Prosegui diritto")
    9 -> visual(type, ManeuverVisualFamily.TURN, ManeuverDirection.SLIGHT_RIGHT, "Svolta leggermente a destra")
    10 -> visual(type, ManeuverVisualFamily.TURN, ManeuverDirection.RIGHT, "Svolta a destra")
    11 -> visual(type, ManeuverVisualFamily.TURN, ManeuverDirection.SHARP_RIGHT, "Svolta stretta a destra")
    12 -> visual(type, ManeuverVisualFamily.U_TURN, ManeuverDirection.U_TURN_RIGHT, "Inversione a destra")
    13 -> visual(type, ManeuverVisualFamily.U_TURN, ManeuverDirection.U_TURN_LEFT, "Inversione a sinistra")
    14 -> visual(type, ManeuverVisualFamily.TURN, ManeuverDirection.SHARP_LEFT, "Svolta stretta a sinistra")
    15 -> visual(type, ManeuverVisualFamily.TURN, ManeuverDirection.LEFT, "Svolta a sinistra")
    16 -> visual(type, ManeuverVisualFamily.TURN, ManeuverDirection.SLIGHT_LEFT, "Svolta leggermente a sinistra")
    17 -> visual(type, ManeuverVisualFamily.RAMP, ManeuverDirection.STRAIGHT, "Prendi la rampa diritto")
    18 -> visual(type, ManeuverVisualFamily.RAMP, ManeuverDirection.RIGHT, "Prendi la rampa a destra")
    19 -> visual(type, ManeuverVisualFamily.RAMP, ManeuverDirection.LEFT, "Prendi la rampa a sinistra")
    20 -> visual(type, ManeuverVisualFamily.EXIT, ManeuverDirection.RIGHT, "Prendi l'uscita a destra")
    21 -> visual(type, ManeuverVisualFamily.EXIT, ManeuverDirection.LEFT, "Prendi l'uscita a sinistra")
    22 -> visual(type, ManeuverVisualFamily.KEEP, ManeuverDirection.STRAIGHT, "Mantieni la direzione")
    23 -> visual(type, ManeuverVisualFamily.KEEP, ManeuverDirection.RIGHT, "Mantieni la destra")
    24 -> visual(type, ManeuverVisualFamily.KEEP, ManeuverDirection.LEFT, "Mantieni la sinistra")
    25 -> visual(type, ManeuverVisualFamily.MERGE, ManeuverDirection.STRAIGHT, "Immettiti")
    26 -> visual(type, ManeuverVisualFamily.ROUNDABOUT_ENTER, label = "Entra nella rotatoria")
    27 -> visual(type, ManeuverVisualFamily.ROUNDABOUT_EXIT, label = "Esci dalla rotatoria")
    28 -> visual(type, ManeuverVisualFamily.FERRY_ENTER, label = "Imbarcati sul traghetto")
    29 -> visual(type, ManeuverVisualFamily.FERRY_EXIT, label = "Sbarca dal traghetto")
    30 -> visual(type, ManeuverVisualFamily.TRANSIT, label = "Prendi il trasporto pubblico")
    31 -> visual(type, ManeuverVisualFamily.TRANSIT_TRANSFER, label = "Cambia trasporto pubblico")
    32 -> visual(type, ManeuverVisualFamily.TRANSIT_REMAIN, label = "Rimani sul trasporto pubblico")
    33 -> visual(type, ManeuverVisualFamily.TRANSIT_CONNECTION_START, label = "Raggiungi il trasporto pubblico")
    34 -> visual(type, ManeuverVisualFamily.TRANSIT_CONNECTION_TRANSFER, label = "Raggiungi la coincidenza")
    35 -> visual(type, ManeuverVisualFamily.TRANSIT_CONNECTION_DESTINATION, label = "Raggiungi la destinazione")
    36 -> visual(type, ManeuverVisualFamily.POST_TRANSIT_CONNECTION_DESTINATION, label = "Prosegui verso la destinazione")
    else -> unknownManeuverVisual(type, instruction)
}

private fun visual(
    type: Int?,
    family: ManeuverVisualFamily,
    direction: ManeuverDirection = ManeuverDirection.NONE,
    label: String,
) = ManeuverVisual(type, family, direction, label)

private fun unknownManeuverVisual(type: Int?, instruction: String?): ManeuverVisual {
    val normalized = instruction.orEmpty()
    return when {
        normalized.contains("rotatoria", ignoreCase = true) ->
            visual(type, ManeuverVisualFamily.ROUNDABOUT_ENTER, label = "Rotatoria")
        normalized.contains("destra", ignoreCase = true) ->
            visual(type, ManeuverVisualFamily.TURN, ManeuverDirection.RIGHT, "Svolta a destra")
        normalized.contains("sinistra", ignoreCase = true) ->
            visual(type, ManeuverVisualFamily.TURN, ManeuverDirection.LEFT, "Svolta a sinistra")
        normalized.contains("destinazione", ignoreCase = true) ->
            visual(type, ManeuverVisualFamily.DESTINATION, label = "Destinazione")
        else -> visual(type, ManeuverVisualFamily.UNKNOWN, ManeuverDirection.STRAIGHT, "Manovra successiva")
    }
}

internal val valhallaManeuverVisualCatalog: List<ManeuverVisual> =
    (0..36).map { type -> maneuverVisual(type, null) }

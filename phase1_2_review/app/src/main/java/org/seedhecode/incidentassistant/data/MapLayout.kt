package org.seedhecode.incidentassistant.data

import kotlin.math.max
import kotlin.math.min

// Plain Kotlin, no Android types: the map's geometry and hit-testing are unit-tested on the JVM.

data class MapPoint(val x: Float, val y: Float)
data class MapZone(val zoneId: String, val name: String, val floor: String?, val outline: List<MapPoint>) {
    /** Fewer than three points cannot be drawn or tapped; such a zone is left off the map. */
    val drawable: Boolean get() = outline.size >= 3
}
data class MapData(val planWidth: Float, val planHeight: Float, val zones: List<MapZone>, val incidents: List<IncidentListing>)

/** Lower is more urgent. Taxonomy P1-P4 and the schema's High/Medium/Low both map; anything else ranks last. */
object PriorityRank {
    const val UNKNOWN = 4
    fun of(priority: String): Int = when (priority.trim().uppercase()) {
        "P1" -> 0
        "P2", "HIGH" -> 1
        "P3", "MEDIUM" -> 2
        "P4", "LOW" -> 3
        else -> UNKNOWN
    }
}

/** One floor of [data] laid out in a view of the given size. Rebuild it when the data, floor or size changes. */
class MapLayout(
    private val data: MapData, val floor: String?, viewWidth: Float, viewHeight: Float,
    private val pinRadius: Float, padding: Float,
) {
    /** [urgentRank] is the most urgent rank among the zone's unresolved incidents; null means none (neutral). */
    data class ZoneShape(val zone: MapZone, val outline: List<MapPoint>, val incidents: List<IncidentListing>, val urgentRank: Int?)
    data class Pin(val incident: IncidentListing, val center: MapPoint) {
        val unresolved: Boolean get() = incident.status != IncidentStatus.RESOLVED
    }
    sealed interface Hit {
        data class OnPin(val incident: IncidentListing) : Hit
        data class OnZone(val zone: MapZone, val incidents: List<IncidentListing>) : Hit
    }

    val shapes: List<ZoneShape>
    val pins: List<Pin>

    init {
        val (planW, planH) = planSize(data)
        val scale = min((viewWidth - 2 * padding) / planW, (viewHeight - 2 * padding) / planH).coerceAtLeast(0f)
        val offsetX = (viewWidth - planW * scale) / 2
        val offsetY = (viewHeight - planH * scale) / 2
        fun toView(p: MapPoint) = MapPoint(offsetX + p.x * scale, offsetY + p.y * scale)

        val byZone = data.incidents.filter { it.zoneId != null }.groupBy { it.zoneId }
        shapes = data.zones.filter { it.drawable && it.floor == floor }.map { zone ->
            // Most urgent first, so a crowded zone shows its worst incident at the front.
            val incidents = byZone[zone.zoneId].orEmpty().sortedWith(compareBy({ it.status == IncidentStatus.RESOLVED }, { PriorityRank.of(it.priority) }))
            ZoneShape(zone, zone.outline.map(::toView), incidents,
                incidents.filter { it.status != IncidentStatus.RESOLVED }.minOfOrNull { PriorityRank.of(it.priority) })
        }
        pins = shapes.flatMap { shape -> placePins(shape) }
    }

    /** Pins sit on a small grid around the zone's centre, as many per row as fit inside its width. */
    private fun placePins(shape: ZoneShape): List<Pin> {
        if (shape.incidents.isEmpty()) return emptyList()
        val left = shape.outline.minOf { it.x }; val right = shape.outline.maxOf { it.x }
        val top = shape.outline.minOf { it.y }; val bottom = shape.outline.maxOf { it.y }
        val spacing = pinRadius * 2.6f
        val columns = max(1, ((right - left - 2 * pinRadius) / spacing).toInt() + 1).coerceAtMost(shape.incidents.size)
        val rows = (shape.incidents.size + columns - 1) / columns
        val startX = (left + right) / 2 - (columns - 1) * spacing / 2
        val startY = (top + bottom) / 2 - (rows - 1) * spacing / 2
        return shape.incidents.mapIndexed { i, incident ->
            Pin(incident, MapPoint(startX + (i % columns) * spacing, startY + (i / columns) * spacing))
        }
    }

    /** Pins take precedence over the zone under them; later-drawn items win over earlier ones. */
    fun hit(x: Float, y: Float): Hit? {
        val reach = pinRadius * 1.4f
        pins.lastOrNull { (it.center.x - x) * (it.center.x - x) + (it.center.y - y) * (it.center.y - y) <= reach * reach }
            ?.let { return Hit.OnPin(it.incident) }
        return shapes.lastOrNull { contains(it.outline, x, y) }?.let { Hit.OnZone(it.zone, it.incidents) }
    }

    companion object {
        /** Floors that have at least one drawable zone, ordered by building then floor number. */
        fun floors(data: MapData): List<String?> = data.zones.filter { it.drawable }.map { it.floor }.distinct()
            .sortedWith(compareBy<String?>({ it == null }, { it?.substringBefore('-') }, { it?.substringAfter('-', "")?.toIntOrNull() ?: -1 }))

        /** The floor holding the most urgent unresolved pinned incident, else the first floor. */
        fun defaultFloor(data: MapData): String? {
            val floorOf = data.zones.filter { it.drawable }.associate { it.zoneId to it.floor }
            return data.incidents.filter { it.status != IncidentStatus.RESOLVED && it.zoneId in floorOf }
                .minByOrNull { PriorityRank.of(it.priority) }?.let { floorOf[it.zoneId] } ?: floors(data).firstOrNull()
        }

        /** Floorplan size, grown to cover any zone drawn outside it; never zero. */
        fun planSize(data: MapData): Pair<Float, Float> {
            val points = data.zones.filter { it.drawable }.flatMap { it.outline }
            return max(max(data.planWidth, points.maxOfOrNull { it.x } ?: 0f), 1f) to
                max(max(data.planHeight, points.maxOfOrNull { it.y } ?: 0f), 1f)
        }

        /** Even-odd ray casting; works for any simple polygon, not just rectangles. */
        fun contains(outline: List<MapPoint>, x: Float, y: Float): Boolean {
            var inside = false
            var j = outline.size - 1
            for (i in outline.indices) {
                val a = outline[i]; val b = outline[j]
                if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
                j = i
            }
            return inside
        }
    }
}

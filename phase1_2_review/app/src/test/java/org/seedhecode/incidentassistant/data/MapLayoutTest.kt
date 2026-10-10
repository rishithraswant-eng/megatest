package org.seedhecode.incidentassistant.data

import org.junit.Assert.*
import org.junit.Test

class MapLayoutTest {
    private fun rect(x1: Float, y1: Float, x2: Float, y2: Float) = listOf(MapPoint(x1, y1), MapPoint(x2, y1), MapPoint(x2, y2), MapPoint(x1, y2))
    private fun incident(id: String, zone: String?, priority: String, status: IncidentStatus = IncidentStatus.OPEN) =
        IncidentListing(id, "Type $id", "FAC", priority, status, null, "desc $id", "2026-10-11T00:00:00Z", true, zone)

    private val zoneA = MapZone("A", "Pantry", "N-1", rect(0f, 0f, 100f, 100f))
    private val zoneB = MapZone("B", "Cafeteria", "N-1", rect(100f, 0f, 200f, 100f))
    private val zoneC = MapZone("C", "Server Room", "N-2", rect(0f, 0f, 100f, 100f))   // same pixels, another floor
    private val zoneD = MapZone("D", "Broken outline", "N-1", emptyList())
    private val data = MapData(200f, 100f, listOf(zoneA, zoneB, zoneC, zoneD), listOf(
        incident("i1", "A", "P3"),
        incident("i2", "A", "P1", IncidentStatus.IN_PROGRESS),
        incident("i3", "B", "P2", IncidentStatus.RESOLVED),
        incident("i4", null, "P1"),           // no resolved zone
        incident("i5", "ghost", "P1"),        // zone id not on the map
        incident("i6", "D", "P1"),            // zone that cannot be drawn
    ))
    // 400x300 view, plan 200x100: scale 2, drawn 400x200 and centred vertically (offset y = 50).
    private fun layout(floor: String? = "N-1") = MapLayout(data, floor, 400f, 300f, pinRadius = 6f, padding = 0f)

    @Test fun `priority ranks put P1 first and accept the schema words`() {
        assertEquals(listOf(0, 1, 2, 3, PriorityRank.UNKNOWN), listOf("P1", "p2", " P3 ", "P4", "urgent").map(PriorityRank::of))
        assertEquals(listOf(1, 2, 3), listOf("High", "Medium", "Low").map(PriorityRank::of))
    }

    @Test fun `floors list drawable floors in building then floor order and default to the most urgent one`() {
        assertEquals(listOf("N-1", "N-2"), MapLayout.floors(data))
        assertEquals(listOf("N-1", "N-2", "N-10", "QRC-1", null), MapLayout.floors(MapData(0f, 0f, listOf("QRC-1", "N-10", null, "N-2", "N-1")
            .mapIndexed { i, f -> MapZone("z$i", "z$i", f, rect(0f, 0f, 1f, 1f)) }, emptyList())))
        // i4-i6 are P1 but have no drawable zone, so the P1 in progress in zone A decides.
        assertEquals("N-1", MapLayout.defaultFloor(data))
        assertEquals("N-1", MapLayout.defaultFloor(data.copy(incidents = emptyList())))
    }

    @Test fun `zones are coloured by their most urgent unresolved incident and only the chosen floor is drawn`() {
        val shapes = layout().shapes.associateBy { it.zone.zoneId }
        assertEquals(setOf("A", "B"), shapes.keys)            // C is on N-2, D has no outline
        assertEquals(0, shapes.getValue("A").urgentRank)        // P1 in progress outranks P3 open
        assertNull(shapes.getValue("B").urgentRank)             // only a resolved incident: neutral
        assertEquals(listOf("i2", "i1"), shapes.getValue("A").incidents.map { it.incidentId })
        assertEquals(listOf(MapPoint(0f, 50f), MapPoint(200f, 50f), MapPoint(200f, 250f), MapPoint(0f, 250f)), shapes.getValue("A").outline)
        assertNull(layout("N-2").shapes.single().urgentRank)
        assertTrue(layout("N-2").pins.isEmpty())
    }

    @Test fun `incidents without a drawable zone get no pin and nothing fails`() {
        val pins = layout().pins
        assertEquals(listOf("i2", "i1", "i3"), pins.map { it.incident.incidentId })
        assertFalse(pins.single { it.incident.incidentId == "i3" }.unresolved)
        assertEquals(pins.size, pins.map { it.center }.toSet().size) // pins never share a spot
        for (pin in pins) {
            val zone = layout().shapes.single { it.zone.zoneId == pin.incident.zoneId }
            assertTrue(pin.incident.incidentId, MapLayout.contains(zone.outline, pin.center.x, pin.center.y))
        }
        // A map with no zones at all still lays out.
        assertTrue(MapLayout(MapData(0f, 0f, emptyList(), data.incidents), null, 400f, 300f, 6f, 0f).pins.isEmpty())
    }

    @Test fun `taps hit pins before zones and empty space hits nothing`() {
        val built = layout()
        val pin = built.pins.first()
        assertEquals(MapLayout.Hit.OnPin(pin.incident), built.hit(pin.center.x + 3f, pin.center.y))
        val zoneHit = built.hit(10f, 60f) as MapLayout.Hit.OnZone
        assertEquals("A", zoneHit.zone.zoneId); assertEquals(listOf("i2", "i1"), zoneHit.incidents.map { it.incidentId })
        assertEquals("B", (built.hit(390f, 240f) as MapLayout.Hit.OnZone).zone.zoneId)
        assertNull(built.hit(10f, 10f))      // above the drawn plan
        assertNull(built.hit(10f, 290f))     // below it
    }

    @Test fun `point in polygon works for non-rectangular outlines`() {
        val triangle = listOf(MapPoint(0f, 0f), MapPoint(10f, 0f), MapPoint(0f, 10f))
        assertTrue(MapLayout.contains(triangle, 2f, 2f))
        assertFalse(MapLayout.contains(triangle, 8f, 8f))
    }
}

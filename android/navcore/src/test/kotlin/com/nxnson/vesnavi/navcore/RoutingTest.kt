package com.nxnson.vesnavi.navcore

import com.nxnson.vesnavi.navcore.Fixtures.p0
import com.nxnson.vesnavi.navcore.Fixtures.p1
import com.nxnson.vesnavi.navcore.Fixtures.p2
import com.nxnson.vesnavi.navcore.Fixtures.p3
import com.nxnson.vesnavi.navcore.Fixtures.p4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderParsingTest {
    @Test
    fun `GraphHopper response is parsed`() {
        val route = GraphHopperProvider.parse(Fixtures.GRAPH_HOPPER_JSON.toByteArray())
        assertEquals(5, route.polyline.size)
        assertEquals(p0, route.polyline[0])
        assertEquals(60.0, route.duration, 0.001)
        assertEquals(listOf(Maneuver.Depart, Maneuver.Right, Maneuver.Arrive), route.steps.map { it.maneuver })
        assertEquals(listOf(0, 2, 4), route.steps.map { it.pointIndex })
        assertEquals("Đường B", route.steps[1].streetName)
        assertNull(route.steps[2].streetName, "empty street name should become null")
    }

    @Test
    fun `GraphHopper error message becomes a server error`() {
        val e = assertFailsWith<RoutingError.Server> { GraphHopperProvider.parse(Fixtures.GRAPH_HOPPER_ERROR.toByteArray()) }
        assertEquals("Cannot find point 0: 0.0,0.0", e.msg)
    }

    @Test
    fun `free plan answer to the scooter profile is a server error mentioning profile`() {
        val body = """{"message":"For your account the profile parameter can only be one of [car, bike, foot] but was scooter"}"""
        val e = assertFailsWith<RoutingError.Server> { GraphHopperProvider.parse(body.toByteArray()) }
        assertTrue("profile" in e.msg)
    }

    @Test
    fun `GraphHopper signs map to maneuvers`() {
        assertEquals(Maneuver.Left, GraphHopperProvider.maneuver(-2, null))
        assertEquals(Maneuver.KeepRight, GraphHopperProvider.maneuver(7, null))
        assertEquals(Maneuver.Roundabout(2), GraphHopperProvider.maneuver(6, 2))
        assertEquals(Maneuver.UTurnLeft, GraphHopperProvider.maneuver(-98, null))
        assertEquals(Maneuver.Straight, GraphHopperProvider.maneuver(5, null))
    }

    @Test
    fun `GraphHopper URL carries points profile and key`() {
        val url = GraphHopperProvider("KEY", "scooter") { ByteArray(0) }.url(p0, p4)
        assertTrue("point=10.77,106.7" in url)
        assertTrue("profile=scooter" in url)
        assertTrue("points_encoded=false" in url)
        assertTrue("key=KEY" in url)
    }

    @Test
    fun `OSRM steps are stitched into one polyline`() {
        val route = OSRMProvider.parse(Fixtures.OSRM_JSON.toByteArray())
        assertEquals(listOf(p0, p1, p2, p3, p4), route.polyline.take(5))
        assertEquals(listOf(Maneuver.Depart, Maneuver.Right, Maneuver.Arrive), route.steps.map { it.maneuver })
        assertEquals(listOf(0, 2, 4), route.steps.map { it.pointIndex })
        assertNull(route.steps[2].streetName)
        assertEquals("OSRM", route.provider)
    }

    @Test
    fun `OSRM NoRoute code`() {
        assertFailsWith<RoutingError.NoRoute> {
            OSRMProvider.parse("""{"code": "NoRoute", "message": "Impossible route"}""".toByteArray())
        }
    }

    @Test
    fun `garbage is an invalid response`() {
        assertFailsWith<RoutingError.InvalidResponse> { OSRMProvider.parse("<html>".toByteArray()) }
    }

    @Test
    fun `routing errors in both languages`() {
        assertEquals("No route found.", RoutingError.NoRoute.message(Language.EN))
        assertEquals("Không tìm được đường đi.", RoutingError.NoRoute.message)
    }
}

class NavigationEngineTest {
    @Test
    fun `start shows the upcoming turn`() {
        val s = NavigationEngine(Fixtures.lRoute).update(p0)
        assertEquals(Maneuver.Right, s.maneuver)
        assertEquals("Đường B", s.streetName)
        assertEquals("Rẽ phải vào Đường B", s.instruction)
        assertEquals(222.0, s.distanceToManeuver, 3.0)
        assertFalse(s.isOffRoute)
        assertFalse(s.hasArrived)
    }

    @Test
    fun `distance shrinks and snaps a fix slightly off the line`() {
        val engine = NavigationEngine(Fixtures.lRoute)
        engine.update(p0)
        val s = engine.update(Coordinate(10.7710, 106.70009))
        assertEquals(111.0, s.distanceToManeuver, 3.0)
        assertNotNull(s.snappedPosition)
    }

    @Test
    fun `after the turn the next maneuver is arrival`() {
        val engine = NavigationEngine(Fixtures.lRoute)
        engine.update(p0)
        engine.update(p2)
        val s = engine.update(p3)
        assertEquals(Maneuver.Arrive, s.maneuver)
        assertEquals(109.0, s.distanceToManeuver, 3.0)
    }

    @Test
    fun `off route needs consecutive fixes`() {
        val engine = NavigationEngine(Fixtures.lRoute)
        val far = Coordinate(10.7700, 106.7030)
        assertFalse(engine.update(far).isOffRoute)
        assertFalse(engine.update(far).isOffRoute)
        assertTrue(engine.update(far).isOffRoute)
        assertFalse(engine.update(p1).isOffRoute)
    }

    @Test
    fun arrival() {
        val engine = NavigationEngine(Fixtures.lRoute)
        engine.update(p3)
        val s = engine.update(p4)
        assertTrue(s.hasArrived)
        assertEquals(Maneuver.Arrive, s.maneuver)
        assertEquals(0.0, s.remainingDistance)
    }

    @Test
    fun `simulator drives the engine to arrival`() {
        val sim = RouteSimulator(Fixtures.lRoute)
        val engine = NavigationEngine(Fixtures.lRoute)
        var d = 0.0
        var sawTurn = false
        while (d <= sim.length) {
            val s = engine.update(sim.coordinate(d))
            assertFalse(s.isOffRoute)
            if (s.maneuver == Maneuver.Right) sawTurn = true
            d += 8
        }
        engine.update(sim.coordinate(sim.length))
        assertTrue(sawTurn)
        assertTrue(engine.hasArrived)
    }

    @Test
    fun `engine follows a language change`() {
        val route = Route(
            listOf(Coordinate(10.0, 106.0), Coordinate(10.001, 106.0), Coordinate(10.001, 106.001)),
            listOf(RouteStep(Maneuver.Depart, null, 0), RouteStep(Maneuver.Right, null, 1), RouteStep(Maneuver.Arrive, null, 2)),
            220.0, 60.0, "test",
        )
        val engine = NavigationEngine(route)
        val start = Coordinate(10.0, 106.0)
        assertEquals("Rẽ phải", engine.update(start).instruction)
        engine.language = Language.EN
        assertEquals("Turn right", engine.update(start).instruction)
    }
}

class FormattingAndThrottleTest {
    @Test
    fun `distance text`() {
        assertEquals("40 m", DistanceFormatter.text(43.0))
        assertEquals("350 m", DistanceFormatter.text(337.0))
        assertEquals("1,2 km", DistanceFormatter.text(1240.0))
        assertEquals("12 km", DistanceFormatter.text(12_340.0))
        assertEquals("1.2 km", DistanceFormatter.text(1240.0, Language.EN))
        assertEquals("350 m", DistanceFormatter.text(337.0, Language.EN))
    }

    @Test
    fun `instruction text`() {
        assertEquals("Rẽ trái vào Lê Lợi", InstructionFormatter.text(Maneuver.Left, "Lê Lợi"))
        assertEquals("Quay đầu", InstructionFormatter.text(Maneuver.UTurnLeft, null))
        assertEquals("Vào vòng xuyến, ra lối thứ 2", InstructionFormatter.text(Maneuver.Roundabout(2), null))
        assertEquals("Đến nơi", InstructionFormatter.text(Maneuver.Arrive, "X"))
        assertEquals("Đi thẳng", InstructionFormatter.text(Maneuver.Straight, "  "))
    }

    @Test
    fun `English instructions`() {
        val en = Language.EN
        assertEquals("Turn left onto Le Loi", InstructionFormatter.text(Maneuver.Left, "Le Loi", en))
        assertEquals("Make a U-turn", InstructionFormatter.text(Maneuver.UTurnRight, null, en))
        assertEquals("At the roundabout, take exit 3", InstructionFormatter.text(Maneuver.Roundabout(3), null, en))
        assertEquals("Start on A", InstructionFormatter.text(Maneuver.Depart, "A", en))
        assertEquals("Arrived", InstructionFormatter.text(Maneuver.Arrive, "X", en))
        assertEquals("Continue straight", InstructionFormatter.text(Maneuver.Straight, null, en))
    }

    @Test
    fun throttle() {
        val t = DashboardThrottle()
        assertTrue(t.shouldSend(Fixtures.state(Maneuver.Right, 300.0), 0.0), "first state always sent")
        assertFalse(t.shouldSend(Fixtures.state(Maneuver.Right, 290.0), 0.5), "same rounded distance (300)")
        assertFalse(t.shouldSend(Fixtures.state(Maneuver.Right, 240.0), 0.8), "distance changed but < minInterval")
        assertTrue(t.shouldSend(Fixtures.state(Maneuver.Right, 240.0), 1.2), "distance changed, interval passed")
        assertTrue(t.shouldSend(Fixtures.state(Maneuver.Left, 500.0), 1.3), "maneuver change is immediate")
        assertFalse(t.shouldSend(Fixtures.state(Maneuver.Left, 500.0), 5.0))
        assertTrue(t.shouldSend(Fixtures.state(Maneuver.Left, 500.0), 11.4), "heartbeat")
    }
}

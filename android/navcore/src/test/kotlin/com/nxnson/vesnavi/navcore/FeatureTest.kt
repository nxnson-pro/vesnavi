package com.nxnson.vesnavi.navcore

import com.nxnson.vesnavi.navcore.Fixtures.hex
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryTest {
    /** A data frame with the key on and the engine off; TFT at the time: 32 °C, ODO 31171 km. */
    private val captured = hex(
        "C0 80 76 34 01 01 00 00 00 00 00 16 05 11 39 C3 79 00 00 00 27 3E 4D 4A 00 00 00 00 00 00 00 01 00 00 01 " +
            "00 00 00 00 00 00 00 00 00 00 00 00 02 00 00 00 00 01 01 01 00 00 00 00 00 00 00 00",
    )

    private fun with(vararg changes: Pair<Int, Int>): Bytes =
        captured.toMutableList().also { f -> changes.forEach { (i, v) -> f[2 + i] = v } }

    @Test
    fun `layout matches the subscription`() {
        assertEquals(49, CoboTelemetry.signals.size)
        assertEquals(61, CoboTelemetry.frameDataLength)
        assertEquals(CoboTelemetry.frameDataLength, captured.size - 2)
    }

    @Test
    fun `decodes the captured frame`() {
        val t = assertNotNull(CoboTelemetry.parse(captured))
        assertEquals(31171, t.odometerKm)
        assertEquals(32, t.ambientC)
        assertEquals(11.8, t.batteryVolts ?: 0.0, 0.001)
        assertEquals(34, t.coolantC)
        assertEquals(37, t.intakeAirC)
        assertEquals(0, t.rpm)
        assertEquals(0, t.throttle)
        assertEquals(0.0, t.rearWheelKmh)
        assertEquals(17, t.clockHour)
        assertEquals(57, t.clockMinute)
        assertFalse(t.engineRunning)
    }

    @Test
    fun `rejects other frames`() {
        assertNull(CoboTelemetry.parse(hex("C0 44 51 00 02 00 97")))
        assertNull(CoboTelemetry.parse(captured.take(20)))
    }

    @Test
    fun `16-bit signals are big endian`() {
        assertEquals(1600, CoboTelemetry.parse(with(35 to 0x06, 36 to 0x40))?.rpm)
    }

    @Test
    fun `decodes a riding frame`() {
        val t = assertNotNull(CoboTelemetry.parse(with(21 to 85 + 40, 24 to 52, 25 to 0x12, 26 to 0x72, 35 to 0x11, 36 to 0xF9)))
        assertEquals(4601, t.rpm)
        assertEquals(52, t.dashboardSpeedKmh)
        assertEquals(47.22, t.rearWheelKmh ?: 0.0, 0.001)
        assertEquals(85, t.coolantC)
        assertTrue(t.engineRunning)
    }

    @Test
    fun `info text`() {
        val t = assertNotNull(CoboTelemetry.parse(captured))
        assertEquals("AQ 11.8V  32C", Cobo.infoText(t))
        val running = t.copy(rpm = 1600, coolantC = 85, batteryVolts = 13.8)
        assertEquals("NUOC 85C  AQ 13.8V", Cobo.infoText(running))
        assertTrue((Cobo.infoText(running)?.length ?: 99) <= 20)
        assertEquals(listOf(0x06, 0x04, 0x00), Cobo.infoDash("X", navigationScreen = true).takeLast(3))
        assertNotEquals(listOf(0x06, 0x04, 0x00), Cobo.infoDash("X", navigationScreen = false).takeLast(3))
    }
}

class TripTest {
    private val t0 = 1_800_000_000_000L

    private fun sample(rpm: Int, kmh: Double, fuel: Double, coolant: Int = 80, odo: Int = 1000) =
        ScooterTelemetry(rpm = rpm, rearWheelKmh = kmh, fuelLitersPerHour = fuel, coolantC = coolant, ambientC = 30, odometerKm = odo)

    @Test
    fun `a ride is integrated`() {
        val r = TripRecorder()
        for (i in 0..600) assertNull(r.ingest(sample(4000, 36.0, 1.2, odo = 1000 + i / 100), t0 + i * 1000L))
        val trip = assertNotNull(r.finish())
        assertEquals(6.0, trip.distanceKm, 0.01)
        assertEquals(0.2, trip.fuelLiters, 0.001)
        assertEquals(36.0, trip.avgSpeedKmh, 0.1)
        assertEquals(3.33, trip.litersPer100Km ?: 0.0, 0.01)
        assertEquals(4000, trip.maxRPM)
        assertEquals(80.0, trip.avgCoolantC ?: 0.0, 0.001)
        assertEquals(600.0, trip.duration, 0.001)
    }

    @Test
    fun `engine off ends the trip after the timeout`() {
        val r = TripRecorder()
        for (i in 0..300) r.ingest(sample(3000, 30.0, 1.0), t0 + i * 1000L)
        var ended: TripSummary? = null
        for (i in 1..200) {
            val trip = r.ingest(sample(0, 0.0, 0.0), t0 + (300 + i) * 1000L) ?: continue
            ended = trip
            assertEquals(180, i, "ends after the engine has been off for 3 minutes")
            break
        }
        assertEquals(t0 + 300_000, ended?.end, "parked minutes are not part of the trip")
        assertNull(r.current)
    }

    @Test
    fun `gaps are not integrated but the odometer covers them`() {
        val r = TripRecorder()
        r.ingest(sample(3000, 40.0, 1.0, odo = 1000), t0)
        r.ingest(sample(3000, 40.0, 1.0, odo = 1007), t0 + 600_000)
        val trip = assertNotNull(r.finish())
        assertEquals(7.0, trip.distanceKm)
        assertEquals(0.0, trip.fuelLiters)
    }

    @Test
    fun `short trips are discarded`() {
        val r = TripRecorder()
        for (i in 0..10) r.ingest(sample(1700, 0.0, 0.6), t0 + i * 1000L)
        assertNull(r.finish())
    }

    @Test
    fun `fuel estimate`() {
        val trip = TripSummary(start = t0 + 3_600_000, end = t0 + 7_200_000, distanceKm = 60.0, fuelLiters = 2.0, endOdometerKm = 1080)
        val s = FuelState(lastOdometer = 1000).fill(8.5, t0).add(trip) // 20 km ridden without the app
        val now = t0 + 86_400_000
        val e = assertNotNull(FuelEstimator.estimate(s, listOf(trip), now))
        assertEquals(2.0 / 60 * 100, e.litersPer100Km, 0.001)
        assertEquals(8.5 - 2.0 - 20 * e.litersPer100Km / 100, e.litersLeft, 0.001)
        assertEquals(e.litersLeft / e.litersPer100Km * 100, e.rangeKm, 0.001)
        assertNotNull(e.kmPerDay)
        assertTrue((e.refuelBy ?: now) > now)
    }

    @Test
    fun `no estimate before the first fill`() {
        assertNull(FuelEstimator.estimate(FuelState(), emptyList(), t0))
    }

    @Test
    fun `trips before the fill don't count`() {
        val old = TripSummary(start = t0 - 7_200_000, end = t0 - 3_600_000, fuelLiters = 1.0, distanceKm = 30.0)
        assertEquals(0.0, FuelState().fill(8.5, t0).add(old).litersMeasured)
    }

    @Test
    fun `estimate from the gauge before the first fill`() {
        val e = assertNotNull(FuelEstimator.estimate(FuelState(lastFuelBars = 5), emptyList(), t0))
        assertEquals(8.5 * 5 / 7, e.litersLeft, 0.001)
    }

    @Test
    fun `refuel detection`() {
        val d = RefuelDetector(stable = 2)
        repeat(5) { assertNull(d.update(5)) } // not stable yet
        var result: RefuelDetector.Reading? = null
        repeat(20) { d.update(5)?.let { r -> result = r } }
        assertEquals(RefuelDetector.Reading(5, rose = true), result)
        // Sloshing back and forth never settles → nothing.
        for (i in 0 until 40) assertNull(d.update(if (i % 2 == 0) 4 else 5))
        // A one-bar drop is consumption, not a fill-up.
        var drop: RefuelDetector.Reading? = null
        repeat(20) { d.update(4)?.let { r -> drop = r } }
        assertEquals(false, drop?.rose)
    }

    @Test
    fun `trips and fuel survive a save`() {
        val file = Persistence.TripsFile(
            listOf(TripSummary(start = t0, end = t0 + 60_000, distanceKm = 1.5, maxCoolantC = 92, startOdometerKm = 10)),
            FuelState(lastFuelBars = 4).fill(6.0, t0),
        )
        assertEquals(file, Persistence.decodeTrips(Persistence.encodeTrips(file)))
    }

    @Test
    fun `old fuel file without newer keys still loads`() {
        val s = Persistence.fuel(org.json.JSONObject("""{"tankLiters":8.5,"litersAtFill":8.5,"litersMeasured":0,"kmMeasured":0}"""))
        assertNull(s.lastFuelBars)
        assertEquals(7, s.barsMax)
    }
}

class FeatureTest {
    @Test
    fun `roundabout icon by exit direction`() {
        val expected = mapOf(0.0 to 104, 45.0 to 103, 90.0 to 102, 135.0 to 101, 175.0 to 100, -45.0 to 105, -90.0 to 106, -135.0 to 107, -179.0 to 100)
        expected.forEach { (deg, icon) -> assertEquals(icon, RouteAnnotator.roundaboutIcon(deg), "$deg°") }
    }

    private fun roundaboutRoute() = Route(
        polyline = listOf(
            Coordinate(10.0, 106.0),
            Coordinate(10.001, 106.0), // entry, arriving northbound
            Coordinate(10.0015, 106.0005),
            Coordinate(10.001, 106.001), // exit
            Coordinate(10.001, 106.002), // leaving eastbound
        ),
        steps = listOf(
            RouteStep(Maneuver.Depart, null, 0),
            RouteStep(Maneuver.Roundabout(2), "Lê Lợi", 1),
            RouteStep(Maneuver.Straight, "Lê Lợi", 3),
            RouteStep(Maneuver.Arrive, null, 4),
        ),
        distance = 500.0, duration = 60.0, provider = "test",
    )

    @Test
    fun `roundabout exit is annotated`() {
        val r = RouteAnnotator.annotateRoundabouts(roundaboutRoute())
        assertEquals(90.0, assertNotNull(r.steps[1].exitTurnDegrees), 2.0)
        assertNull(r.steps[2].exitTurnDegrees)
        val s = NavigationEngine(r).update(Coordinate(10.0003, 106.0))
        assertEquals(102, PMP3.icon(s), "right-hand exit → roundabout pictogram 102")
        assertEquals(102, Cobo.dashPayload(s)[2])
    }

    @Test
    fun `GraphHopper max speed details`() {
        val json = """
        {"paths":[{"distance":300,"time":60000,
          "points":{"coordinates":[[106.0,10.0],[106.0,10.001],[106.0,10.002],[106.0,10.003]]},
          "instructions":[{"sign":0,"interval":[0,1],"street_name":"A"},{"sign":4,"interval":[3,3],"street_name":""}],
          "details":{"max_speed":[[0,2,50.0],[2,3,null]]}}]}
        """
        val r = GraphHopperProvider.parse(json.toByteArray())
        assertEquals(listOf(SpeedLimitSpan(0, 2, 50)), r.speedLimits)
        val engine = NavigationEngine(r)
        assertEquals(50, engine.update(Coordinate(10.0005, 106.0)).speedLimit)
        assertNull(engine.update(Coordinate(10.0025, 106.0)).speedLimit)
    }

    @Test
    fun `dash carries the speed limit`() {
        val p = Cobo.dashPayload(Fixtures.state(Maneuver.Left, 100.0, street = "A", remaining = 500.0, duration = 60.0).copy(speedLimit = 50))
        assertEquals(listOf(0x02, 50, 0x00), p.subList(10, 13))
    }

    @Test
    fun `Overpass ways resolve to a limit`() {
        val json = """
        {"elements":[
          {"type":"way","id":1,"tags":{"highway":"residential","name":"Hẻm 12"}},
          {"type":"way","id":2,"tags":{"highway":"primary","name":"Đường Nguyễn Hữu Thọ","maxspeed":"60"}},
          {"type":"way","id":3,"tags":{"highway":"footway"}}
        ]}
        """
        val ways = SpeedLimitResolver.parseOverpass(json.toByteArray())
        assertEquals(3, ways.size)
        assertEquals(SpeedLimitResult(60, false, "Đường Nguyễn Hữu Thọ"), SpeedLimitResolver.resolve(ways, "Nguyễn Hữu Thọ", false))
        assertNull(SpeedLimitResolver.resolve(ways, "Hẻm 12", false), "untagged road without Vietnamese defaults")
        assertEquals(SpeedLimitResult(50, true, "Hẻm 12"), SpeedLimitResolver.resolve(ways, "Hẻm 12", true))
    }

    @Test
    fun `maxspeed parsing and Vietnamese defaults`() {
        assertEquals(50, SpeedLimitResolver.parseMaxspeed("50"))
        assertEquals(40, SpeedLimitResolver.parseMaxspeed("40 km/h"))
        assertEquals(48, SpeedLimitResolver.parseMaxspeed("30 mph"))
        assertEquals(50, SpeedLimitResolver.parseMaxspeed("VN:urban"))
        assertNull(SpeedLimitResolver.parseMaxspeed("signals"))
        assertEquals(60, SpeedLimitResolver.vietnamDefault(mapOf("highway" to "primary", "oneway" to "yes", "lanes" to "3")))
        assertEquals(60, SpeedLimitResolver.vietnamDefault(mapOf("highway" to "primary", "dual_carriageway" to "yes")))
        assertEquals(50, SpeedLimitResolver.vietnamDefault(mapOf("highway" to "secondary")))
        assertNull(SpeedLimitResolver.vietnamDefault(mapOf("highway" to "motorway")))
    }

    @Test
    fun `maintenance status`() {
        val now = 1_800_000_000_000L
        val day = 86_400_000L
        var oil = MaintenanceItem.defaults[0]
        fun level(odo: Int) = MaintenancePlanner.status(oil, odo, now, ZoneOffset.UTC).level
        assertEquals(MaintenanceStatus.Level.UNKNOWN, level(31_000))
        oil = oil.copy(lastKm = 29_000, lastDate = now - 30 * day)
        val ok = MaintenancePlanner.status(oil, 31_000, now, ZoneOffset.UTC)
        assertEquals(1_000, ok.kmLeft)
        assertEquals(MaintenanceStatus.Level.OK, ok.level)
        assertEquals(MaintenanceStatus.Level.SOON, level(31_800))
        val due = MaintenancePlanner.status(oil, 32_100, now, ZoneOffset.UTC)
        assertEquals(MaintenanceStatus.Level.DUE, due.level)
        assertEquals(-100, due.kmLeft)
        oil = oil.copy(lastDate = now - 200 * day) // > 6 months
        assertEquals(MaintenanceStatus.Level.DUE, level(29_500))
    }

    @Test
    fun `maintenance names in English`() {
        val oil = MaintenanceItem.defaults[0]
        assertEquals("Engine oil", oil.displayName(Language.EN))
        assertEquals("Nhớt máy", oil.displayName(Language.VI))
        assertEquals("Change engine oil", oil.popupText(Language.EN))
        val custom = MaintenanceItem("x", "Lốp", 1, null, "Lop")
        assertEquals("Lốp", custom.displayName(Language.EN), "custom items keep their own name")
    }

    @Test
    fun `maintenance file gains items added later`() {
        val saved = Persistence.encodeMaintenance(Persistence.MaintenanceFile(MaintenanceItem.defaults.take(2).map { it.copy(lastKm = 5) }, 1234))
        val loaded = Persistence.decodeMaintenance(saved)
        assertEquals(MaintenanceItem.defaults.size, loaded.items.size)
        assertEquals(5, loaded.items[0].lastKm)
        assertEquals(1234, loaded.odometer)
    }

    @Test
    fun `places keep recents unique and survive a save`() {
        val a = SavedPlace("A", latitude = 10.0, longitude = 106.0)
        val nearA = SavedPlace("A again", latitude = 10.0001, longitude = 106.0)
        var p = Places().addRecent(a).addRecent(nearA)
        assertEquals(listOf(nearA), p.recents)
        p = p.toggleFavorite(a).copy(parking = ParkingSpot(10.0, 106.0, 1_000, 6.0), home = a)
        assertTrue(p.isFavorite(nearA))
        assertEquals(p, Persistence.decodePlaces(Persistence.encodePlaces(p)))
        assertFalse(p.toggleFavorite(nearA).isFavorite(a))
    }
}

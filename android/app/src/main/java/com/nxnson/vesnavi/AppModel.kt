package com.nxnson.vesnavi

import android.content.Context
import android.location.Location
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nxnson.vesnavi.navcore.Coordinate
import com.nxnson.vesnavi.navcore.DashboardThrottle
import com.nxnson.vesnavi.navcore.GraphHopperProvider
import com.nxnson.vesnavi.navcore.NavigationEngine
import com.nxnson.vesnavi.navcore.NavigationState
import com.nxnson.vesnavi.navcore.OSRMProvider
import com.nxnson.vesnavi.navcore.PlaceSearch
import com.nxnson.vesnavi.navcore.Route
import com.nxnson.vesnavi.navcore.RouteSimulator
import com.nxnson.vesnavi.navcore.RoutingError
import com.nxnson.vesnavi.navcore.SavedPlace
import com.nxnson.vesnavi.navcore.ScooterTelemetry
import com.nxnson.vesnavi.navcore.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.abs

/** App-wide state and the navigation loop (port of the iOS AppModel). Main thread only. */
class AppModel(private val context: Context) {
    enum class Phase { IDLE, ROUTING, PREVIEW, NAVIGATING }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = Settings(context)
    val location = LocationService(context, scope)
    val ble = VespaBLE(context, settings, scope)
    val trips = TripStore(context)
    val places = PlacesStore(context)
    val maintenance = MaintenanceStore(context)
    val speedLimits = SpeedLimitService(settings, scope)

    var phase by mutableStateOf(Phase.IDLE)
        private set
    var destination by mutableStateOf<SavedPlace?>(null)
        private set
    var route by mutableStateOf<Route?>(null)
        private set
    var navState by mutableStateOf<NavigationState?>(null)
        private set
    var isSimulating by mutableStateOf(false)
        private set
    var simulatedPosition by mutableStateOf<Coordinate?>(null)
        private set
    /** Non-fatal info about the route, e.g. "GraphHopper failed → using OSRM". */
    var routeNote by mutableStateOf<String?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
    /** The activity keeps the screen on while this is true. */
    var keepScreenOn by mutableStateOf(false)
        private set

    private var engine: NavigationEngine? = null
    private val throttle = DashboardThrottle()
    private var simJob: Job? = null
    private var arrivalJob: Job? = null
    private var routeJob: Job? = null
    private var isRerouting = false
    private var lastRerouteAt = 0L
    private var isForeground = true
    private var parkingClearedThisRide = false
    private var maintenanceChecked = false

    init {
        Localizer.load(settings)
        ble.onTelemetry = ::scooterData
        ble.onDataLinkEnded = ::scooterLinkEnded
        ble.onStateChanged = ::updateService
        location.onUpdate = ::handle
        location.setMode(LocationService.Mode.BROWSING)
    }

    // Activity lifecycle

    /** Battery: with the app in the background and no route, stop GPS entirely. */
    fun foregroundChanged(foreground: Boolean) {
        isForeground = foreground
        ble.isForeground = foreground
        if (phase != Phase.NAVIGATING) location.setMode(if (foreground) LocationService.Mode.BROWSING else LocationService.Mode.OFF)
        if (!foreground) ble.stopScan()
        updateService()
    }

    fun permissionsChanged() {
        location.permissionChanged()
        ble.ensureStarted()
        updateService()
    }

    /** The foreground service runs while navigating or while the scooter link is up / expected. */
    private fun updateService() {
        when {
            phase == Phase.NAVIGATING -> ScooterService.start(context, L("Đang dẫn đường", "Navigating"))
            ble.wantsBackground -> ScooterService.start(context, if (ble.isReady) L("Đã kết nối xe", "Scooter connected") else L("Chờ xe kết nối", "Waiting for the scooter"))
            else -> ScooterService.stop(context)
        }
    }

    /** "Stop" in the notification: end the route and drop the scooter link. */
    fun stopFromNotification() {
        stopNavigation()
        ble.disconnect()
        ScooterService.stop(context)
    }

    // Search & routing

    suspend fun search(query: String): List<SearchResult> {
        val here = location.lastLocation?.let { Coordinate(it.latitude, it.longitude) }
        return PlaceSearch.parsePhoton(Network.get(PlaceSearch.photonURL(query, here)))
    }

    fun planRoute(to: SavedPlace) {
        val here = location.lastLocation
        if (here == null) {
            errorMessage = L("Chưa có vị trí GPS. Hãy bật quyền vị trí cho app và thử lại.", "No GPS position yet. Allow location access for the app and try again.")
            return
        }
        destination = to
        phase = Phase.ROUTING
        routeJob?.cancel()
        routeJob = scope.launch {
            try {
                val (r, note) = fetchRoute(Coordinate(here.latitude, here.longitude), to.coordinate)
                route = r
                routeNote = note
                phase = Phase.PREVIEW
                places.addRecent(to)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                destination = null
                phase = Phase.IDLE
                errorMessage = message(e)
            }
        }
    }

    /** GraphHopper if an API key is set (falls back to OSRM on error), otherwise OSRM. */
    private suspend fun fetchRoute(from: Coordinate, to: Coordinate): Pair<Route, String?> {
        val osrm = OSRMProvider(fetch = Network.fetcher)
        val key = settings.graphHopperKey.trim()
        if (key.isEmpty()) return osrm.route(from, to) to null
        val profile = settings.graphHopperProfile
        fun graphHopper(p: String) = GraphHopperProvider(key, p, Network.fetcher)
        return try {
            graphHopper(profile).route(from, to) to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: RoutingError.Server) {
            if (profile != "car" && "profile" in e.msg) {
                // Free GraphHopper plans only offer car/bike/foot: switch to car for good and retry.
                settings.graphHopperProfile = "car"
                runCatching { graphHopper("car").route(from, to) }.getOrNull()?.let {
                    return it to L("Gói GraphHopper miễn phí không có kiểu Xe tay ga → đã chuyển sang Ô tô", "The free GraphHopper plan has no Scooter profile → switched to Car")
                }
            }
            osrm.route(from, to) to L("GraphHopper lỗi (${e.message(appLanguage)}) → đang dùng OSRM", "GraphHopper failed (${e.message(appLanguage)}) → using OSRM")
        } catch (e: Exception) {
            osrm.route(from, to) to L("GraphHopper lỗi (${message(e)}) → đang dùng OSRM", "GraphHopper failed (${message(e)}) → using OSRM")
        }
    }

    private fun message(e: Throwable): String = when (e) {
        is RoutingError -> e.message(appLanguage)
        is java.io.IOException -> L("Không có mạng hoặc máy chủ không trả lời (${e.message})", "No network or the server didn't answer (${e.message})")
        else -> e.message ?: e.toString()
    }

    // Scooter data

    private fun scooterData(t: ScooterTelemetry) {
        trips.ingest(t)
        t.odometerKm?.let(maintenance::updateOdometer)
        if (t.engineRunning && !parkingClearedThisRide) {
            // Riding away: the old parking spot is no longer where the scooter is.
            parkingClearedThisRide = true
            if (places.places.parking != null) places.clearParking()
        }
        remindMaintenanceIfDue()
    }

    private fun scooterLinkEnded() {
        trips.linkEnded()
        parkingClearedThisRide = false
        maintenanceChecked = false
        rememberParkingSpot()
    }

    /** The scooter was switched off (or left behind): note where the phone is. */
    private fun rememberParkingSpot() {
        if (!settings.rememberParking) return
        val endedAt = System.currentTimeMillis()
        location.requestPreciseFix { fix -> saveParking(fix, endedAt) }
    }

    /** Re-measure the parking spot where the rider stands now (next to the scooter). */
    fun setParkingHere() {
        if (!location.isAuthorized) {
            errorMessage = L("Cần quyền vị trí cho VesNavi (Cài đặt → Ứng dụng → VesNavi → Quyền).", "VesNavi needs location access (Settings → Apps → VesNavi → Permissions).")
            return
        }
        location.requestPreciseFix(timeoutMs = 15_000) { fix ->
            if (fix != null) {
                places.setParking(fix.latitude, fix.longitude, accuracy = fix.accuracy.toDouble())
            } else {
                errorMessage = L("Không lấy được vị trí GPS, thử lại ở chỗ thoáng hơn.", "Couldn't get a GPS fix, try again somewhere more open.")
            }
        }
    }

    /**
     * The precise fix, or a fix the running GPS took around the moment the scooter was switched off
     * if that one is more accurate. With neither, nothing is saved rather than a spot from earlier in the ride.
     */
    private fun saveParking(fix: Location?, linkEndedAt: Long) {
        val window = 10_000L
        val aroundStop = location.lastLocation?.takeIf { abs(it.time - linkEndedAt) <= window }
        val best = listOfNotNull(fix, aroundStop)
            .filter { it.hasAccuracy() && it.time >= linkEndedAt - window }
            .minByOrNull { it.accuracy } ?: return
        places.setParking(best.latitude, best.longitude, best.time, best.accuracy.toDouble())
    }

    /** Once a day, when the scooter is connected: pop up the most urgent overdue service on the TFT. */
    private fun remindMaintenanceIfDue() {
        if (maintenanceChecked || !ble.isReady || maintenance.odometer == null) return
        maintenanceChecked = true
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        if (settings.maintenancePopupDay == today) return
        val item = maintenance.dueItems.firstOrNull() ?: return
        settings.maintenancePopupDay = today
        ble.showPopup(L("Bao duong", "Service due"), item.popupText(appLanguage))
    }

    // Navigation

    fun startNavigation(simulate: Boolean) {
        val r = route ?: return
        engine = NavigationEngine(r)
        throttle.reset()
        speedLimits.reset()
        isSimulating = simulate
        phase = Phase.NAVIGATING
        // Off by default: the arrows are on the scooter TFT, the phone can stay in a pocket.
        keepScreenOn = settings.keepScreenOn
        location.setMode(LocationService.Mode.NAVIGATING)
        updateService()
        if (simulate) startSimulation(r) else location.lastLocation?.let(::handle)
    }

    /** Also used to cancel the route preview. */
    fun stopNavigation() {
        routeJob?.cancel()
        simJob?.cancel()
        simJob = null
        arrivalJob?.cancel()
        arrivalJob = null
        engine = null
        isSimulating = false
        simulatedPosition = null
        navState = null
        route = null
        destination = null
        routeNote = null
        ble.clear()
        location.setMode(if (isForeground) LocationService.Mode.BROWSING else LocationService.Mode.OFF)
        keepScreenOn = false
        phase = Phase.IDLE
        updateService()
    }

    private fun handle(loc: Location) {
        if (phase != Phase.NAVIGATING || isSimulating) return
        if (!loc.hasAccuracy() || loc.accuracy > 100) return
        process(Coordinate(loc.latitude, loc.longitude), loc.accuracy.toDouble())
    }

    private fun process(coord: Coordinate, accuracy: Double) {
        val e = engine ?: return
        e.language = appLanguage
        var state = e.update(coord, accuracy)
        val limit = speedLimits.limit(state, state.snappedPosition ?: coord)
        state = state.copy(speedLimit = limit?.kmh, speedLimitEstimated = limit?.estimated ?: false)
        navState = state
        if (state.hasArrived) finishAfterArrival()
        if (throttle.shouldSend(state, System.currentTimeMillis() / 1000.0)) ble.send(state)
        if (state.isOffRoute) reroute(coord)
    }

    /** Arrived: show "Đến nơi" for a while, then end guidance on its own (GPS and the TFT screen cost battery). */
    private fun finishAfterArrival() {
        if (arrivalJob != null) return
        arrivalJob = scope.launch {
            delay(30_000)
            if (phase == Phase.NAVIGATING) stopNavigation()
        }
    }

    private fun reroute(from: Coordinate) {
        val target = destination?.coordinate ?: return
        if (isSimulating || isRerouting || System.currentTimeMillis() - lastRerouteAt < 15_000) return
        isRerouting = true
        lastRerouteAt = System.currentTimeMillis()
        scope.launch {
            try {
                val (r, note) = fetchRoute(from, target)
                if (phase != Phase.NAVIGATING) return@launch
                route = r
                routeNote = note
                engine = NavigationEngine(r)
                throttle.reset()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                routeNote = L("Tìm lại đường thất bại: ${message(e)}", "Rerouting failed: ${message(e)}")
            } finally {
                isRerouting = false
            }
        }
    }

    // Simulation (test without riding)

    private fun startSimulation(r: Route) {
        val sim = RouteSimulator(r)
        val metersPerTick = settings.simulationSpeedKmh.coerceAtLeast(5) / 3.6 // 1 tick = 1 s
        simJob = scope.launch {
            var distance = 0.0
            while (isActive) {
                val p = sim.coordinate(distance)
                simulatedPosition = p
                process(p, 5.0)
                if (distance >= sim.length) return@launch
                distance += metersPerTick
                delay(1_000)
            }
        }
    }
}

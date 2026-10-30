package com.nxnson.vesnavi.navcore

object Fixtures {
    /** L-shaped route near HCMC: ~222 m north, then right turn, ~220 m east. */
    val p0 = Coordinate(10.7700, 106.7000)
    val p1 = Coordinate(10.7710, 106.7000)
    val p2 = Coordinate(10.7720, 106.7000)
    val p3 = Coordinate(10.7720, 106.7010)
    val p4 = Coordinate(10.7720, 106.7020)

    val lRoute = Route(
        polyline = listOf(p0, p1, p2, p3, p4),
        steps = listOf(
            RouteStep(Maneuver.Depart, "Đường A", 0),
            RouteStep(Maneuver.Right, "Đường B", 2),
            RouteStep(Maneuver.Arrive, null, 4),
        ),
        distance = 440.0,
        duration = 60.0,
        provider = "test",
    )

    const val GRAPH_HOPPER_JSON = """
    {
      "paths": [{
        "distance": 440.5,
        "time": 60000,
        "points": {"type": "LineString", "coordinates": [
          [106.7000, 10.7700], [106.7000, 10.7710], [106.7000, 10.7720],
          [106.7010, 10.7720], [106.7020, 10.7720]
        ]},
        "instructions": [
          {"distance": 222, "sign": 0, "interval": [0, 2], "text": "Tiếp tục", "time": 30000, "street_name": "Đường A"},
          {"distance": 218, "sign": 2, "interval": [2, 4], "text": "Rẽ phải", "time": 30000, "street_name": "Đường B"},
          {"distance": 0, "sign": 4, "interval": [4, 4], "text": "Đến nơi", "time": 0, "street_name": ""}
        ]
      }]
    }
    """

    const val GRAPH_HOPPER_ERROR = """{"message": "Cannot find point 0: 0.0,0.0", "hints": []}"""

    const val OSRM_JSON = """
    {
      "code": "Ok",
      "routes": [{
        "distance": 440.5,
        "duration": 60,
        "legs": [{"steps": [
          {"name": "Đường A", "geometry": {"coordinates": [[106.7000, 10.7700], [106.7000, 10.7710], [106.7000, 10.7720]]},
           "maneuver": {"type": "depart", "location": [106.7000, 10.7700]}},
          {"name": "Đường B", "geometry": {"coordinates": [[106.7000, 10.7720], [106.7010, 10.7720], [106.7020, 10.7720]]},
           "maneuver": {"type": "turn", "modifier": "right", "location": [106.7000, 10.7720]}},
          {"name": "", "geometry": {"coordinates": [[106.7020, 10.7720], [106.7020, 10.7720]]},
           "maneuver": {"type": "arrive", "location": [106.7020, 10.7720]}}
        ]}]
      }]
    }
    """

    fun hex(s: String): Bytes = Hex.decode(s) ?: emptyList()

    fun ascii(s: String): Bytes = s.toByteArray(Charsets.US_ASCII).toBytes()

    fun state(
        maneuver: Maneuver,
        distance: Double,
        street: String? = null,
        remaining: Double = 1000.0,
        duration: Double = 100.0,
    ) = NavigationState(maneuver, street, "", distance, remaining, duration, isOffRoute = false, hasArrived = false, snappedPosition = null)
}

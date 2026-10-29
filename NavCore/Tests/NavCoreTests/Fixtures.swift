import Foundation
@testable import NavCore

enum Fixtures {
    /// L-shaped route near HCMC: ~222 m north, then right turn, ~220 m east.
    ///   p0 → p1 → p2 (turn right) → p3 → p4 (arrive)
    static let p0 = Coordinate(latitude: 10.7700, longitude: 106.7000)
    static let p1 = Coordinate(latitude: 10.7710, longitude: 106.7000)
    static let p2 = Coordinate(latitude: 10.7720, longitude: 106.7000)
    static let p3 = Coordinate(latitude: 10.7720, longitude: 106.7010)
    static let p4 = Coordinate(latitude: 10.7720, longitude: 106.7020)

    static let lRoute = Route(
        polyline: [p0, p1, p2, p3, p4],
        steps: [
            RouteStep(maneuver: .depart, streetName: "Đường A", pointIndex: 0),
            RouteStep(maneuver: .right, streetName: "Đường B", pointIndex: 2),
            RouteStep(maneuver: .arrive, streetName: nil, pointIndex: 4),
        ],
        distance: 440, duration: 60, provider: "test"
    )

    static let graphHopperJSON = """
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

    static let graphHopperError = """
    {"message": "Cannot find point 0: 0.0,0.0", "hints": []}
    """

    static let osrmJSON = """
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
}

// swift-tools-version:5.9
import PackageDescription

// Pure-Swift navigation core (no UIKit/MapKit) so it can be unit-tested on Linux CI.
let package = Package(
    name: "NavCore",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [
        .library(name: "NavCore", targets: ["NavCore"]),
    ],
    targets: [
        .target(name: "NavCore"),
        .testTarget(name: "NavCoreTests", dependencies: ["NavCore"]),
    ]
)

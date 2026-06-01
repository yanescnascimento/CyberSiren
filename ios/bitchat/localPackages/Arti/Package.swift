import PackageDescription

let package = Package(
    name: "Tor",
    platforms: [
        .iOS(.v16),
        .macOS(.v13),
    ],
    products: [
        .library(
            name: "Tor",
            targets: ["Tor"]
        ),
    ],
    dependencies: [
        .package(path: "../BitLogger"),
    ],
    targets: [

        .target(
            name: "Tor",
            dependencies: [
                "arti",
                .product(name: "BitLogger", package: "BitLogger"),
            ],
            path: "Sources",
            exclude: ["C"],
            sources: [
                "TorManager.swift",
                "TorURLSession.swift",
                "TorNotifications.swift",
            ],
            linkerSettings: [
                .linkedLibrary("resolv"),
                .linkedLibrary("z"),
                .linkedLibrary("sqlite3"),
            ]
        ),

        .binaryTarget(
            name: "arti",
            path: "Frameworks/arti.xcframework"
        ),
    ]
)

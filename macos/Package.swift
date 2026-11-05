// swift-tools-version:5.10
import PackageDescription

// Pods Companion для Mac: значок в строке меню, связь с приложением на телефоне и переключение
// AirPods между Android и Mac. PodsCore — чистая логика (проверяется тестами), PodsCompanionMac — само приложение.
let package = Package(
    name: "PodsCompanionMac",
    platforms: [.macOS(.v13)],
    targets: [
        .target(name: "PodsCore", path: "Sources/PodsCore"),
        .executableTarget(
            name: "PodsCompanionMac",
            dependencies: ["PodsCore"],
            path: "Sources/PodsCompanionMac",
            linkerSettings: [
                .linkedFramework("IOBluetooth"),
                .linkedFramework("CoreBluetooth"),
                .linkedFramework("CoreAudio"),
                .linkedFramework("ServiceManagement"),
            ]
        ),
        .testTarget(name: "PodsCoreTests", dependencies: ["PodsCore"], path: "Tests/PodsCoreTests"),
    ]
)

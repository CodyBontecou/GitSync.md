// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "Clibgit2",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "Clibgit2", targets: ["Clibgit2", "Clibgit2Sys", "libgit2"]),
    ],
    targets: [
        // Thin Swift shim that re-exports the binary target's headers.
        .target(
            name: "Clibgit2",
            dependencies: ["libgit2"],
            publicHeadersPath: "include"
        ),
        // Narrow C wrappers for system APIs omitted from the binary's umbrella.
        .target(
            name: "Clibgit2Sys",
            dependencies: ["libgit2"],
            publicHeadersPath: "include",
            // Compile the real sys headers textually; the prebuilt Clang module
            // exports the public API but hides these system declarations.
            cSettings: [.unsafeFlags(["-fno-modules"])]
        ),
        // Pre-built libgit2 xcframework (iOS arm64 + simulator)
        .binaryTarget(
            name: "libgit2",
            path: "../../libgit2.xcframework"
        ),
    ]
)

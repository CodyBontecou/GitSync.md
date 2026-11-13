import UIKit

// MARK: - Device wording
//
// Copy is written for iPhone ("changes on this iPhone", "On My iPhone").
// On iPad the same sentences name the iPad instead. "iPhone" / "iPad" are
// brand names that translations keep verbatim, so swapping the word works in
// every language.

enum DeviceWording {
    /// "iPad" on iPad (including Mac running the iPad app), else "iPhone".
    static let deviceName: String = UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"
}

extension String {
    /// This string with "iPhone" replaced by the current device's name.
    var forThisDevice: String {
        DeviceWording.deviceName == "iPhone" ? self : replacingOccurrences(of: "iPhone", with: DeviceWording.deviceName)
    }
}

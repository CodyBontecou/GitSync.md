import SwiftUI
import Notelet

enum AppReleaseNotes {
    static var all: [NoteletVersionNotes] {
        [
            .init(
                version: "2.4.1",
                items: version241Items
            )
        ]
    }

    static let configuration = NoteletConfiguration(
        nextButtonLabel: "Next",
        doneButtonLabel: "Done",
        accentColor: .brutalAccent
    )

    /// Prevent release notes from appearing for brand-new installs after onboarding.
    ///
    /// Existing users upgrading from a build before Notelet existed won't have
    /// Notelet's seen-version key yet, but they will have app data such as
    /// onboarding completion or repos. In that case we intentionally leave the
    /// key unset so the sheet can appear once they reach the home page.
    static func bootstrapFreshInstallIfNeeded(hasExistingAppData: Bool) {
        guard !hasExistingAppData,
              NoteletStorage.getLatestSeenAppVersion() == nil else {
            return
        }

        NoteletStorage.markCurrentVersionAsSeen()
    }

    /// Returns a Notelet version only when release notes should be shown from
    /// the home screen: an existing install, a bundle version with notes, and a
    /// current app version the user hasn't already seen.
    static func presentedVersionForHomePage(hasExistingAppData: Bool) -> NoteletPresentedVersion? {
        #if DEBUG
        if MarketingCapture.isActive {
            return nil
        }
        #endif

        guard hasExistingAppData,
              let currentVersion,
              hasNotes(for: currentVersion),
              NoteletStorage.getLatestSeenAppVersion() != currentVersion else {
            return nil
        }

        return .current
    }

    static func markCurrentVersionAsSeen() {
        NoteletStorage.markCurrentVersionAsSeen()
    }

    private static let availableVersions: Set<String> = ["2.4.1"]

    private static var currentVersion: String? {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
    }

    private static func hasNotes(for version: String) -> Bool {
        availableVersions.contains(version)
    }

    private static var version241Items: [NoteletVersionNoteItem] {
        var items: [NoteletVersionNoteItem] = []

        if let videoURL = releaseNotesVideoURL {
            items.append(
                .media(
                    kind: .video,
                    url: videoURL,
                    title: "Delete previously cloned repos",
                    description: "Watch how to remove local repository copies you no longer need, right from Sync.md."
                )
            )
        }

        items.append(
            .list(
                title: "Repo cleanup is here",
                rows: [
                    .init(
                        symbolSystemName: "trash.fill",
                        title: "Delete previous clones",
                        description: "Remove old on-device repository copies directly from the app."
                    ),
                    .init(
                        symbolSystemName: "internaldrive.fill",
                        title: "Free up device storage",
                        description: "Clean up local files without affecting the repository on GitHub."
                    )
                ]
            )
        )

        return items
    }

    private static var releaseNotesVideoURL: URL? {
        Bundle.main.url(
            forResource: "previously-cloned-delete-mobile-optimized",
            withExtension: "mp4",
            subdirectory: "Resources"
        ) ?? Bundle.main.url(
            forResource: "previously-cloned-delete-mobile-optimized",
            withExtension: "mp4"
        )
    }
}

import SwiftUI
import Notelet

enum AppReleaseNotes {
    static var all: [NoteletVersionNotes] {
        [
            .init(
                version: "2.5.1",
                items: version25Items
            ),
            .init(
                version: "2.4.7",
                items: version247Items
            ),
            .init(
                version: "2.4.5",
                items: version245Items
            ),
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
        if MarketingCapture.usesSeededData {
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

    private static let availableVersions: Set<String> = ["2.5.1", "2.4.7", "2.4.5", "2.4.1"]

    private static var currentVersion: String? {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
    }

    private static func hasNotes(for version: String) -> Bool {
        availableVersions.contains(version)
    }

    private static var version25Items: [NoteletVersionNoteItem] {
        [
            .list(
                title: "Self-hosted SSH is ready",
                rows: [
                    .init(
                        symbolSystemName: "server.rack",
                        title: "Clone SSH-only repositories",
                        description: "GitSync.md now supports self-hosted Forgejo and Git servers that only expose SSH remotes."
                    ),
                    .init(
                        symbolSystemName: "key.horizontal.fill",
                        title: "Modern SSH keys work",
                        description: "Ed25519, ECDSA, and RSA private keys are supported for clone, pull, and push."
                    ),
                    .init(
                        symbolSystemName: "shield.lefthalf.filled.badge.checkmark",
                        title: "Trust hosts safely",
                        description: "Unknown SSH hosts show a fingerprint prompt, and changed host keys are blocked until you review them."
                    )
                ]
            ),
            .list(
                title: "GitHub accounts are safer",
                rows: [
                    .init(
                        symbolSystemName: "person.2.badge.key.fill",
                        title: "Use personal and work accounts",
                        description: "GitSync.md can now remember multiple GitHub sign-ins and keep each account’s token separate."
                    ),
                    .init(
                        symbolSystemName: "folder.badge.person.crop",
                        title: "Repos follow the right account",
                        description: "Repositories are linked to the GitHub account that added them, so switching accounts shows the matching repo list and uses the right credentials."
                    ),
                    .init(
                        symbolSystemName: "safari.fill",
                        title: "Choose the account you want",
                        description: "GitHub sign-in now uses a private browser session so logging out and back in does not silently reuse the wrong browser account."
                    )
                ]
            ),
            .list(
                title: "Safer repository removal",
                rows: [
                    .init(
                        symbolSystemName: "folder.badge.minus",
                        title: "Remove without deleting files",
                        description: "Removing a repository from GitSync.md now keeps the files on your device, so folders shared with other apps stay safe."
                    ),
                    .init(
                        symbolSystemName: "trash.fill",
                        title: "Delete only when you choose",
                        description: "For repositories managed by GitSync.md, Settings now offers a separate Delete Local Files action with a clear confirmation path."
                    ),
                    .init(
                        symbolSystemName: "link.badge.plus",
                        title: "External folders are protected",
                        description: "Repositories opened from existing Files folders are unlinked from GitSync.md without removing the original folder."
                    )
                ]
            )
        ]
    }

    private static var version247Items: [NoteletVersionNoteItem] {
        [
            .list(
                title: "Pull with rebase",
                rows: [
                    .init(
                        symbolSystemName: "arrow.triangle.2.circlepath.circle.fill",
                        title: "Rebase diverged branches",
                        description: "When your local commits and remote changes both move forward, GitSync.md can now replay your local commits on top of the latest remote branch."
                    ),
                    .init(
                        symbolSystemName: "exclamationmark.triangle.fill",
                        title: "Resolve rebase conflicts in-app",
                        description: "If a rebase hits conflicts, use the Conflict Center to choose a side or edit the result, then continue or abort the rebase."
                    ),
                    .init(
                        symbolSystemName: "arrow.up.circle.fill",
                        title: "Push rebased commits",
                        description: "After a successful rebase, Push Current Branch sends your rewritten local commits to the remote without needing a new commit."
                    )
                ]
            )
        ]
    }

    private static var version245Items: [NoteletVersionNoteItem] {
        [
            .list(
                title: "Shortcuts support",
                rows: [
                    .init(
                        symbolSystemName: "arrow.down.circle.fill",
                        title: "Pull from Apple Shortcuts",
                        description: "Run Pull All Repositories or Pull Repository from the Shortcuts app to fetch and fast-forward your vaults without opening the Git controls."
                    ),
                    .init(
                        symbolSystemName: "bolt.fill",
                        title: "Auto-pull on app open",
                        description: "Create a Personal Automation for when GitSync.md opens, then run Pull All Repositories to keep your notes fresh automatically."
                    )
                ]
            ),
            .list(
                title: "Commit setup is clearer",
                rows: [
                    .init(
                        symbolSystemName: "person.crop.circle.badge.checkmark",
                        title: "Author details are checked first",
                        description: "GitSync.md now catches missing Author Name or Author Email before Commit & Push tries to create a commit."
                    ),
                    .init(
                        symbolSystemName: "exclamationmark.bubble.fill",
                        title: "No more cryptic signature errors",
                        description: "If your repository needs Git author identity, you'll see exactly what to set in repository settings."
                    )
                ]
            )
        ]
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

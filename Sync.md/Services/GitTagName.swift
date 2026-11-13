import Foundation
import Clibgit2
import libgit2

/// Validate Swift input before libgit2 converts it to a NUL-terminated string.
enum GitTagName {
    private static let initialized: Int32 = git_libgit2_init()

    static func validationMessage(_ raw: String) -> String? {
        _ = initialized
        let name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        var valid: Int32 = 0
        guard !raw.utf8.contains(where: { $0 < 32 || $0 == 127 }),
              !name.isEmpty,
              git_reference_name_is_valid(&valid, "refs/tags/" + name) == 0,
              valid == 1 else {
            return String(localized: "Please enter a valid Git tag name.")
        }
        return nil
    }
}

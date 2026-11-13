import Foundation
import Clibgit2
import libgit2

enum GitBranchName {
    private static let initialized: Int32 = git_libgit2_init()

    static func validationMessage(_ name: String) -> String? {
        _ = initialized
        var valid: Int32 = 0
        // Validate a full branch ref, plus git-branch's restrictions on its
        // shorthand. Reject ASCII controls (including NUL and DEL) before
        // crossing the C string boundary; some libgit2 versions accept DEL.
        guard !name.isEmpty, !name.hasPrefix("-"), name != "HEAD",
              !name.utf8.contains(where: { $0 < 32 || $0 == 127 }),
              git_reference_name_is_valid(&valid, "refs/heads/" + name) == 0,
              valid == 1 else {
            return String(localized: "Please enter a valid Git branch name.")
        }
        return nil
    }
}

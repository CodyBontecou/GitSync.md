import Foundation

extension AppState {
    /// Maps a typed push outcome (Shortcuts, callbacks, offline replay) onto
    /// the sync states: rejection, sign-in expiry, offline queue, toasts.
    func classifyPushResult(repoID: UUID, result: RepositoryPushResult) {
        guard let repo = repo(id: repoID) else { return }
        switch result {
        case .pushed(let sha):
            didFinishPush(repoID: repoID, commitSHA: sha, commits: syncActivity?.commitsToSend ?? 1)
        case .noChanges:
            didSyncSuccessfully(repoID: repoID)
        case .blocked(let message):
            let divergedMessages = [
                String(localized: "Local and remote history diverged."),
                String(localized: "Remote updates must be pulled before pushing."),
            ]
            if divergedMessages.contains(message) {
                let branch = repo.gitState.branch.isEmpty ? repo.branch : repo.gitState.branch
                recordPushRejection(repoID: repoID, message: "Push failed: refs/heads/\(branch): fetch first")
            }
        case .commitSavedNotPushed(_, let message, nil),
             .authenticationOrTrustRequired(let message, nil),
             .failed(let message):
            if Self.isPushRejection(message) {
                recordPushRejection(repoID: repoID, message: message)
            } else if isGitHubTokenVault(repo), Self.isAuthenticationFailure(message) {
                markAuthExpired(repoIDs: [repoID])
            } else if Self.isNetworkFailure(message), isOffline {
                enqueueOffline(repoID: repoID, operation: .push)
            }
        default:
            break
        }
    }
}

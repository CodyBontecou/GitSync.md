import Foundation
import CryptoKit

@main
struct NativeHandoffFixture {
    static func main() throws {
        let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
        let suite = "gitsync-handoff-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        var clock = Date()
        let service = ObsidianCredentialHandoff(defaults: defaults, now: { clock })
        let id = UUID()
        let remote = "https://github.com/fixture/vault.git"
        let configURL = root.appendingPathComponent(".git/config")
        try FileManager.default.createDirectory(at: root.appendingPathComponent(".git"), withIntermediateDirectories: true)
        try "[remote \"origin\"]\n\turl = \(remote)\n".write(to: configURL, atomically: true, encoding: .utf8)
        precondition(ObsidianCredentialHandoff.isGitHubHTTPS(remote))
        for invalid in ["http://github.com/a/b.git", "https://github.com.evil/a/b", "https://user:secret@github.com/a/b", "https://github.com/a/b?token=x", "ssh://git@github.com/a/b"] {
            precondition(!ObsidianCredentialHandoff.isGitHubHTTPS(invalid))
        }
        precondition(ObsidianCredentialHandoff.originURL(in: "[remote \"origin\"]\nurl = \(remote)\npushurl = https://evil.test/a/b\n") == nil)
        precondition(ObsidianCredentialHandoff.originURL(in: "[include]\npath = other\n[remote \"origin\"]\nurl = \(remote)") == nil)
        try service.prepare(vaultURL: root, repoID: id, remoteURL: remote)
        let requestURL = root.appendingPathComponent(ObsidianCredentialHandoff.directory + "/request.json")
        var request = try JSONDecoder().decode(ObsidianCredentialHandoff.Request.self, from: Data(contentsOf: requestURL))
        func rejects(_ action: () throws -> Void) {
            do { try action(); preconditionFailure("Expected rejection") } catch {}
        }
        rejects { try service.respond(vaultURL: root, repoID: id, requestID: UUID().uuidString, remoteURL: remote, username: "x-access-token", token: "fixture-token", authorName: "Fixture Author", authorEmail: "fixture@example.invalid") }
        clock = clock.addingTimeInterval(8 * 86400)
        rejects { try service.respond(vaultURL: root, repoID: id, requestID: request.requestID, remoteURL: remote, username: "x-access-token", token: "fixture-token", authorName: "Fixture Author", authorEmail: "fixture@example.invalid") }
        clock = Date()
        let oldRequestID = request.requestID
        request = try service.prepare(vaultURL: root, repoID: id, remoteURL: remote)
        precondition(request.requestID != oldRequestID)
        rejects { try service.respond(vaultURL: root, repoID: id, requestID: oldRequestID, remoteURL: remote, username: "x-access-token", token: "fixture-token", authorName: "Fixture Author", authorEmail: "fixture@example.invalid") }
        // Emit only public data. JS supplies the recipient key before we respond.
        print(String(decoding: try Data(contentsOf: requestURL), as: UTF8.self))
        fflush(stdout)
        guard readLine() == "respond" else { throw CocoaError(.fileReadUnknown) }
        try "[remote \"origin\"]\nurl = https://github.com/other/vault.git\n".write(to: configURL, atomically: true, encoding: .utf8)
        rejects { try service.respond(vaultURL: root, repoID: id, requestID: request.requestID, remoteURL: remote, username: "x-access-token", token: "fixture-token", authorName: "Fixture Author", authorEmail: "fixture@example.invalid") }
        try "[remote \"origin\"]\nurl = \(remote)\n".write(to: configURL, atomically: true, encoding: .utf8)
        let recipientURL = root.appendingPathComponent(ObsidianCredentialHandoff.directory + "/recipient.json")
        let originalRecipient = try Data(contentsOf: recipientURL)
        try FileManager.default.removeItem(at: recipientURL)
        try FileManager.default.createSymbolicLink(at: recipientURL, withDestinationURL: configURL)
        rejects { try service.respond(vaultURL: root, repoID: id, requestID: request.requestID, remoteURL: remote, username: "x-access-token", token: "fixture-token", authorName: "Fixture Author", authorEmail: "fixture@example.invalid") }
        try FileManager.default.removeItem(at: recipientURL)
        try originalRecipient.write(to: recipientURL)
        try service.respond(vaultURL: root, repoID: id, requestID: request.requestID, remoteURL: remote, username: "x-access-token", token: "fixture-token", authorName: "Fixture Author", authorEmail: "fixture@example.invalid")
        rejects { try service.respond(vaultURL: root, repoID: id, requestID: request.requestID, remoteURL: remote, username: "x-access-token", token: "fixture-token", authorName: "Fixture Author", authorEmail: "fixture@example.invalid") }
        let response = try Data(contentsOf: root.appendingPathComponent(ObsidianCredentialHandoff.directory + "/response.json"))
        precondition(!String(decoding: response, as: UTF8.self).contains("fixture-token"))
    }
}

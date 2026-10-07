import Foundation
import XCTest
@testable import Sync_md

final class GitHubRepositoryPublisherTests: XCTestCase {
    func testCreatesOnlyAnEmptyPrivatePersonalRepositoryWithPinnedAPI() async throws {
        let fixture = fixture([.json(200, ["id": 7, "login": "cody"]), .json(201, repositoryJSON())])
        defer { fixture.close() }

        let repository = try await fixture.publisher.createPrivateRepository(name: "notes", token: "test-token")
        XCTAssertEqual(repository.id, 42)
        XCTAssertEqual(repository.owner, "cody")
        XCTAssertTrue(repository.isPrivate)
        let requests = fixture.stub.requests
        XCTAssertEqual(requests.count, 2)
        XCTAssertEqual(requests[0].url?.absoluteString, "https://api.github.com/user")
        XCTAssertEqual(requests[1].url?.absoluteString, "https://api.github.com/user/repos")
        XCTAssertEqual(requests[1].httpMethod, "POST")
        XCTAssertEqual(requests[1].value(forHTTPHeaderField: "Authorization"), "Bearer test-token")
        XCTAssertEqual(requests[1].value(forHTTPHeaderField: "X-GitHub-Api-Version"), "2022-11-28")
        let body = try XCTUnwrap(try JSONSerialization.jsonObject(with: bodyData(requests[1])) as? [String: Any])
        XCTAssertEqual(Set(body.keys), ["name", "private", "auto_init"])
        XCTAssertEqual(body["name"] as? String, "notes")
        XCTAssertEqual(body["private"] as? Bool, true)
        XCTAssertEqual(body["auto_init"] as? Bool, false)
    }

    func testAuthenticatedAccountComesFromTheTokenRatherThanRepositoryInput() async throws {
        let fixture = fixture([.json(200, ["id": 7, "login": "signed-in-account"])])
        defer { fixture.close() }
        let login = try await fixture.publisher.authenticatedLogin(token: "test-token")
        XCTAssertEqual(login, "signed-in-account")
        XCTAssertEqual(fixture.stub.requests.first?.httpMethod, "GET")
    }

    func testAuthenticatedAccountIncludesDurableUserIDWhenLoginIsReused() async throws {
        let fixture = fixture([
            .json(200, ["id": 7, "login": "same-login"]),
            .json(200, ["id": 8, "login": "same-login"])
        ])
        defer { fixture.close() }
        let original = try await fixture.publisher.authenticatedAccount(token: "original-token")
        let replacement = try await fixture.publisher.authenticatedAccount(token: "replacement-token")
        XCTAssertEqual(original.id, 7)
        XCTAssertEqual(replacement.id, 8)
        XCTAssertEqual(original.login, replacement.login)
        XCTAssertNotEqual(original, replacement)
    }

    func testAuthenticatedAccountRejectsMissingOrNonpositiveUserID() async throws {
        for body: [String: Any] in [
            ["login": "cody"], ["id": 0, "login": "cody"], ["id": -7, "login": "cody"]
        ] {
            let fixture = fixture([.json(200, body)])
            defer { fixture.close() }
            do {
                _ = try await fixture.publisher.authenticatedAccount(token: "test-token")
                XCTFail("Expected invalid account identity rejection")
            } catch let error as GitHubRepositoryPublicationError {
                guard case .unavailable = error else { return XCTFail("Unexpected error: \(error)") }
            }
        }
    }

    func testCreationRejectsWrongOwnerVisibilityAndNoncanonicalDestinationAsUnknown() async throws {
        let changes: [(String, Any)] = [
            ("owner", ["login": "someone-else"]),
            ("private", false),
            ("name", "different-name"),
            ("html_url", "http://github.com/cody/notes"),
            ("html_url", "https://github.com/cody/notes?unexpected=1"),
            ("html_url", "https://github.com/cody/notes/extra"),
            ("clone_url", "https://github.com.evil.example/cody/notes.git"),
            ("clone_url", "https://token@github.com/cody/notes.git"),
            ("clone_url", "https://github.com:444/cody/notes.git"),
            ("clone_url", "https://github.com/other/notes.git")
        ]
        for (key, value) in changes {
            var repository = repositoryJSON()
            repository[key] = value
            let fixture = fixture([.json(200, ["id": 7, "login": "cody"]), .json(201, repository)])
            defer { fixture.close() }
            do {
                _ = try await fixture.publisher.createPrivateRepository(name: "notes", token: "test-token")
                XCTFail("Expected rejection of \(key): \(value)")
            } catch let error as GitHubRepositoryPublicationError {
                XCTAssertTrue(error.creationOutcomeIsUnknown, "Creation succeeded but its destination was not usable: \(error)")
            }
            XCTAssertEqual(fixture.stub.requests.count, 2, "Returned URLs must never receive an authenticated request")
        }
    }

    func testCreationTimeoutCancellationServerErrorAndMalformedSuccessHaveUnknownOutcomes() async throws {
        let responses: [GitHubPublisherStub.Response] = [
            .failure(URLError(.timedOut)),
            .failure(URLError(.cancelled)),
            .json(503, ["message": "Service unavailable"]),
            .http(201, Data("not-json".utf8)),
            .json(200, repositoryJSON())
        ]
        for response in responses {
            let fixture = fixture([.json(200, ["id": 7, "login": "cody"]), response])
            defer { fixture.close() }
            do {
                _ = try await fixture.publisher.createPrivateRepository(name: "notes", token: "test-token")
                XCTFail("Expected an unknown creation outcome")
            } catch let error as GitHubRepositoryPublicationError {
                XCTAssertTrue(error.creationOutcomeIsUnknown)
            }
        }
    }

    func testCreationValidationErrorIsDefinitiveAndIncludesGitHubMessage() async throws {
        let fixture = fixture([
            .json(200, ["id": 7, "login": "cody"]),
            .json(422, ["message": "name already exists on this account"])
        ])
        defer { fixture.close() }
        do {
            _ = try await fixture.publisher.createPrivateRepository(name: "notes", token: "test-token")
            XCTFail("Expected a name collision")
        } catch let error as GitHubRepositoryPublicationError {
            XCTAssertEqual(error, .validationFailed("name already exists on this account"))
            XCTAssertFalse(error.creationOutcomeIsUnknown)
        }
    }

    func testAuthenticationPolicyAndRateLimitsRemainDistinct() async throws {
        let cases: [(GitHubPublisherStub.Response, GitHubRepositoryPublicationError)] = [
            (.json(401, ["message": "Bad credentials"]), .authenticationRequired),
            (.json(403, ["message": "Resource not accessible by token"]), .forbidden("Resource not accessible by token")),
            (.json(403, ["message": "API rate limit exceeded"], headers: ["X-RateLimit-Remaining": "0"]), .rateLimited(retryAfter: nil)),
            (.json(429, ["message": "Too many requests"], headers: ["Retry-After": "30"]), .rateLimited(retryAfter: "30"))
        ]
        for (response, expected) in cases {
            let fixture = fixture([response])
            defer { fixture.close() }
            do {
                _ = try await fixture.publisher.authenticatedLogin(token: "test-token")
                XCTFail("Expected \(expected)")
            } catch let error as GitHubRepositoryPublicationError {
                XCTAssertEqual(error, expected)
            }
        }
    }

    func testRepository404NeverMeansConfirmedAbsence() async throws {
        let fixture = fixture([.json(404, ["message": "Not Found"])])
        defer { fixture.close() }
        do {
            _ = try await fixture.publisher.repository(owner: "cody", name: "notes", token: "test-token")
            XCTFail("Expected unavailable-or-unauthorized ambiguity")
        } catch let error as GitHubRepositoryPublicationError {
            XCTAssertEqual(error, .repositoryUnavailable)
        }
    }

    func testMissingBranchRequiresReadableReferences() async throws {
        let fixture = fixture([
            .json(200, repositoryJSON()),
            .json(404, ["message": "Not Found"]),
            .json(200, [])
        ])
        defer { fixture.close() }
        let oid = try await fixture.publisher.branchOID(owner: "cody", name: "notes", branch: "main", token: "test-token")
        XCTAssertNil(oid)
        XCTAssertEqual(fixture.stub.requests.map { $0.url!.path }, [
            "/repos/cody/notes", "/repos/cody/notes/git/ref/heads/main", "/repos/cody/notes/git/matching-refs"
        ])
        XCTAssertEqual(fixture.stub.requests.last?.url?.absoluteString, "https://api.github.com/repos/cody/notes/git/matching-refs/")
    }

    func testMetadataAccessWithoutContentsAccessDoesNotMakeBranchMissing() async throws {
        let fixture = fixture([
            .json(200, repositoryJSON()),
            .json(404, ["message": "Not Found"]),
            .json(403, ["message": "Resource not accessible by token"])
        ])
        defer { fixture.close() }
        do {
            _ = try await fixture.publisher.branchOID(owner: "cody", name: "notes", branch: "main", token: "test-token")
            XCTFail("Expected contents permission failure")
        } catch let error as GitHubRepositoryPublicationError {
            XCTAssertEqual(error, .forbidden("Resource not accessible by token"))
        }
    }

    func testBranchLookupChecksExactRefAndEncodesReservedCharacters() async throws {
        let sha = String(repeating: "a", count: 40)
        let fixture = fixture([
            .json(200, repositoryJSON()),
            .json(200, ["ref": "refs/heads/topic/a#b", "object": ["sha": sha, "type": "commit"]])
        ])
        defer { fixture.close() }
        let oid = try await fixture.publisher.branchOID(owner: "cody", name: "notes", branch: "topic/a#b", token: "test-token")
        XCTAssertEqual(oid, sha)
        XCTAssertEqual(fixture.stub.requests.last?.url?.absoluteString, "https://api.github.com/repos/cody/notes/git/ref/heads/topic/a%23b")
    }

    func testEmptyReference409RequiresSpecificMessageAndRevalidatedRepository() async throws {
        let fixture = fixture([
            .json(200, repositoryJSON()),
            .json(409, ["message": "Git Repository is empty."]),
            .json(200, repositoryJSON())
        ])
        defer { fixture.close() }
        let hasReferences = try await fixture.publisher.hasAnyReferences(owner: "cody", name: "notes", token: "test-token")
        XCTAssertFalse(hasReferences)
        XCTAssertEqual(fixture.stub.requests.count, 3)
    }

    func testArbitrary409AndRevokedRepositoryCannotBeTreatedAsEmpty() async throws {
        let cases: [[GitHubPublisherStub.Response]] = [
            [.json(200, repositoryJSON()), .json(409, ["message": "Unexpected conflict"])],
            [.json(200, repositoryJSON()), .json(409, ["message": "Git Repository is empty."]), .json(404, ["message": "Not Found"])]
        ]
        for responses in cases {
            let fixture = fixture(responses)
            defer { fixture.close() }
            do {
                _ = try await fixture.publisher.hasAnyReferences(owner: "cody", name: "notes", token: "test-token")
                XCTFail("Expected unavailable reference state")
            } catch let error as GitHubRepositoryPublicationError {
                switch error {
                case .requestFailed(status: 409, message: _), .repositoryUnavailable: break
                default: XCTFail("Unexpected error: \(error)")
                }
            }
        }
    }

    func testRepositoryPathComponentsCannotChangeAPIHostOrInjectAQuery() async throws {
        var repository = repositoryJSON()
        repository["name"] = "notes?#"
        repository["html_url"] = "https://github.com/cody/notes%3F%23"
        repository["clone_url"] = "https://github.com/cody/notes%3F%23.git"
        let fixture = fixture([.json(200, repository)])
        defer { fixture.close() }
        _ = try await fixture.publisher.repository(owner: "cody", name: "notes?#", token: "test-token")
        XCTAssertEqual(fixture.stub.requests.first?.url?.absoluteString, "https://api.github.com/repos/cody/notes%3F%23")
    }

    func testRejectsTraversalBeforeAnyRequest() async throws {
        let fixture = fixture([])
        defer { fixture.close() }
        do {
            _ = try await fixture.publisher.repository(owner: "..", name: "notes", token: "test-token")
            XCTFail("Expected unsafe path rejection")
        } catch let error as GitHubRepositoryPublicationError {
            guard case .invalidInput = error else { return XCTFail("Unexpected error: \(error)") }
        }
        XCTAssertTrue(fixture.stub.requests.isEmpty)
    }

    private func repositoryJSON() -> [String: Any] {
        [
            "id": 42, "owner": ["login": "cody"], "name": "notes", "private": true,
            "html_url": "https://github.com/cody/notes", "clone_url": "https://github.com/cody/notes.git"
        ]
    }

    private struct Fixture {
        let publisher: GitHubRepositoryPublisher
        let session: URLSession
        let stub: GitHubPublisherStub
        let identifier: String

        func close() {
            session.invalidateAndCancel()
            GitHubPublisherURLProtocol.registry.remove(identifier)
        }
    }

    private func fixture(_ responses: [GitHubPublisherStub.Response]) -> Fixture {
        let identifier = UUID().uuidString
        let stub = GitHubPublisherStub(responses: responses)
        GitHubPublisherURLProtocol.registry.add(stub, identifier: identifier)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [GitHubPublisherURLProtocol.self]
        configuration.httpAdditionalHeaders = ["X-Publisher-Test-ID": identifier]
        let session = URLSession(configuration: configuration)
        return Fixture(publisher: GitHubRepositoryPublisher(session: session), session: session, stub: stub, identifier: identifier)
    }

    private func bodyData(_ request: URLRequest) throws -> Data {
        if let data = request.httpBody { return data }
        let stream = try XCTUnwrap(request.httpBodyStream)
        stream.open()
        defer { stream.close() }
        var data = Data()
        var bytes = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let count = stream.read(&bytes, maxLength: bytes.count)
            guard count >= 0 else { throw try XCTUnwrap(stream.streamError) }
            if count == 0 { break }
            data.append(contentsOf: bytes.prefix(count))
        }
        return data
    }
}

private final class GitHubPublisherStub: @unchecked Sendable {
    enum Response {
        case http(Int, Data, headers: [String: String] = [:])
        case failure(Error)

        static func json(_ status: Int, _ value: Any, headers: [String: String] = [:]) -> Response {
            .http(status, try! JSONSerialization.data(withJSONObject: value), headers: headers)
        }
    }

    private let lock = NSLock()
    private var responses: [Response]
    private var recordedRequests: [URLRequest] = []

    init(responses: [Response]) { self.responses = responses }

    var requests: [URLRequest] {
        lock.lock()
        defer { lock.unlock() }
        return recordedRequests
    }

    func respond(to request: URLRequest) -> Response {
        lock.lock()
        defer { lock.unlock() }
        recordedRequests.append(request)
        guard !responses.isEmpty else { return .failure(URLError(.resourceUnavailable)) }
        return responses.removeFirst()
    }
}

private final class GitHubPublisherStubRegistry: @unchecked Sendable {
    private let lock = NSLock()
    private var stubs: [String: GitHubPublisherStub] = [:]

    func add(_ stub: GitHubPublisherStub, identifier: String) {
        lock.lock()
        defer { lock.unlock() }
        stubs[identifier] = stub
    }

    func remove(_ identifier: String) {
        lock.lock()
        defer { lock.unlock() }
        stubs.removeValue(forKey: identifier)
    }

    func stub(for identifier: String) -> GitHubPublisherStub? {
        lock.lock()
        defer { lock.unlock() }
        return stubs[identifier]
    }
}

private final class GitHubPublisherURLProtocol: URLProtocol {
    static let registry = GitHubPublisherStubRegistry()

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let identifier = request.value(forHTTPHeaderField: "X-Publisher-Test-ID"),
              let stub = Self.registry.stub(for: identifier),
              let url = request.url else {
            client?.urlProtocol(self, didFailWithError: URLError(.resourceUnavailable))
            return
        }
        switch stub.respond(to: request) {
        case .failure(let error):
            client?.urlProtocol(self, didFailWithError: error)
        case .http(let status, let data, let headers):
            let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func stopLoading() {}
}

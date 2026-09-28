import XCTest
@testable import DeepSeekHarnessMobile

final class MobileAccountClientTests: XCTestCase {
    func testLoginRejectsInsecureAndAmbiguousOriginsBeforeSendingCredentials() async {
        for origin in ["http://example.com", "https://user:pass@example.com", "https://example.com/path", "https://example.com?redirect=other", "https://example.com#account"] {
            do {
                _ = try await MobileAccountClient.login(origin: origin, username: "alice", password: "secret")
                XCTFail("Accepted \(origin)")
            } catch { /* Invalid origins never receive credentials. */ }
        }
    }

    func testRefreshRefusesAnotherEndpointAndKeepsLegacyDeviceCredentialsDistinct() async throws {
        let credential = MobileAccountCredential(origin: "https://example.com", userId: 12, serverId: "server",
            gatewayId: "account", gatewayName: "alice", endpoint: "wss://example.com/api/mobile.v1/account",
            refreshCookie: "__Secure-dsh_mobile_refresh=secret")
        let decoded = try XCTUnwrap(MobileAccountCredential.decode(credential.encoded()))
        XCTAssertEqual(decoded.userId, 12)
        XCTAssertEqual(decoded.endpoint, credential.endpoint)
        XCTAssertNil(try MobileAccountCredential.decode("legacy-device-token"))
        do {
            _ = try await MobileAccountClient.access(credential, endpoint: "wss://other.example/api/mobile.v1/account")
            XCTFail("Accepted another server")
        } catch { /* The credential remains scoped to its saved endpoint. */ }
    }
}

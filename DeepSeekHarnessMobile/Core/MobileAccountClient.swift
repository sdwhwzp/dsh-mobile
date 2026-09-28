import Foundation

/// Only this refresh credential is persisted in Keychain; passwords and access tokens stay in memory.
struct MobileAccountCredential: Codable {
    let origin: String
    let userId: Int
    let serverId: String
    let gatewayId: String
    let gatewayName: String
    let endpoint: String
    let refreshCookie: String
    static let prefix = "dsh-account-v1:"

    func encoded() throws -> String {
        Self.prefix + String(decoding: try JSONEncoder().encode(self), as: UTF8.self)
    }

    static func decode(_ stored: String) throws -> Self? {
        guard stored.hasPrefix(prefix) else { return nil }
        return try JSONDecoder().decode(Self.self, from: Data(stored.dropFirst(prefix.count).utf8))
    }
}

/// Separate HTTPS requests and explicit cookies prevent credentials crossing accounts or redirects.
enum MobileAccountClient {
    private static let auth = "/gateway/mobile/v1/auth"
    private struct LoginBody: Encodable { let username: String; let password: String }
    private struct Challenge: Decodable { let challenge: String }
    private struct LoginReply: Decodable {
        struct User: Decodable { let userId: Int }
        struct Gateway: Decodable { let gatewayId: String; let gatewayName: String; let path: String }
        let accessToken: String
        let serverId: String
        let user: User
        let mobileGateway: Gateway
    }
    private struct Failure: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    static func login(origin raw: String, username: String, password: String, previous: MobileAccountCredential? = nil) async throws -> MobileAccountCredential {
        guard var parts = URLComponents(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)),
              parts.scheme == "https", parts.host?.isEmpty == false, parts.user == nil, parts.password == nil,
              parts.query == nil, parts.fragment == nil, parts.path.isEmpty || parts.path == "/" else {
            throw Failure(message: String(localized: "请输入 HTTPS 服务器地址，不要包含路径或凭据"))
        }
        parts.path = ""
        guard let origin = parts.string else { throw URLError(.badURL) }
        let payload = try JSONEncoder().encode(LoginBody(username: username.trimmingCharacters(in: .whitespacesAndNewlines), password: password))
        let refresh = previous.flatMap { $0.origin == origin && $0.gatewayName == username.trimmingCharacters(in: .whitespacesAndNewlines) ? $0.refreshCookie : nil }
        let (data, response) = try await action(origin: origin, name: "login", payload: payload, refresh: refresh)
        let reply = try JSONDecoder().decode(LoginReply.self, from: data)
        let gateway = reply.mobileGateway
        guard GatewayIdentity.isValid(gateway.gatewayId), gateway.path == "/api/mobile.v1/\(gateway.gatewayId)",
              let cookie = cookie(response, name: "__Secure-dsh_mobile_refresh") else {
            throw Failure(message: String(localized: "服务器账号协议不兼容，请更新账号网关"))
        }
        parts.scheme = "wss"
        parts.path = gateway.path
        guard let endpoint = parts.string else { throw URLError(.badURL) }
        return MobileAccountCredential(origin: origin, userId: reply.user.userId, serverId: reply.serverId,
            gatewayId: gateway.gatewayId, gatewayName: gateway.gatewayName, endpoint: endpoint, refreshCookie: cookie)
    }

    static func access(_ credential: MobileAccountCredential, endpoint: String) async throws -> String {
        guard endpoint == credential.endpoint else { throw Failure(message: String(localized: "账号与服务器地址不匹配")) }
        let (data, _) = try await action(origin: credential.origin, name: "refresh", payload: Data("{}".utf8), refresh: credential.refreshCookie)
        let reply = try JSONDecoder().decode(LoginReply.self, from: data)
        guard reply.serverId == credential.serverId, reply.user.userId == credential.userId,
              reply.mobileGateway.gatewayId == credential.gatewayId, reply.accessToken.hasPrefix("dshm.") else {
            throw Failure(message: String(localized: "服务器或账号身份已变更，请重新登录"))
        }
        return reply.accessToken
    }

    static func logout(_ credential: MobileAccountCredential) async throws {
        _ = try await action(origin: credential.origin, name: "logout", payload: Data("{}".utf8), refresh: credential.refreshCookie)
    }

    private static func action(origin: String, name: String, payload: Data, refresh: String?) async throws -> (Data, HTTPURLResponse) {
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        config.timeoutIntervalForRequest = 30
        let session = URLSession(configuration: config, delegate: GatewayRedirectBlocker(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        guard let challengeURL = URL(string: origin + auth + "/challenge"), let actionURL = URL(string: origin + auth + "/" + name) else { throw URLError(.badURL) }
        let (challengeData, challengeResponse) = try await request(session, URLRequest(url: challengeURL))
        let challenge = try JSONDecoder().decode(Challenge.self, from: challengeData)
        guard let challengeCookie = cookie(challengeResponse, name: "__Secure-dsh_mobile_challenge") else {
            throw Failure(message: String(localized: "服务器未返回登录验证凭据"))
        }
        var req = URLRequest(url: actionURL)
        req.httpMethod = "POST"
        req.httpBody = payload
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(challenge.challenge, forHTTPHeaderField: "X-Dsh-Csrf")
        req.setValue([challengeCookie, refresh].compactMap { $0 }.joined(separator: "; "), forHTTPHeaderField: "Cookie")
        return try await request(session, req)
    }

    private static func request(_ session: URLSession, _ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        guard (200..<300).contains(http.statusCode) else {
            throw Failure(message: String(localized: "账号请求失败（\(http.statusCode)），请检查账号或服务器配置"))
        }
        return (data, http)
    }

    private static func cookie(_ response: HTTPURLResponse, name: String) -> String? {
        guard let url = response.url, let header = response.value(forHTTPHeaderField: "Set-Cookie") else { return nil }
        let matches = HTTPCookie.cookies(withResponseHeaderFields: ["Set-Cookie": header], for: url)
            .filter { $0.name == name && $0.isSecure && $0.isHTTPOnly && $0.path == auth && $0.domain == url.host }
        guard matches.count == 1, let value = matches.first else { return nil }
        return "\(value.name)=\(value.value)"
    }
}

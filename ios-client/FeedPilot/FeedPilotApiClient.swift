import Foundation

final class FeedPilotApiClient {
    static let shared = FeedPilotApiClient()

    var accessToken: String?
    var baseURL = URL(string: "https://feedpilot-api-ount.onrender.com/")!

    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    private init() {
        decoder.dateDecodingStrategy = .iso8601
        encoder.dateEncodingStrategy = .iso8601
    }

    func deviceAuth(_ body: DeviceAuthRequest) async throws -> AuthResponse {
        try await request("api/auth/device", method: "POST", body: body)
    }

    func watchedHandles() async throws -> [WatchedHandle] {
        try await request("api/watched-handles")
    }

    func addWatchedHandle(username: String, pollIntervalMinutes: Int) async throws -> WatchedHandle {
        try await request(
            "api/watched-handles",
            method: "POST",
            body: CreateWatchedHandleRequest(username: username, pollIntervalMinutes: pollIntervalMinutes, watchEnabled: true)
        )
    }

    func watchedPosts(handleId: UUID) async throws -> [WatchedPost] {
        try await request("api/watched-handles/\(handleId.uuidString)/posts")
    }

    private func request<T: Decodable>(_ path: String, method: String = "GET") async throws -> T {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = method
        if let accessToken {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)
        return try decoder.decode(T.self, from: data)
    }

    private func request<T: Decodable, B: Encodable>(_ path: String, method: String, body: B) async throws -> T {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let accessToken {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try encoder.encode(body)
        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)
        return try decoder.decode(T.self, from: data)
    }

    private func validate(response: URLResponse, data: Data) throws {
        guard let http = response as? HTTPURLResponse, 200..<300 ~= http.statusCode else {
            throw FeedPilotApiError.server(String(data: data, encoding: .utf8) ?? "Request failed")
        }
    }
}

enum FeedPilotApiError: Error {
    case server(String)
}

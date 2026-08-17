import Foundation

struct DeviceAuthRequest: Codable {
    let installationId: String
    let deviceId: String
    let appInstanceId: String?
    let androidVersion: String?
    let appVersion: String?
}

struct AuthResponse: Codable {
    let userId: UUID
    let name: String
    let email: String
    let accessToken: String
    let refreshToken: String
    let expiresAt: Date
}

struct CreateWatchedHandleRequest: Codable {
    let username: String
    let pollIntervalMinutes: Int
    let watchEnabled: Bool
}

struct WatchedHandle: Codable, Identifiable {
    let id: UUID
    let username: String
    let profilePictureUrl: String?
    let fullName: String?
    let isPrivate: Bool
    let watchEnabled: Bool
    let pollIntervalMinutes: Int
    let lastFetchedAt: Date?
    let createdAt: Date
    let savedPostCount: Int
}

struct WatchedPost: Codable, Identifiable {
    let id: UUID
    let watchedHandleId: UUID
    let postId: String
    let code: String?
    let caption: String?
    let mediaUrl: String?
    let permalink: String?
    let mediaType: Int
    let likeCount: Int64
    let commentCount: Int64
    let takenAt: Date?
    let fetchedAt: Date
}

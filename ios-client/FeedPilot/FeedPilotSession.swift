import Foundation
import UIKit

@MainActor
final class FeedPilotSession: ObservableObject {
    @Published private(set) var isAuthenticated = false
    private let api = FeedPilotApiClient.shared

    func deviceLogin() async {
        guard !isAuthenticated else { return }
        let installId = UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
        do {
            let response = try await api.deviceAuth(
                DeviceAuthRequest(
                    installationId: installId,
                    deviceId: installId,
                    appInstanceId: Bundle.main.bundleIdentifier,
                    androidVersion: nil,
                    appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String
                )
            )
            api.accessToken = response.accessToken
            isAuthenticated = true
        } catch {
            isAuthenticated = false
        }
    }
}

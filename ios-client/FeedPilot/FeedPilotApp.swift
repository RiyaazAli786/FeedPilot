import SwiftUI

@main
struct FeedPilotApp: App {
    @StateObject private var session = FeedPilotSession()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(session)
                .task {
                    await session.deviceLogin()
                }
        }
    }
}

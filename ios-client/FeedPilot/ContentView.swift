import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var session: FeedPilotSession

    var body: some View {
        NavigationStack {
            Group {
                if session.isAuthenticated {
                    WatchedHandlesView()
                } else {
                    ProgressView("Connecting")
                }
            }
            .navigationTitle("FeedPilot")
        }
    }
}

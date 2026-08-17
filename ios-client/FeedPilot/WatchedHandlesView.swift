import SwiftUI

struct WatchedHandlesView: View {
    @State private var handles: [WatchedHandle] = []
    @State private var username = ""
    @State private var isLoading = false

    var body: some View {
        List {
            Section {
                HStack {
                    TextField("Instagram handle", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("Add") {
                        Task { await addHandle() }
                    }
                    .disabled(username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }

            Section {
                ForEach(handles) { handle in
                    NavigationLink {
                        WatchedPostsView(handle: handle)
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("@\(handle.username)")
                                .font(.headline)
                            Text("\(handle.savedPostCount) saved posts")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .refreshable { await load() }
        .task { await load() }
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        handles = (try? await FeedPilotApiClient.shared.watchedHandles()) ?? handles
    }

    private func addHandle() async {
        let clean = username.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "@", with: "")
        guard !clean.isEmpty else { return }
        if let handle = try? await FeedPilotApiClient.shared.addWatchedHandle(username: clean, pollIntervalMinutes: 60) {
            username = ""
            handles.removeAll { $0.id == handle.id }
            handles.append(handle)
            handles.sort { $0.username < $1.username }
        }
    }
}

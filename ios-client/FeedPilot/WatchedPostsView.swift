import SwiftUI

struct WatchedPostsView: View {
    let handle: WatchedHandle
    @State private var posts: [WatchedPost] = []

    var body: some View {
        List(posts) { post in
            VStack(alignment: .leading, spacing: 6) {
                Text(post.code ?? post.postId)
                    .font(.headline)
                if let caption = post.caption, !caption.isEmpty {
                    Text(caption)
                        .font(.subheadline)
                        .lineLimit(3)
                }
                Text("\(post.likeCount) likes  \(post.commentCount) comments")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("@\(handle.username)")
        .task { posts = (try? await FeedPilotApiClient.shared.watchedPosts(handleId: handle.id)) ?? [] }
        .refreshable { posts = (try? await FeedPilotApiClient.shared.watchedPosts(handleId: handle.id)) ?? posts }
    }
}

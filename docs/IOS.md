# iOS

The iOS folder is a SwiftUI starter because this workspace is Windows and cannot compile
an IPA locally.

Implemented starter behavior:

- device auth against `/api/auth/device`
- watched handle list and add flow
- watched post list

To build:

1. Open Xcode on macOS.
2. Create an iOS app target named `FeedPilot`.
3. Use bundle id `com.feedpilot.client`.
4. Add the Swift files from `ios-client/FeedPilot`.
5. Set signing team and run/archive.

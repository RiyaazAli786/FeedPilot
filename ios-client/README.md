# FeedPilot iOS

Native SwiftUI starter for the FeedPilot API.

This workspace was prepared on Windows, so the iOS target cannot be compiled here. Open the
`FeedPilot` folder in Xcode on macOS, create an iOS App target named `FeedPilot`, set the bundle id
to `com.feedpilot.client`, and add the Swift files under `FeedPilot/` to the target.

Default API base URL:

```text
https://feedpilot-api-ount.onrender.com/
```

Implemented starter surface:

- device/direct auth against `/api/auth/device`
- watched Instagram handle cards
- saved watched posts
- API models for the shared ASP.NET backend

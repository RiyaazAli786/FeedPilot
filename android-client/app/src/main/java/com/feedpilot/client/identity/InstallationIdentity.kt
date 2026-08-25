package com.feedpilot.client.identity

/** Public-facing snapshot of this install's cryptographic identity. Never carries the private key. */
data class InstallationIdentity(
    val installationId: String,
    val publicKeyBase64: String
)

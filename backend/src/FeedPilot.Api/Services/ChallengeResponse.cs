using System.Security.Cryptography;
using System.Text;

namespace FeedPilot.Api.Services;

/// <summary>
/// Crypto primitives shared by the admin-login and backup-login challenge-response flows
/// (<see cref="Controllers.AdminAuthController"/>, <see cref="Controllers.AdminBackupAuthController"/>).
/// Mirrors the pattern <see cref="Controllers.AdminDashboardController"/> already uses for its
/// passcode challenge-response, kept as-is there to avoid touching working code — new callers use
/// this instead of reimplementing it.
/// </summary>
internal static class ChallengeResponse
{
    /// <summary>Opaque, unguessable identifier for a cached nonce or session.</summary>
    public static string NewToken() => Convert.ToHexString(RandomNumberGenerator.GetBytes(16));

    /// <summary>One-time challenge material a client HMACs the secret against.</summary>
    public static byte[] NewNonce() => RandomNumberGenerator.GetBytes(32);

    public static byte[] ComputeHmac(string secret, byte[] nonce) =>
        HMACSHA256.HashData(Encoding.UTF8.GetBytes(secret), nonce);

    /// <summary>Length-independent comparison so a wrong hash can't be probed by timing.</summary>
    public static bool VerifyHash(string providedHashBase64, byte[] expectedHash)
    {
        byte[] providedHash;
        try
        {
            providedHash = Convert.FromBase64String(providedHashBase64);
        }
        catch (FormatException)
        {
            return false;
        }

        return providedHash.Length == expectedHash.Length &&
               CryptographicOperations.FixedTimeEquals(providedHash, expectedHash);
    }

    /// <summary>256-bit bearer token for a session cache entry — high-entropy enough that a direct
    /// cache lookup (rather than a fixed-time compare) is not a meaningful timing side channel.</summary>
    public static string NewSessionToken() => Convert.ToHexString(RandomNumberGenerator.GetBytes(32));

    public static string NewPersistentSessionToken(string secret)
    {
        var nonce = RandomNumberGenerator.GetBytes(32);
        var signature = HMACSHA256.HashData(Encoding.UTF8.GetBytes(secret), nonce);
        return $"v1.{Convert.ToHexString(nonce)}.{Convert.ToHexString(signature)}";
    }

    public static bool VerifyPersistentSessionToken(string token, string secret)
    {
        var parts = token.Split('.');
        if (parts.Length != 3 || parts[0] != "v1") return false;

        byte[] nonce;
        byte[] providedSignature;
        try
        {
            nonce = Convert.FromHexString(parts[1]);
            providedSignature = Convert.FromHexString(parts[2]);
        }
        catch (FormatException)
        {
            return false;
        }

        var expectedSignature = HMACSHA256.HashData(Encoding.UTF8.GetBytes(secret), nonce);
        return providedSignature.Length == expectedSignature.Length &&
               CryptographicOperations.FixedTimeEquals(providedSignature, expectedSignature);
    }
}

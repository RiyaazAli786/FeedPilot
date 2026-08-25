using System.Security.Cryptography;

namespace FeedPilot.Api.Services;

public interface ISignatureService
{
    bool Verify(string publicKeyBase64, string challengeBase64, string signatureBase64);
}

/// <summary>Verifies the ECDSA proof an Android installation produces with its Keystore-held
/// secp256r1 private key (see identity.KeystoreManager on the client).</summary>
public class SignatureService : ISignatureService
{
    /// <summary>
    /// Verifies SHA256withECDSA over the raw challenge bytes using the installation's registered
    /// SubjectPublicKeyInfo (X.509) public key. Android's java.security.Signature produces a
    /// DER-encoded (ASN.1) signature by default for "SHA256withECDSA", which matches .NET's
    /// DSASignatureFormat.Rfc3279DerSequence — no format conversion needed between platforms.
    /// </summary>
    public bool Verify(string publicKeyBase64, string challengeBase64, string signatureBase64)
    {
        try
        {
            var keyBytes = Convert.FromBase64String(publicKeyBase64);
            var challengeBytes = Convert.FromBase64String(challengeBase64);
            var signatureBytes = Convert.FromBase64String(signatureBase64);

            using var ecdsa = ECDsa.Create();
            ecdsa.ImportSubjectPublicKeyInfo(keyBytes, out _);
            return ecdsa.VerifyData(challengeBytes, signatureBytes, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);
        }
        catch
        {
            return false;
        }
    }
}

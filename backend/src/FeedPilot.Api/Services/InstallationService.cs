using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;

namespace FeedPilot.Api.Services;

public interface IInstallationService
{
    Task<AppInstallation> RegisterAsync(string installationId, string publicKeyBase64, Guid? userId, CancellationToken ct);
    Task<AppInstallation?> GetAsync(string installationId, CancellationToken ct);
    Task TouchLastSeenAsync(AppInstallation installation, CancellationToken ct);
    Task AssociateUserAsync(AppInstallation installation, Guid userId, CancellationToken ct);
    Task RevokeAsync(AppInstallation installation, CancellationToken ct);
}

/// <summary>
/// Foundational-phase installation identity store. See IdentityIsolation.md — this table sits
/// alongside the existing, untrusted <see cref="Device"/> table rather than replacing it; nothing
/// yet gates on a row existing here.
/// </summary>
public class InstallationService : IInstallationService
{
    private readonly AppDbContext _db;
    public InstallationService(AppDbContext db) => _db = db;

    /// <summary>
    /// Upsert by InstallationId. Always accepts the latest public key presented — trust is
    /// enforced by signature verification (see ISignatureService), not by this write. A public key
    /// can legitimately rotate, e.g. when CloneDataIsolationGuard regenerates the Keystore key.
    /// </summary>
    public async Task<AppInstallation> RegisterAsync(string installationId, string publicKeyBase64, Guid? userId, CancellationToken ct)
    {
        var existing = await _db.AppInstallations.FirstOrDefaultAsync(x => x.InstallationId == installationId, ct);
        if (existing is null)
        {
            existing = new AppInstallation
            {
                InstallationId = installationId,
                PublicKey = publicKeyBase64,
                UserId = userId
            };
            _db.AppInstallations.Add(existing);
        }
        else
        {
            existing.PublicKey = publicKeyBase64;
            existing.LastSeenAt = DateTime.UtcNow;
            if (userId.HasValue) existing.UserId = userId;
        }

        await _db.SaveChangesAsync(ct);
        return existing;
    }

    public Task<AppInstallation?> GetAsync(string installationId, CancellationToken ct) =>
        _db.AppInstallations.FirstOrDefaultAsync(x => x.InstallationId == installationId, ct);

    public async Task TouchLastSeenAsync(AppInstallation installation, CancellationToken ct)
    {
        installation.LastSeenAt = DateTime.UtcNow;
        await _db.SaveChangesAsync(ct);
    }

    public async Task AssociateUserAsync(AppInstallation installation, Guid userId, CancellationToken ct)
    {
        installation.UserId = userId;
        await _db.SaveChangesAsync(ct);
    }

    public async Task RevokeAsync(AppInstallation installation, CancellationToken ct)
    {
        installation.Status = InstallationStatus.Revoked;
        installation.RevokedAt = DateTime.UtcNow;
        await _db.SaveChangesAsync(ct);
    }
}

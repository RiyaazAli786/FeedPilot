using System.Security.Cryptography;
using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;

namespace FeedPilot.Api.Services;

public interface IInstallationChallengeService
{
    Task<InstallationChallenge> IssueAsync(string installationId, CancellationToken ct);
    Task<bool> ConsumeAsync(string installationId, string challengeBase64, CancellationToken ct);
}

/// <summary>
/// Random, single-use, short-lived challenges for installation signature verification
/// (IdentityIsolation.md Phase 8-11). Not yet consumed by any enforcement path.
/// </summary>
public class InstallationChallengeService : IInstallationChallengeService
{
    private readonly AppDbContext _db;

    // Within the doc's recommended 30-120 second lifetime.
    private static readonly TimeSpan Lifetime = TimeSpan.FromSeconds(90);

    public InstallationChallengeService(AppDbContext db) => _db = db;

    public async Task<InstallationChallenge> IssueAsync(string installationId, CancellationToken ct)
    {
        var challenge = new InstallationChallenge
        {
            InstallationId = installationId,
            Challenge = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)),
            ExpiresAt = DateTime.UtcNow.Add(Lifetime)
        };
        _db.InstallationChallenges.Add(challenge);
        await _db.SaveChangesAsync(ct);
        return challenge;
    }

    /// <summary>Single-use: marks UsedAt in the same lookup so a replay of the same challenge value fails.</summary>
    public async Task<bool> ConsumeAsync(string installationId, string challengeBase64, CancellationToken ct)
    {
        var row = await _db.InstallationChallenges.FirstOrDefaultAsync(x =>
            x.InstallationId == installationId &&
            x.Challenge == challengeBase64 &&
            x.UsedAt == null &&
            x.ExpiresAt > DateTime.UtcNow, ct);

        if (row is null) return false;

        row.UsedAt = DateTime.UtcNow;
        await _db.SaveChangesAsync(ct);
        return true;
    }
}

using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;

namespace FeedPilot.Api.Services;

/// <summary>
/// Get-or-create for the single <see cref="SmmProviderConfig"/> row (Id=1). On first read (a
/// fresh database with no row yet) it seeds from the "SmmPanel" appsettings/env-var section
/// rather than the hardcoded entity defaults, so an already-deployed instance's configured API
/// key and service IDs carry over instead of silently resetting to empty on upgrade.
/// </summary>
public static class SmmProviderConfigStore
{
    public static async Task<SmmProviderConfig> GetOrCreateAsync(
        AppDbContext db, IOptions<SmmPanelSettings> fallback, CancellationToken ct)
    {
        var config = await db.SmmProviderConfigs.FirstOrDefaultAsync(x => x.Id == 1, ct);
        if (config is not null) return config;

        var seed = fallback.Value;
        config = new SmmProviderConfig
        {
            Id = 1,
            BaseUrl = seed.BaseUrl,
            ApiKey = seed.ApiKey,
            FollowServiceId = seed.FollowServiceId,
            LikeServiceId = seed.LikeServiceId,
            CommentServiceId = seed.CommentServiceId,
            CommentCustomServiceId = seed.CommentCustomServiceId,
            RepostServiceId = seed.RepostServiceId,
            SavePostServiceId = seed.SavePostServiceId,
            StoryViewServiceId = seed.StoryViewServiceId,
            PollIntervalMinutes = seed.PollIntervalMinutes,
        };
        db.SmmProviderConfigs.Add(config);
        await db.SaveChangesAsync(ct);
        return config;
    }
}

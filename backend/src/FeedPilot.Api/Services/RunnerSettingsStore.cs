using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Data;
using FeedPilot.Api.Domain;

namespace FeedPilot.Api.Services;

/// <summary>
/// Get-or-create for the single <see cref="RunnerSettings"/> row (Id=1), shared by the
/// app-facing read endpoint and the admin read/write endpoint so both agree on how the row
/// gets provisioned the first time either is called on a fresh database.
/// </summary>
public static class RunnerSettingsStore
{
    public static async Task<RunnerSettings> GetOrCreateAsync(AppDbContext db, CancellationToken ct)
    {
        var settings = await db.RunnerSettings.FirstOrDefaultAsync(x => x.Id == 1, ct);
        if (settings is not null) return settings;

        settings = new RunnerSettings { Id = 1 };
        db.RunnerSettings.Add(settings);
        await db.SaveChangesAsync(ct);
        return settings;
    }

    /// <summary>
    /// Dashboard-configured per-action coin reward for Normal vs Upgraded accounts. Shared by
    /// every endpoint that credits a completed action so the "Action Coin Pricing" panel actually
    /// governs every award path, not just the one it happened to be written for first.
    /// </summary>
    public static int GetActionCoinReward(TaskType taskType, bool isUpgraded, RunnerSettings settings) =>
        taskType switch
        {
            TaskType.Follow => isUpgraded ? settings.FollowCoinsUpgraded : settings.FollowCoinsNormal,
            TaskType.Like => isUpgraded ? settings.LikeCoinsUpgraded : settings.LikeCoinsNormal,
            TaskType.Comment => isUpgraded ? settings.CommentCoinsUpgraded : settings.CommentCoinsNormal,
            TaskType.Repost => isUpgraded ? settings.RepostCoinsUpgraded : settings.RepostCoinsNormal,
            TaskType.SavePost => isUpgraded ? settings.SavePostCoinsUpgraded : settings.SavePostCoinsNormal,
            TaskType.StoryView => isUpgraded ? settings.StoryViewCoinsUpgraded : settings.StoryViewCoinsNormal,
            _ => isUpgraded ? 2 : 1
        };
}

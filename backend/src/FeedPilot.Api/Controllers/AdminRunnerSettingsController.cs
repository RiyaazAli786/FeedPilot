using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using FeedPilot.Api.Data;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Hubs;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

/// <summary>
/// Dashboard control panel for the Android runner's pacing — see <see cref="RunnerSettingsController"/>
/// for the read-only side every client polls. Guarded by an admin session token rather than a
/// user JWT, same as every other admin controller.
/// </summary>
[ApiController]
[AdminSession]
[Route("api/admin/runner-settings")]
public class AdminRunnerSettingsController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly IHubContext<CoinSyncHub, ICoinSyncClient> _hubContext;

    public AdminRunnerSettingsController(AppDbContext db, IHubContext<CoinSyncHub, ICoinSyncClient> hubContext)
    {
        _db = db;
        _hubContext = hubContext;
    }

    [HttpGet]
    public async Task<ActionResult<RunnerSettingsDto>> Get(CancellationToken ct)
    {
        var s = await RunnerSettingsStore.GetOrCreateAsync(_db, ct);
        return Ok(ToDto(s));
    }

    [HttpPatch]
    public async Task<ActionResult<RunnerSettingsDto>> Update(
        [FromBody] UpdateRunnerSettingsRequest body, CancellationToken ct)
    {
        if (body.ActionDelayMinMs > body.ActionDelayMaxMs)
            return BadRequest(new ApiError(
                "Action delay min cannot be greater than max.", "INVALID_DELAY_RANGE"));

        var s = await RunnerSettingsStore.GetOrCreateAsync(_db, ct);
        s.ActionDelayMinMs = body.ActionDelayMinMs;
        s.ActionDelayMaxMs = body.ActionDelayMaxMs;
        s.FetchDelayMs = body.FetchDelayMs;
        s.CooldownSeconds = body.CooldownSeconds;
        s.AutoPartialCancelledTasks = body.AutoPartialCancelledTasks;
        s.CoinsPerInr = body.CoinsPerInr;
        s.MinWithdrawalInr = body.MinWithdrawalInr;
        s.ClaimBatchSize = Math.Clamp(body.ClaimBatchSize, 1, 50);
        s.ClaimTimeoutMinutes = Math.Clamp(body.ClaimTimeoutMinutes, 1, 60);

        s.FollowCoinsNormal = Math.Max(1, body.FollowCoinsNormal);
        s.FollowCoinsUpgraded = Math.Max(1, body.FollowCoinsUpgraded);
        s.LikeCoinsNormal = Math.Max(1, body.LikeCoinsNormal);
        s.LikeCoinsUpgraded = Math.Max(1, body.LikeCoinsUpgraded);
        s.CommentCoinsNormal = Math.Max(1, body.CommentCoinsNormal);
        s.CommentCoinsUpgraded = Math.Max(1, body.CommentCoinsUpgraded);
        s.RepostCoinsNormal = Math.Max(1, body.RepostCoinsNormal);
        s.RepostCoinsUpgraded = Math.Max(1, body.RepostCoinsUpgraded);
        s.SavePostCoinsNormal = Math.Max(1, body.SavePostCoinsNormal);
        s.SavePostCoinsUpgraded = Math.Max(1, body.SavePostCoinsUpgraded);
        s.StoryViewCoinsNormal = Math.Max(1, body.StoryViewCoinsNormal);
        s.StoryViewCoinsUpgraded = Math.Max(1, body.StoryViewCoinsUpgraded);
        s.PricePerFollow = Math.Max(1, body.PricePerFollow);
        s.PricePerLike = Math.Max(1, body.PricePerLike);
        s.PricePerComment = Math.Max(1, body.PricePerComment);
        s.PricePerRepost = Math.Max(1, body.PricePerRepost);
        s.PricePerSavePost = Math.Max(1, body.PricePerSavePost);
        s.PricePerStoryView = Math.Max(1, body.PricePerStoryView);
        s.ReferralBonusCoins = Math.Max(0, body.ReferralBonusCoins);
        s.ReferralLevel1Percent = Math.Clamp(body.ReferralLevel1Percent, 0, 100);
        s.ReferralLevel2Percent = Math.Clamp(body.ReferralLevel2Percent, 0, 100);
        s.ReferralLevel3Percent = Math.Clamp(body.ReferralLevel3Percent, 0, 100);
        s.SupportContactUrl = string.IsNullOrWhiteSpace(body.SupportContactUrl) ? null : body.SupportContactUrl.Trim();
        s.TelegramChannelUrl = string.IsNullOrWhiteSpace(body.TelegramChannelUrl) ? null : body.TelegramChannelUrl.Trim();
        s.UpiEnabled = body.UpiEnabled;
        s.BankEnabled = body.BankEnabled;
        s.UsdtBep20Enabled = body.UsdtBep20Enabled;
        s.CoinsPerUsdt = Math.Max(1, body.CoinsPerUsdt);
        s.MinWithdrawalUsdt = Math.Max(0, body.MinWithdrawalUsdt);
        s.FollowStreakCount    = Math.Clamp(body.FollowStreakCount,    1, 500);
        s.LikeStreakCount      = Math.Clamp(body.LikeStreakCount,      1, 500);
        s.CommentStreakCount   = Math.Clamp(body.CommentStreakCount,   1, 500);
        s.RepostStreakCount    = Math.Clamp(body.RepostStreakCount,    1, 500);
        s.SavePostStreakCount  = Math.Clamp(body.SavePostStreakCount,  1, 500);
        s.StoryViewStreakCount = Math.Clamp(body.StoryViewStreakCount, 1, 500);

        s.MaxFollowsPerDay          = Math.Clamp(body.MaxFollowsPerDay,          0, 100_000);
        s.MaxLikesPerDay            = Math.Clamp(body.MaxLikesPerDay,            0, 100_000);
        s.MaxCommentsPerDay         = Math.Clamp(body.MaxCommentsPerDay,         0, 100_000);
        s.MaxRepostsPerDay          = Math.Clamp(body.MaxRepostsPerDay,          0, 100_000);
        s.MaxSavePostsPerDay        = Math.Clamp(body.MaxSavePostsPerDay,        0, 100_000);
        s.MaxStoryViewsPerDay        = Math.Clamp(body.MaxStoryViewsPerDay,        0, 100_000);
        s.DailyLimitCooldownMinutes = Math.Clamp(body.DailyLimitCooldownMinutes, 1, 1_440);

        s.UpdatedAt = DateTime.UtcNow;
        await _db.SaveChangesAsync(ct);

        var dto = ToDto(s);
        await _hubContext.Clients.All.RunnerSettingsUpdated(dto);

        return Ok(dto);
    }

    public static RunnerSettingsDto ToDto(Domain.RunnerSettings s) => new(
        s.ActionDelayMinMs, s.ActionDelayMaxMs, s.FetchDelayMs, s.CooldownSeconds,
        s.AutoPartialCancelledTasks, s.CoinsPerInr, s.MinWithdrawalInr, s.ClaimBatchSize,
        s.ClaimTimeoutMinutes,
        s.FollowCoinsNormal, s.FollowCoinsUpgraded,
        s.LikeCoinsNormal, s.LikeCoinsUpgraded,
        s.CommentCoinsNormal, s.CommentCoinsUpgraded,
        s.RepostCoinsNormal, s.RepostCoinsUpgraded,
        s.SavePostCoinsNormal, s.SavePostCoinsUpgraded,
        s.StoryViewCoinsNormal, s.StoryViewCoinsUpgraded,
        s.PricePerFollow, s.PricePerLike,
        s.PricePerComment, s.PricePerRepost,
        s.PricePerSavePost, s.PricePerStoryView,
        s.ReferralBonusCoins, s.ReferralLevel1Percent, s.ReferralLevel2Percent, s.ReferralLevel3Percent,
        s.SupportContactUrl, s.TelegramChannelUrl,
        s.UpiEnabled, s.BankEnabled, s.UsdtBep20Enabled, s.CoinsPerUsdt, s.MinWithdrawalUsdt,
        s.FollowStreakCount, s.LikeStreakCount, s.CommentStreakCount,
        s.RepostStreakCount, s.SavePostStreakCount, s.StoryViewStreakCount,
        s.MaxFollowsPerDay, s.MaxLikesPerDay, s.MaxCommentsPerDay,
        s.MaxRepostsPerDay, s.MaxSavePostsPerDay, s.MaxStoryViewsPerDay,
        s.DailyLimitCooldownMinutes);
}

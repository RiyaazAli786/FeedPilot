using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Domain;

namespace FeedPilot.Api.Data;

public class AppDbContext : DbContext
{
    public AppDbContext(DbContextOptions<AppDbContext> options) : base(options) { }

    public DbSet<User> Users => Set<User>();
    public DbSet<Account> Accounts => Set<Account>();
    public DbSet<EngagementTask> Tasks => Set<EngagementTask>();
    public DbSet<Wallet> Wallets => Set<Wallet>();
    public DbSet<WalletTransaction> WalletTransactions => Set<WalletTransaction>();
    public DbSet<Withdrawal> Withdrawals => Set<Withdrawal>();
    public DbSet<Device> Devices => Set<Device>();
    public DbSet<AppRelease> AppReleases => Set<AppRelease>();
    public DbSet<AppOrder> AppOrders => Set<AppOrder>();
    public DbSet<PasswordResetRequest> PasswordResetRequests => Set<PasswordResetRequest>();
    public DbSet<SubscriptionRequest> SubscriptionRequests => Set<SubscriptionRequest>();
    public DbSet<InstagramUserIdCache> InstagramUserIdCaches => Set<InstagramUserIdCache>();
    public DbSet<WatchedInstagramHandle> WatchedInstagramHandles => Set<WatchedInstagramHandle>();
    public DbSet<WatchedInstagramPost> WatchedInstagramPosts => Set<WatchedInstagramPost>();
    public DbSet<RunnerSettings> RunnerSettings => Set<RunnerSettings>();
    public DbSet<SmmProviderConfig> SmmProviderConfigs => Set<SmmProviderConfig>();
    public DbSet<CoinTransfer> CoinTransfers => Set<CoinTransfer>();
    public DbSet<PickedUsername> PickedUsernames => Set<PickedUsername>();

    protected override void OnModelCreating(ModelBuilder b)
    {
        b.Entity<User>(e =>
        {
            e.HasIndex(x => x.Email).IsUnique();
            e.Property(x => x.Email).HasMaxLength(256).IsRequired();
            e.Property(x => x.Name).HasMaxLength(128);
            e.HasOne(x => x.Wallet).WithOne(w => w.User!)
                .HasForeignKey<Wallet>(w => w.UserId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<Account>(e =>
        {
            e.HasIndex(x => new { x.UserId, x.Username });
            e.Property(x => x.Username).HasMaxLength(128).IsRequired();
            e.Property(x => x.AppId).HasMaxLength(128).IsRequired();
            e.HasIndex(x => x.AppId);
            e.HasOne(x => x.User).WithMany(u => u.Accounts)
                .HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<EngagementTask>(e =>
        {
            e.HasIndex(x => x.Status);
            e.Property(x => x.TargetId).HasMaxLength(512).IsRequired();
        });

        b.Entity<Wallet>(e =>
        {
            e.HasIndex(x => x.UserId).IsUnique();
        });

        b.Entity<WalletTransaction>(e =>
        {
            e.HasIndex(x => x.WalletId);
            e.HasOne(x => x.Wallet).WithMany(w => w.Transactions)
                .HasForeignKey(x => x.WalletId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<Withdrawal>(e =>
        {
            e.HasIndex(x => x.WalletId);
            e.Property(x => x.Amount).HasPrecision(18, 2);
            e.Property(x => x.AppId).HasMaxLength(128).IsRequired();
            e.Property(x => x.DeviceId).HasMaxLength(128).IsRequired();
            e.Property(x => x.AdminNote).HasMaxLength(512);
            e.Property(x => x.PaymentReference).HasMaxLength(128);
            e.Property(x => x.UsdtAddress).HasMaxLength(128);
            e.HasIndex(x => new { x.AppId, x.DeviceId });
            e.HasIndex(x => x.Status);
            e.HasOne(x => x.Wallet).WithMany()
                .HasForeignKey(x => x.WalletId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<Device>(e =>
        {
            e.Property(x => x.AppId).HasMaxLength(128).IsRequired();
            e.HasIndex(x => x.DeviceId);
            e.HasIndex(x => new { x.AppId, x.DeviceId });
            e.HasIndex(x => x.InstallationId);
        });

        b.Entity<PasswordResetRequest>(e =>
        {
            e.HasIndex(x => x.Status);
            e.HasIndex(x => x.Email);
            e.Property(x => x.Email).HasMaxLength(256).IsRequired();
            e.Property(x => x.AppId).HasMaxLength(128).IsRequired();
            e.Property(x => x.DeviceId).HasMaxLength(128).IsRequired();
            e.Property(x => x.AdminNote).HasMaxLength(512);
        });

        b.Entity<AppOrder>(e =>
        {
            e.HasIndex(x => x.Status);
            e.HasIndex(x => x.CreatedAt);
            e.HasIndex(x => x.UserId);
            e.Property(x => x.TargetUrl).HasMaxLength(512).IsRequired();
            e.Property(x => x.TargetUsername).HasMaxLength(128);
            e.Property(x => x.ProviderName).HasMaxLength(128);
            e.Property(x => x.ProviderServiceId).HasMaxLength(64);
            e.Property(x => x.ProviderOrderId).HasMaxLength(64);
            e.Property(x => x.AdminNote).HasMaxLength(512);
            e.Property(x => x.ErrorMessage).HasMaxLength(512);
            e.Property(x => x.ProcessingDeviceId).HasMaxLength(128);
            e.HasIndex(x => new { x.Status, x.ProcessingStartedAt });
            e.Property(x => x.AppId).HasMaxLength(128).IsRequired();
            e.Property(x => x.DeviceId).HasMaxLength(128).IsRequired();
            e.HasIndex(x => new { x.AppId, x.DeviceId });
            e.Property(x => x.ExternalOrderId).HasMaxLength(64);
            e.HasIndex(x => x.ExternalOrderId).IsUnique();
            e.Property(x => x.ProviderServiceName).HasMaxLength(128);
            e.Property(x => x.ProviderUsername).HasMaxLength(120);
            e.Property(x => x.ProviderChargeCurrency).HasMaxLength(8);
            e.Property(x => x.PanelLastPushedStatus).HasMaxLength(32);
            e.Property(x => x.PanelLastPushedReason).HasMaxLength(512);
            e.HasOne(x => x.User).WithMany()
                .HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<SubscriptionRequest>(e =>
        {
            e.HasIndex(x => x.UserId);
            e.HasIndex(x => x.Status);
            e.Property(x => x.Upi).HasMaxLength(128);
            e.Property(x => x.Utr).HasMaxLength(64).IsRequired();
            e.Property(x => x.AdminNote).HasMaxLength(512);
            e.Property(x => x.AppId).HasMaxLength(128).IsRequired();
            e.Property(x => x.DeviceId).HasMaxLength(128).IsRequired();
            e.HasIndex(x => new { x.AppId, x.DeviceId });
            e.HasOne(x => x.User).WithMany()
                .HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<InstagramUserIdCache>(e =>
        {
            e.HasIndex(x => x.Username).IsUnique();
            e.Property(x => x.Username).HasMaxLength(128).IsRequired();
            e.Property(x => x.UserId).HasMaxLength(64).IsRequired();
        });

        b.Entity<WatchedInstagramHandle>(e =>
        {
            e.HasIndex(x => new { x.UserId, x.AppId, x.DeviceId, x.Username }).IsUnique();
            e.HasIndex(x => new { x.AppId, x.DeviceId });
            e.HasIndex(x => new { x.WatchEnabled, x.LastFetchedAt });
            e.Property(x => x.AppId).HasMaxLength(128).IsRequired();
            e.Property(x => x.DeviceId).HasMaxLength(128).IsRequired();
            e.Property(x => x.Username).HasMaxLength(128).IsRequired();
            e.Property(x => x.ProfilePictureUrl).HasMaxLength(512);
            e.Property(x => x.FullName).HasMaxLength(256);
            e.HasOne(x => x.User).WithMany()
                .HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<WatchedInstagramPost>(e =>
        {
            e.HasIndex(x => new { x.WatchedInstagramHandleId, x.PostId }).IsUnique();
            e.HasIndex(x => x.TakenAt);
            e.Property(x => x.PostId).HasMaxLength(128).IsRequired();
            e.Property(x => x.Code).HasMaxLength(128);
            e.Property(x => x.MediaUrl).HasMaxLength(1024);
            e.Property(x => x.Permalink).HasMaxLength(512);
            e.HasOne(x => x.Handle).WithMany(h => h.Posts)
                .HasForeignKey(x => x.WatchedInstagramHandleId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<RunnerSettings>(e =>
        {
            e.Property(x => x.Id).ValueGeneratedNever();
            e.Property(x => x.MinWithdrawalUsdt).HasPrecision(18, 2);
        });

        b.Entity<SmmProviderConfig>(e =>
        {
            e.Property(x => x.Id).ValueGeneratedNever();
            e.Property(x => x.BaseUrl).HasMaxLength(256).IsRequired();
            e.Property(x => x.ApiKey).HasMaxLength(256);
        });

        b.Entity<CoinTransfer>(e =>
        {
            e.HasIndex(x => x.SenderUserId);
            e.HasIndex(x => x.ReceiverUserId);
            e.HasIndex(x => x.CreatedAt);
            e.Property(x => x.SenderUsername).HasMaxLength(128).IsRequired();
            e.Property(x => x.ReceiverUsername).HasMaxLength(128).IsRequired();
            e.Property(x => x.Note).HasMaxLength(256);
            e.Property(x => x.InitiatedBy).HasMaxLength(32).IsRequired();
        });

        b.Entity<PickedUsername>(e =>
        {
            e.HasIndex(x => x.Username);
            e.HasIndex(x => new { x.Username, x.DeviceId });
            e.HasIndex(x => new { x.DeviceId, x.AppId });
            e.Property(x => x.Username).HasMaxLength(128).IsRequired();
            e.Property(x => x.DeviceId).HasMaxLength(128).IsRequired();
            e.Property(x => x.AppId).HasMaxLength(128).IsRequired();
        });
    }

    public override int SaveChanges(bool acceptAllChangesOnSuccess)
    {
        OnBeforeSave();
        return base.SaveChanges(acceptAllChangesOnSuccess);
    }

    public override async Task<int> SaveChangesAsync(bool acceptAllChangesOnSuccess, CancellationToken cancellationToken = default)
    {
        OnBeforeSave();
        return await base.SaveChangesAsync(acceptAllChangesOnSuccess, cancellationToken);
    }

    private void OnBeforeSave()
    {
        var usernamesToInvalidate = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        foreach (var entry in ChangeTracker.Entries<AppOrder>())
        {
            if (entry.State == EntityState.Deleted)
            {
                var username = entry.Entity.TargetUsername;
                if (!string.IsNullOrWhiteSpace(username))
                {
                    usernamesToInvalidate.Add(username);
                }
                var urlUsername = ExtractUsername(entry.Entity.TargetUrl);
                if (!string.IsNullOrWhiteSpace(urlUsername))
                {
                    usernamesToInvalidate.Add(urlUsername);
                }
            }
            else if (entry.State == EntityState.Modified)
            {
                var statusProp = entry.Property(x => x.Status);
                if (statusProp.IsModified && statusProp.CurrentValue == AppOrderStatus.Completed)
                {
                    var username = entry.Entity.TargetUsername;
                    if (!string.IsNullOrWhiteSpace(username))
                    {
                        usernamesToInvalidate.Add(username);
                    }
                    var urlUsername = ExtractUsername(entry.Entity.TargetUrl);
                    if (!string.IsNullOrWhiteSpace(urlUsername))
                    {
                        usernamesToInvalidate.Add(urlUsername);
                    }
                }
            }
        }

        if (usernamesToInvalidate.Count > 0)
        {
            var lowercaseUsernames = usernamesToInvalidate
                .Select(u => u.ToLowerInvariant())
                .ToList();

            var cachesToRemove = InstagramUserIdCaches
                .Where(c => lowercaseUsernames.Contains(c.Username))
                .ToList();
            
            if (cachesToRemove.Count > 0)
            {
                InstagramUserIdCaches.RemoveRange(cachesToRemove);
            }
        }
    }

    private static string? ExtractUsername(string? link)
    {
        if (string.IsNullOrWhiteSpace(link)) return null;
        var trimmed = link.Trim();
        if (trimmed.StartsWith('@')) return trimmed.TrimStart('@');
        try
        {
            if (!trimmed.StartsWith("http://", StringComparison.OrdinalIgnoreCase) &&
                !trimmed.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            {
                trimmed = "https://" + trimmed;
            }
            var uri = new Uri(trimmed);
            var segments = uri.AbsolutePath.Trim('/').Split('/');
            if (segments.Length > 0)
            {
                var first = segments[0];
                if (segments.Length > 1 && (
                    first.Equals("p", StringComparison.OrdinalIgnoreCase) ||
                    first.Equals("reel", StringComparison.OrdinalIgnoreCase) ||
                    first.Equals("reels", StringComparison.OrdinalIgnoreCase) ||
                    first.Equals("tv", StringComparison.OrdinalIgnoreCase)))
                {
                    return segments[1];
                }
                return first;
            }
            return null;
        }
        catch { return trimmed.TrimStart('@'); }
    }
}

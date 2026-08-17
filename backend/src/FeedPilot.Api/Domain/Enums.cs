namespace FeedPilot.Api.Domain;

public enum AccountStatus
{
    Active,
    Suspended,
    Checkpoint,
    Captcha,
    VerificationRequired,
    SessionExpired,
    LoginFailed,
    Disabled,
    TemporaryRestricted,
    Unknown
}

public enum TaskType
{
    Like,
    Follow,
    Comment,
    Repost,
    SavePost,
    StoryView
}

/// <summary>
/// Paid tiers that raise how many coins an earned action is worth. Free is the implicit
/// default for every account. See <see cref="Services.PlanCatalog"/> for prices and rates.
/// </summary>
public enum SubscriptionPlan
{
    Free,
    Silver,
    Gold,
    Platinum
}

/// <summary>Lifecycle of a paid-plan purchase awaiting UPI-payment verification by an admin.</summary>
public enum SubscriptionRequestStatus
{
    /// <summary>UTR submitted; an admin must verify the payment.</summary>
    Pending,
    /// <summary>Payment verified — the plan was activated on the account.</summary>
    Approved,
    /// <summary>Payment not found / invalid. The plan was not activated.</summary>
    Rejected
}

public enum TaskStatus
{
    Pending,
    Assigned,
    Running,
    Completed,
    Failed,
    Skipped
}

public enum WithdrawalStatus
{
    Pending,
    Processing,
    Approved,
    Rejected,
    Completed
}

public enum PasswordResetStatus
{
    Pending,
    /// <summary>An admin set a new password; it must be delivered to the user out of band.</summary>
    Completed,
    Rejected
}

public enum PaymentMethod
{
    Upi,
    Bank,
    // Appended so existing persisted ordinals keep their meaning.
    UsdtBep20
}

public enum WalletTransactionType
{
    Earn,
    Withdraw,
    Adjustment,
    Refund,
    // Appended so existing persisted ordinals keep their meaning.
    Spend,
    // User-to-user coin transfer (see CoinTransferController / AdminWalletsController.Transfer).
    TransferOut,
    TransferIn,
    // Referral commission earned when a referred user/device completes actions.
    Referral,
    // Coins moved out of the spendable balance into PendingCoins when a withdrawal request is
    // created. Distinct from Withdraw (which fires once the payout is actually confirmed) so the
    // two audit events don't double-count against each other.
    WithdrawHold
}

/// <summary>Lifecycle of an order placed from the app, as managed on the backend dashboard.</summary>
public enum AppOrderStatus
{
    /// <summary>Accepted and paid for, awaiting admin review.</summary>
    Pending,
    /// <summary>Cleared by an admin, not yet sent to a provider.</summary>
    Approved,
    /// <summary>Handed to an SMM provider; ProviderOrderId is set.</summary>
    Submitted,
    InProgress,
    /// <summary>
    /// Claimed by a worker device and actively being fulfilled. Acts as the lock: a claimed
    /// order is invisible to every other device until it completes or the claim goes stale.
    /// </summary>
    Processing,
    Completed,
    /// <summary>Turned down by an admin. Coins are refunded.</summary>
    Rejected,
    Failed,
    /// <summary>Withdrawn by the user before fulfilment. Coins are refunded.</summary>
    Canceled,
    /// <summary>The Instagram target no longer resolves, so no worker should claim it again.</summary>
    NotFound
}

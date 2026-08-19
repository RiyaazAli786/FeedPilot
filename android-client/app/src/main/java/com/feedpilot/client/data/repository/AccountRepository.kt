package com.feedpilot.client.data.repository

import android.content.Context
import com.feedpilot.client.common.Resource
import com.feedpilot.client.common.apiErrorMessage
import com.feedpilot.client.data.local.AccountDao
import com.feedpilot.client.data.local.AccountEntity
import com.feedpilot.client.data.remote.ApiService
import com.feedpilot.client.data.remote.InstagramLoginResult
import com.feedpilot.client.data.remote.dto.CreateAccountRequest
import com.feedpilot.client.data.remote.dto.LeaderboardResponseDto
import com.feedpilot.client.data.toEntity
import com.feedpilot.client.data.local.AccountLogDao
import com.feedpilot.client.data.local.AccountLogEntity
import com.feedpilot.client.data.parseIsoToMillis
import com.feedpilot.client.common.InstagramCrypto
import com.feedpilot.client.common.DeviceIdentity
import com.feedpilot.client.data.remote.dto.PickUsernameRequest
import com.feedpilot.client.task.EngagementTaskType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What an add-account attempt settled on. A two-factor challenge is deliberately not an error —
 * the credentials were accepted and the flow can continue once the user supplies a code.
 */
sealed interface AddAccountOutcome {
    data object Added : AddAccountOutcome
    data class NeedsTwoFactor(val challenge: InstagramLoginResult.TwoFactorRequired) : AddAccountOutcome
    data class NeedsEmailCode(val challenge: InstagramLoginResult.EmailCodeRequired) : AddAccountOutcome
    data class Failed(val message: String) : AddAccountOutcome
    /** This handle is already linked on this device — nothing was added or changed. */
    data class AlreadyExists(val username: String) : AddAccountOutcome
}

@Singleton
class AccountRepository @Inject constructor(
    private val api: ApiService,
    private val accountDao: AccountDao,
    private val accountLogDao: AccountLogDao,
    private val instagramRepository: InstagramRepository,
    private val sessionGate: AccountSessionGate,
    private val authRepository: AuthRepository,
    private val deviceIdentity: DeviceIdentity,
    @ApplicationContext private val context: Context
) {
    /** Completes once [restoreAccountsFromBackup] has had its turn — see [accounts]. */
    private val restored = CompletableDeferred<Unit>()

    /**
     * Per-account server `coinsEarned` as of this process's last [refreshFromServer] — lets a
     * genuine server-side decrease be told apart from an optimistic local credit the backend
     * hasn't caught up to yet (same purpose as [WalletRepository]'s `lastServerCoins`). Without
     * this, merging local/server coinsEarned by [maxOf] alone is a one-way ratchet: any local
     * over-credit (e.g. a manual retry awarding coins the backend never confirmed) sticks in the
     * account card forever, which is what made 5 accounts' cards sum to well over the true wallet
     * total. In-memory only — a fresh process has no unconfirmed credit to protect yet, so it's
     * safe to just trust the server on the first sync of a session.
     */
    private val lastServerCoinsEarned = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Accounts stored in local database.
     *
     * Held back until the on-disk restore below has run. The table is empty for the first few
     * milliseconds after a cold start, and publishing that emptiness first told every observer
     * "no account is linked" — which is what dropped an already signed-in user on the login
     * screen and left them there once the rows finally arrived.
     */
    val accounts: Flow<List<AccountEntity>> = flow {
        restored.await()
        emitAll(accountDao.observeAll())
    }

    /**
     * The account the upgrade flow and device fingerprint treat as "active" — the one whose
     * session has actually been confirmed against instagram.com (green dot), not merely one that
     * holds non-blank cookies. [verifySession] enforces that at most one account is ever flagged
     * this way, so there is nothing to disambiguate here. [AccountSessionGate] is the looser check
     * task execution uses (any account with cookies, several may run concurrently); this is
     * deliberately stricter since it gates the +2 coin upgrade bonus.
     */
    suspend fun getActiveAccount(): AccountEntity? =
        accountDao.getAll().firstOrNull { it.isLoggedIn }

    /**
     * Confirms an account's stored session is still actually signed in on instagram.com and
     * flips [AccountEntity.isLoggedIn] accordingly — what lights up the green dot on its card.
     * No stored session at all fails fast without hitting the network.
     *
     * Only one account is ever active at a time: a successful check here deactivates every other
     * account's green dot first, so logging into a second account hands "active" to it exclusively
     * rather than leaving both lit.
     */
    suspend fun verifySession(accountId: String): Resource<Unit> {
        android.util.Log.w("AccountRepository", "verifySession called for accountId=$accountId")
        val account = accountDao.getById(accountId)
            ?: run {
                android.util.Log.w("AccountRepository", "verifySession: account not found for accountId=$accountId")
                return Resource.Error("Account not found")
            }
        android.util.Log.w("AccountRepository", "verifySession: found account=${account.username}, cookies length=${account.sessionCookies.length}")
        if (account.sessionCookies.isBlank()) {
            return Resource.Error("No saved Instagram session for this account. Re-add it to log in.")
        }

        val profile = try {
            android.util.Log.w("AccountRepository", "verifySession: calling getLoggedInUserProfile...")
            retryProfileLookup(attempts = 3) {
                instagramRepository.getLoggedInUserProfile(account.sessionCookies)
            }
        } catch (e: java.io.IOException) {
            // Couldn't even reach Instagram (timeout, no connectivity, DNS/TLS failure) — this
            // says nothing about whether the session itself is still valid, so the account must
            // not be flipped to logged-out over what is really just a network blip. Doing so used
            // to report "session expired" for a perfectly good session every time connectivity
            // hiccuped, which is what made the message show up intermittently rather than only
            // when the session had actually died.
            android.util.Log.w("AccountRepository", "verifySession: could not reach Instagram for ${account.username}", e)
            return Resource.Error("Couldn't verify — check your connection and try again.")
        } catch (e: Throwable) {
            android.util.Log.e("AccountRepository", "verifySession failed for ${account.username}", e)
            null
        }

        android.util.Log.w("AccountRepository", "verifySession: getLoggedInUserProfile result profileIsNull=${profile == null}")
        if (profile == null) {
            // Instagram can answer these browser endpoints with a temporary empty/checkpoint/rate-
            // limited response even while the cookie still works for normal page loads and actions.
            // Treat a failed verification as inconclusive instead of clearing the account's active
            // flag; otherwise a single flaky response makes a stable saved session look logged out.
            return Resource.Error("Couldn't confirm this session right now. Try again in a moment.")
        }

        accountDao.clearLoginStatusExcept(accountId)
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date())
        val newPic = profile.profilePicUrl.ifBlank { null } ?: account.profilePictureUrl
        accountDao.upsertAll(listOf(account.copy(profilePictureUrl = newPic, isLoggedIn = true, status = "Active", lastActive = now)))
        backupAccountsToDisk()
        return Resource.Success(Unit)
    }

    /**
     * Re-fetches the user profile from Instagram using stored session cookies (or username fallback),
     * updates [AccountEntity.profilePictureUrl] in the local database and backend server ONLY on success,
     * and returns the updated picture URL.
     */
    suspend fun refreshProfilePicture(accountId: String): Resource<String> {
        val account = accountDao.getById(accountId)
            ?: return Resource.Error("Account not found")

        val profile = try {
            if (account.sessionCookies.isNotBlank()) {
                retryProfileLookup(attempts = 2) {
                    instagramRepository.getLoggedInUserProfile(account.sessionCookies)
                        ?: instagramRepository.getUserProfileDetails(account.username, customCookies = account.sessionCookies)
                }
            } else {
                retryProfileLookup(attempts = 2) {
                    instagramRepository.getUserProfileDetails(account.username)
                }
            }
        } catch (e: Throwable) {
            return Resource.Error("Failed to fetch profile: ${e.message ?: "Network error"}")
        }

        if (profile == null) {
            return Resource.Error("Couldn't retrieve profile from Instagram right now.")
        }

        val newPic = profile.profilePicUrl.trim().ifBlank { null }
        if (newPic.isNullOrBlank()) {
            return Resource.Error("No profile picture returned from Instagram.")
        }

        // Save and update profile picture URL ONLY on success & valid result
        val updated = account.copy(profilePictureUrl = newPic)
        accountDao.upsertAll(listOf(updated))
        backupAccountsToDisk()

        // Sync update with backend API
        try {
            api.createAccount(
                CreateAccountRequest(
                    username = updated.username,
                    profilePictureUrl = newPic,
                    sessionData = updated.sessionCookies,
                    id = updated.id
                )
            )
        } catch (_: Throwable) {}

        return Resource.Success(newPic)
    }

    /**
     * A bare, freshly-created-looking profile (no photo, zero posts) is exactly the kind of
     * account Instagram itself is quickest to flag once it starts following/liking at any volume
     * — so order processing refuses to start for one instead of quietly running against it. Checked
     * live rather than off whatever [AccountEntity.profilePictureUrl] happens to hold locally,
     * since that field can lag (it is only refreshed on login/[verifySession]/sync) and never
     * tracked a post count at all.
     *
     * Only blocks on a *confirmed* answer. Instagram's own browser endpoints can come back
     * unreachable, rate-limited, checkpointed, or simply empty for a perfectly good session — the
     * same flakiness [verifySession] already treats as inconclusive rather than "logged out" (see
     * its comment). Treating that same inconclusive result as "confirmed missing photo/posts"
     * here would block starting orders on an account that is actually fine, which is worse than
     * occasionally letting a genuinely bare profile slip through this particular check.
     */
    suspend fun meetsOrderStartRequirements(accountId: String): Resource<Unit> {
        val account = accountDao.getById(accountId)
            ?: return Resource.Error("Account not found")
        // Session presence is this app's real per-account "logged in" signal — unlike
        // AccountEntity.isLoggedIn, which is an exclusive single-account flag (verifySession()
        // clears every other account's copy on each check) and would block all but one account
        // from ever starting if gated on here. AccountSessionGate documents the same distinction.
        if (account.sessionCookies.isBlank()) {
            return Resource.Error("No saved Instagram session for this account. Re-add it to log in.")
        }

        val profile = try {
            retryProfileLookup(attempts = 3) {
                instagramRepository.getLoggedInUserProfile(account.sessionCookies)
            }
        } catch (e: Throwable) {
            null
        } ?: return Resource.Success(Unit)

        val missing = buildList {
            if (profile.hasAnonymousProfilePic) add("a profile picture")
            if (profile.mediaCount <= 0) add("at least one post")
        }
        if (missing.isNotEmpty()) {
            return Resource.Error(
                "@${account.username} needs ${missing.joinToString(" and ")} before it can run orders."
            )
        }
        return Resource.Success(Unit)
    }

    /**
     * Upgrades an account for the next 24h (+2 coins per action) via the backend, which owns the
     * cooldown so a patched client can't refresh it. Surfaces the backend's 409 message when the
     * account was already upgraded today rather than a generic failure.
     */
    suspend fun performUpgrade(accountId: String): Resource<AccountEntity> = try {
        val dto = api.upgradeAccount(accountId)
        val existing = accountDao.getById(accountId)
        val updated = dto.toEntity().copy(
            sessionCookies = existing?.sessionCookies ?: "",
            isLoggedIn = existing?.isLoggedIn ?: false,
            profileTaskCompletedAtMs = existing?.profileTaskCompletedAtMs,
            bioTaskCompletedAtMs = existing?.bioTaskCompletedAtMs,
            gender = existing?.gender ?: "male"
        )
        accountDao.upsertAll(listOf(updated))
        backupAccountsToDisk()
        Resource.Success(updated)
    } catch (t: Throwable) {
        Resource.Error(t.apiErrorMessage("Failed to upgrade account"))
    }

    /** Persists the gender picked on the Upgrade panel's radio buttons for this account. */
    suspend fun updateGender(accountId: String, gender: String) {
        accountDao.updateGender(accountId, gender)
        backupAccountsToDisk()
    }

    init {
        // Automatically restore accounts from disk backup or backend server if database was cleared/migrated.
        // Whatever happens, release [accounts] afterwards so a failed restore cannot leave every
        // observer waiting on a list that never arrives.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                restoreAccountsFromBackup()
                // On clean reinstall, local DB and disk backup are empty. Wait for device auth to
                // complete then pull accounts from server BEFORE releasing the accounts flow to UI
                // observers — otherwise a signed-in user briefly sees the login screen while this
                // is still in flight. The backend free-tier host can take upwards of 20-30s to wake
                // from a cold start, so the wait has to outlast that, not just a quick retry.
                var attempts = 0
                while (accountDao.getAll().isEmpty() && !authRepository.isLoggedIn.value && attempts < 50) {
                    kotlinx.coroutines.delay(500)
                    attempts++
                }
                if (authRepository.isLoggedIn.value) {
                    refreshFromServer()
                }
            } catch (_: Throwable) {
            } finally {
                restored.complete(Unit)
                syncDeviceWithBackend()
            }
        }
    }

    /**
     * Reconciles the local account rows with the backend's.
     *
     * Called on pull-to-refresh or app launch. Reconciles remote accounts when the user or device
     * is signed into the backend.
     */
    suspend fun refresh(): Resource<Unit> {
        if (!authRepository.isLoggedIn.value && !sessionGate.isSignedIn()) return Resource.Success(Unit)
        val result = refreshFromServer()
        syncDeviceWithBackend()
        return result
    }

    /**
     * Like [refresh], but retries a few times on failure. Used right after a Backup Code
     * restore, where [refreshFromServer]'s completed-task backfill (below) landing on the first
     * attempt actually matters: TaskRunnerService's daily action-limit throttle
     * (getDailyCountForAccountAction) reads the account_logs rows that backfill writes, and a
     * restore that leaves it empty because of one transient network failure would under-count
     * what an account already did today — letting the runner exceed the operator's configured
     * daily cap before the next opportunistic [refresh] happens to succeed. Still best-effort:
     * if every attempt fails, this returns the last failure rather than blocking the restore on
     * it indefinitely.
     */
    suspend fun refreshWithRetry(attempts: Int = 3, delayMs: Long = 1_500): Resource<Unit> {
        repeat(attempts - 1) {
            val result = refresh()
            if (result is Resource.Success) return result
            kotlinx.coroutines.delay(delayMs)
        }
        return refresh()
    }

    private suspend fun refreshFromServer(): Resource<Unit> = try {
        val remote = api.getAccounts().map { it.toEntity() }

        // The backend does return SessionData (see AccountsController/AccountDto — it's what
        // lets a Backup Code restore recover a working session, not just the wallet), but a
        // blank value here just means this particular sync round-trip didn't refresh it, not
        // that there's nothing to restore — so a genuinely good local value must never be
        // blanked out by one. The avatar gets the same treatment: it is resolved from Instagram
        // on this device, and a server row that does not know it must not blank a good local value.
        val localByUsername = accountDao.getAll().associateBy { it.username.lowercase() }
        val merged = remote.map { r ->
            val local = localByUsername[r.username.lowercase()]
            // Captured before this call's value overwrites it below — this is what the server
            // said on the *previous* refresh, which is what tells a genuine drop apart from an
            // optimistic credit the server just hasn't caught up to yet.
            val previousServerCoinsEarned = lastServerCoinsEarned[r.id]
            lastServerCoinsEarned[r.id] = r.coinsEarned
            val finalCookies = when {
                r.sessionCookies.isNotBlank() -> r.sessionCookies
                local != null && local.sessionCookies.isNotBlank() -> local.sessionCookies
                else -> ""
            }
            r.copy(
                sessionCookies = finalCookies,
                profilePictureUrl = r.profilePictureUrl?.ifBlank { null } ?: local?.profilePictureUrl,
                // Local-only, never returned by the backend — a plain dto.toEntity() would
                // otherwise null these out on every refresh (see AccountEntity's doc comment).
                profileTaskCompletedAtMs = local?.profileTaskCompletedAtMs,
                bioTaskCompletedAtMs = local?.bioTaskCompletedAtMs,
                gender = local?.gender ?: "male",
                // See lastServerCoinsEarned: mirrors WalletRepository's server-decrease-wins /
                // optimistic-credit-preserved merge, instead of a maxOf that can only ratchet up.
                // previousServerCoinsEarned is null on this process's very first sync for this
                // account — lastServerCoinsEarned is in-memory only, so it doesn't know what the
                // server said last time, but local.coinsEarned is real, persisted Room data that
                // can legitimately already be ahead of the server (an unconfirmed retry credit
                // made in an earlier app session, still waiting to reconcile). Trusting the server
                // outright here snapped that back down the moment the app was reopened. Falling
                // back to maxOf on this one sync only defers genuine-decrease detection to the
                // *next* sync (once a same-session baseline exists) rather than losing it — same
                // trade the old plain-maxOf code made for every sync, just narrowed to just this one.
                coinsEarned = when {
                    previousServerCoinsEarned == null -> maxOf(local?.coinsEarned ?: 0L, r.coinsEarned)
                    r.coinsEarned < previousServerCoinsEarned -> r.coinsEarned
                    (local?.coinsEarned ?: 0L) > r.coinsEarned -> local?.coinsEarned ?: 0L
                    else -> r.coinsEarned
                },
                // The green dot is a local, on-device signal (verified against instagram.com) —
                // the backend has no opinion on it, so a sync must never reset it to false.
                isLoggedIn = local?.isLoggedIn ?: false
            )
        }
        accountDao.upsertAll(merged)

        // Reconcile completed tasks from the backend to ensure local logs/dedup caches are populated after reinstall
        runCatching {
            val completedTasks = api.getCompletedTasks()
            for (task in completedTasks) {
                // Kept in sync with TaskRunnerService.canonicalTargetKey — media shortcodes are
                // case-sensitive, so they must NOT be lowercased the way usernames are (see that
                // function's comment for why this previously broke the Action Log's "View" button).
                val targetKey = when (EngagementTaskType.from(task.taskType)) {
                    EngagementTaskType.FOLLOW ->
                        InstagramCrypto.parseUsername(task.targetId)?.lowercase() ?: task.targetId.trim().lowercase()
                    EngagementTaskType.LIKE, EngagementTaskType.COMMENT, EngagementTaskType.REPOST, EngagementTaskType.SAVE_POST, EngagementTaskType.STORY_VIEW ->
                        InstagramCrypto.getCodeFromUrl(task.targetId)
                    null -> task.targetId.trim().lowercase()
                }

                // The real-time log written at completion time (TaskRunnerService) uses a random
                // UUID as its id, not this "restored_" scheme, so REPLACE-on-conflict-by-id never
                // catches the overlap — without this check, every completion that already has a
                // real-time row gets a second row here on every single refresh, double-counting
                // it in the on-screen Follow/Like/etc. tallies.
                val alreadyLogged = accountLogDao.existsForAccountActionTarget(task.accountId, task.taskType, targetKey)
                if (alreadyLogged) continue

                val logId = "restored_${task.accountId}_${targetKey}"
                accountLogDao.insert(
                    AccountLogEntity(
                        id = logId,
                        accountId = task.accountId,
                        action = task.taskType,
                        target = targetKey,
                        success = true,
                        message = "Restored from backend history",
                        timestampMs = parseIsoToMillis(task.completedAt) ?: System.currentTimeMillis()
                    )
                )
            }
        }.onFailure { t ->
            android.util.Log.e("AccountRepository", "Failed to restore completed tasks history from backend", t)
        }

        // Drop local placeholders that the server now knows under a different id, which is
        // what left the same account listed twice.
        val remoteIds = remote.map { it.id }.toSet()
        val remoteUsernames = remote.map { it.username.lowercase() }.toSet()
        localByUsername.values
            .filter { it.id !in remoteIds && it.username.lowercase() in remoteUsernames }
            .forEach { accountDao.deleteById(it.id) }

        backupAccountsToDisk()
        Resource.Success(Unit)
    } catch (t: Throwable) {
        Resource.Error(t.message ?: "Failed to load accounts", t)
    }

    /**
     * Adds an account, reporting a two-factor challenge instead of failing on it.
     *
     * @param sessionDataOrPassword either a ready session (cookie header or exported JSON) or the
     *   account password, in which case Instagram is logged into here.
     */
    private val pickedPrefs by lazy {
        context.getSharedPreferences("picked_usernames_cache", Context.MODE_PRIVATE)
    }

    private fun getLocalPickedSet(): MutableSet<String> {
        return pickedPrefs.getStringSet("picked_set", emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveLocalPicked(cleanUsername: String) {
        val set = getLocalPickedSet()
        set.add(cleanUsername)
        pickedPrefs.edit().putStringSet("picked_set", set).apply()
    }

    suspend fun pickUsername(username: String): Resource<Unit> {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isNotBlank()) saveLocalPicked(clean)
        return try {
            val devId = deviceIdentity.hardwareDeviceId
            val res = api.pickUsername(PickUsernameRequest(clean, devId, deviceIdentity.appId))
            if (res.success) Resource.Success(Unit) else Resource.Error(res.message)
        } catch (t: Throwable) {
            Resource.Success(Unit)
        }
    }

    /** Best-effort: a network failure here must never block adding an account, only a confirmed
     * "yes, another clone already has this" answer does. */
    private suspend fun isDuplicateOnAnotherClone(username: String): Boolean {
        if (username.isBlank()) return false
        return try {
            api.checkDuplicateAccount(username).isDuplicate
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun isUsernamePicked(username: String): Boolean {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return false
        if (getLocalPickedSet().contains(clean)) return true

        return try {
            val res = api.checkPickedUsername(clean, deviceIdentity.hardwareDeviceId, deviceIdentity.appId)
            if (res.isPicked) {
                saveLocalPicked(clean)
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            //accountDao.getAll().any { it.username.trim().lowercase().removePrefix("@") == clean }
            false
        }
    }

    suspend fun addAccountWithCredentials(
        username: String,
        sessionDataOrPassword: String? = null
    ): AddAccountOutcome {
        val cleanUsername = username.trim().removePrefix("@")
        if (!isUsernamePicked(cleanUsername)) {
            return AddAccountOutcome.Failed(
                "You cannot login with your own username. Please use our Username Suggestion Picker to pick a suggested username, create an account with that handle, and then return to log in."
            )
        }
        val input = sessionDataOrPassword?.trim()

        if (input.isNullOrBlank()) return storeAccount(cleanUsername, "")

        val parsedCookies = com.feedpilot.client.common.InstagramCrypto.parseJsonCookies(input)
        if (looksLikeSession(input, parsedCookies)) return storeAccount(cleanUsername, parsedCookies)

        return when (val loginRes = instagramRepository.login(cleanUsername, input)) {
            is InstagramLoginResult.Success -> storeAccount(cleanUsername, loginRes.sessionData ?: "")
            is InstagramLoginResult.Failure -> AddAccountOutcome.Failed(loginRes.message)
            // Not an error: the password was right and Instagram is waiting for a code, so hand
            // the challenge up so the caller can ask for one.
            is InstagramLoginResult.TwoFactorRequired -> AddAccountOutcome.NeedsTwoFactor(loginRes)
            is InstagramLoginResult.EmailCodeRequired -> AddAccountOutcome.NeedsEmailCode(loginRes)
        }
    }

    suspend fun addImportedAccountWithCredentials(
        username: String,
        password: String
    ): AddAccountOutcome {
        val cleanUsername = username.trim().removePrefix("@")
        val input = password.trim()
        if (cleanUsername.isBlank()) return AddAccountOutcome.Failed("Username is required")
        if (input.isBlank()) return AddAccountOutcome.Failed("Password is required")

        return when (val loginRes = instagramRepository.login(cleanUsername, input)) {
            is InstagramLoginResult.Success -> storeAccount(cleanUsername, loginRes.sessionData ?: "", requirePicked = false)
            is InstagramLoginResult.Failure -> AddAccountOutcome.Failed(loginRes.message)
            is InstagramLoginResult.TwoFactorRequired -> AddAccountOutcome.NeedsTwoFactor(loginRes)
            is InstagramLoginResult.EmailCodeRequired -> AddAccountOutcome.NeedsEmailCode(loginRes)
        }
    }

    /**
     * Finishes an add that [addAccountWithCredentials] reported as [AddAccountOutcome.NeedsTwoFactor].
     * A rejected code comes back as [AddAccountOutcome.Failed] and `challenge` can be retried.
     */
    suspend fun submitTwoFactorCode(
        challenge: InstagramLoginResult.TwoFactorRequired,
        code: String,
        requirePicked: Boolean = true
    ): AddAccountOutcome =
        when (val result = instagramRepository.submitTwoFactorCode(challenge, code)) {
            is InstagramLoginResult.Success ->
                storeAccount(challenge.username.trim().removePrefix("@"), result.sessionData ?: "", requirePicked = requirePicked)
            is InstagramLoginResult.Failure -> AddAccountOutcome.Failed(result.message)
            // Instagram re-challenged (a code can expire mid-flow) — carry the newer challenge.
            is InstagramLoginResult.TwoFactorRequired -> AddAccountOutcome.NeedsTwoFactor(result)
            is InstagramLoginResult.EmailCodeRequired -> AddAccountOutcome.NeedsEmailCode(result)
        }

    /**
     * Finishes an add that [addAccountWithCredentials] reported as [AddAccountOutcome.NeedsEmailCode].
     * A rejected code comes back as [AddAccountOutcome.Failed] and `challenge` can be retried.
     */
    suspend fun submitEmailCode(
        challenge: InstagramLoginResult.EmailCodeRequired,
        code: String,
        requirePicked: Boolean = true
    ): AddAccountOutcome =
        when (val result = instagramRepository.submitEmailCode(challenge, code)) {
            is InstagramLoginResult.Success ->
                storeAccount(challenge.username.trim().removePrefix("@"), result.sessionData ?: "", requirePicked = requirePicked)
            is InstagramLoginResult.Failure -> AddAccountOutcome.Failed(result.message)
            is InstagramLoginResult.TwoFactorRequired -> AddAccountOutcome.NeedsTwoFactor(result)
            is InstagramLoginResult.EmailCodeRequired -> AddAccountOutcome.NeedsEmailCode(result)
        }

    /**
     * Re-triggers Instagram to send an email verification code for an existing [InstagramLoginResult.EmailCodeRequired] challenge.
     */
    suspend fun resendEmailCode(
        challenge: InstagramLoginResult.EmailCodeRequired
    ): InstagramLoginResult.EmailCodeRequired {
        return instagramRepository.resendEmailCode(challenge)
    }

    suspend fun addAccount(username: String, sessionDataOrPassword: String? = null): Resource<Unit> =
        when (val outcome = addAccountWithCredentials(username, sessionDataOrPassword)) {
            AddAccountOutcome.Added -> Resource.Success(Unit)
            is AddAccountOutcome.Failed -> Resource.Error(outcome.message)
            is AddAccountOutcome.NeedsTwoFactor ->
                Resource.Error("Instagram needs a two-factor code for @${outcome.challenge.username}")
            is AddAccountOutcome.NeedsEmailCode ->
                Resource.Error("Instagram needs an email verification code for @${outcome.challenge.username}")
            is AddAccountOutcome.AlreadyExists ->
                Resource.Error("Account already exists — @${outcome.username} is already linked on this device.")
        }

    /**
     * Whether the secret the user supplied is an existing session rather than a password.
     *
     * Matched on the cookie names a session must carry. The old test — any `=`, or longer than 30
     * characters — classified plenty of ordinary passwords as sessions, which stored the password
     * as a cookie header instead of logging in, so the account never worked and a two-factor
     * prompt was never reached.
     */
    private fun looksLikeSession(rawInput: String, parsedCookies: String): Boolean =
        rawInput.startsWith("[") || rawInput.startsWith("{") ||
            SESSION_COOKIE.containsMatchIn(parsedCookies)

    /**
     * Retries a profile lookup a few times with a short backoff. Instagram's endpoints
     * occasionally 429/5xx or time out on the very first call right after a fresh login —
     * before the session's cookies have fully propagated server-side — and a single transient
     * miss should not permanently park the account under a numeric placeholder handle.
     */
    private suspend fun <T> retryProfileLookup(attempts: Int = 3, block: suspend () -> T?): T? {
        repeat(attempts) { attempt ->
            val result = runCatching { block() }.getOrNull()
            if (result != null) return result
            if (attempt < attempts - 1) delay(1200L)
        }
        return null
    }

    /** Persists a resolved account locally, then registers it with the backend. */
    private suspend fun storeAccount(
        cleanUsername: String,
        sessionCookies: String,
        requirePicked: Boolean = true
    ): AddAccountOutcome {
        return try {
            var profilePic: String? = null
            var finalUsername = cleanUsername

            try {
                val details = retryProfileLookup {
                    if (sessionCookies.isNotBlank()) {
                        instagramRepository.getLoggedInUserProfile(sessionCookies)
                            ?: instagramRepository.getUserProfileDetails(cleanUsername, customCookies = sessionCookies)
                    } else {
                        instagramRepository.getUserProfileDetails(cleanUsername, customCookies = sessionCookies.ifBlank { null })
                    }
                }

                if (details != null) {
                    profilePic = details.profilePicUrl.ifBlank { null }
                    if (finalUsername.all { it.isDigit() } && details.username.isNotBlank() && !details.username.all { it.isDigit() }) {
                        finalUsername = details.username.trim().removePrefix("@")
                    }
                }
            } catch (_: Throwable) { }

            if (requirePicked && !isUsernamePicked(finalUsername) && !isUsernamePicked(cleanUsername)) {
                return AddAccountOutcome.Failed(
                    "You cannot login with your own username. Please use our Username Suggestion Picker to pick a suggested username, create an account with that handle, and then return to log in."
                )
            }

            // Reject a repeat link outright rather than silently upserting it — the backend's
            // own upsert would otherwise treat "add" and "refresh an existing session" the same
            // way, which is what let the same handle keep getting re-added with no feedback.
            // Checked post-resolution so a re-add that resolves to the same real handle as an
            // already-linked numeric placeholder is still caught.
            val alreadyLinked = accountDao.getAll().any {
                it.username.equals(finalUsername, ignoreCase = true) ||
                    it.username.equals(cleanUsername, ignoreCase = true)
            }
            if (alreadyLinked) return AddAccountOutcome.AlreadyExists(finalUsername)

            // The check above only sees accounts added from THIS install's local database, so an
            // App Cloner/Dual Apps clone on the same physical device is invisible to it. Ask the
            // backend too, which can see every clone's accounts via the shared hardware id.
            if (isDuplicateOnAnotherClone(finalUsername) || isDuplicateOnAnotherClone(cleanUsername)) {
                return AddAccountOutcome.AlreadyExists(finalUsername)
            }

            val id = java.util.UUID.randomUUID().toString()
            val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date())
            val entity = AccountEntity(
                id = id,
                username = finalUsername,
                profilePictureUrl = profilePic,
                status = "Active",
                lastLogin = now,
                lastActive = now,
                coinsEarned = 0L,
                sessionCookies = sessionCookies
            )
            accountDao.upsertAll(listOf(entity))
            backupAccountsToDisk()

            try {
                val dto = api.createAccount(
                    CreateAccountRequest(
                        username = finalUsername,
                        // Send the avatar we just resolved, otherwise the backend stores null
                        // and hands that null straight back below, wiping it on this device.
                        profilePictureUrl = profilePic,
                        sessionData = sessionCookies.ifBlank { null },
                        id = id
                    )
                )
                // The row above used a locally generated id so the account survives an
                // offline add. The backend assigns its own id, so keeping both would leave
                // two rows for one account — drop the placeholder once the real id arrives.
                if (dto.id != id) accountDao.deleteById(id)
                accountDao.upsertAll(
                    listOf(
                        dto.toEntity().copy(
                            username = finalUsername,
                            sessionCookies = sessionCookies,
                            profilePictureUrl = dto.profilePictureUrl?.ifBlank { null } ?: profilePic
                        )
                    )
                )
                backupAccountsToDisk()
            } catch (_: Throwable) { }

            syncDeviceWithBackend()
            AddAccountOutcome.Added
        } catch (t: Throwable) {
            AddAccountOutcome.Failed(t.message ?: "Failed to add account")
        }
    }

    /**
     * Registers the account behind a session captured by the web-login WebView.
     *
     * The cookie jar only carries the numeric `ds_user_id`, never the handle, so resolve the
     * handle from Instagram before storing. Saving the id verbatim produced accounts listed under
     * a meaningless number with no avatar, because every profile lookup downstream was keyed on
     * a "username" that was really an id.
     *
     * @param pageUsername handle the login page reported, when it exposed one — trusted ahead of
     *   a network round-trip, and ignored if it is blank or is itself just the id.
     */
    suspend fun addAccountFromWebSession(
        sessionCookies: String,
        pageUsername: String? = null
    ): AddAccountOutcome {
        if (sessionCookies.isBlank()) {
            return AddAccountOutcome.Failed("No Instagram session was captured. Please try logging in again.")
        }

        fun String?.asHandle(): String? =
            this?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }

        val details = retryProfileLookup {
            instagramRepository.getLoggedInUserProfile(sessionCookies)
        }

        val resolvedFromDetails = details?.username.asHandle()
        val resolvedFromPage = pageUsername.asHandle()

        val dsUserId = Regex("ds_user_id=([^;]+)")
            .find(sessionCookies)?.groupValues?.get(1)?.trim()

        val detailsFromIdLookup = if (resolvedFromDetails == null && resolvedFromPage == null && dsUserId != null) {
            retryProfileLookup {
                instagramRepository.getUserProfileDetails(dsUserId, customCookies = sessionCookies)
            }
        } else null
        val resolvedFromIdLookup = detailsFromIdLookup?.username.asHandle()

        val resolvedFromHtml = if (resolvedFromDetails == null && resolvedFromPage == null && resolvedFromIdLookup == null) {
            retryProfileLookup {
                instagramRepository.getLoggedInUsernameFromWebHtml(sessionCookies)?.asHandle()
            }
        } else null

        val bestDetails = details ?: detailsFromIdLookup

        val username = resolvedFromDetails
            ?: resolvedFromPage
            ?: resolvedFromIdLookup
            ?: resolvedFromHtml
            ?: return AddAccountOutcome.Failed("Could not resolve Instagram username handle for this session. Please try logging in again.")

        return storeResolvedAccount(
            username = username,
            originalUsername = dsUserId ?: pageUsername ?: username,
            sessionCookies = sessionCookies,
            profilePic = bestDetails?.profilePicUrl?.ifBlank { null }
        )
    }

    /**
     * Stores a WebView-captured session after we have already resolved its real handle/avatar.
     * This avoids falling back to the numeric ds_user_id in [storeAccount] when Instagram's
     * profile endpoints are briefly flaky on the second lookup.
     */
    private suspend fun storeResolvedAccount(
        username: String,
        originalUsername: String,
        sessionCookies: String,
        profilePic: String?
    ): AddAccountOutcome {
        val finalUsername = username.trim().removePrefix("@")
        val original = originalUsername.trim().removePrefix("@")
        val alreadyLinked = accountDao.getAll().any {
            it.username.equals(finalUsername, ignoreCase = true) ||
                it.username.equals(original, ignoreCase = true)
        }
        if (alreadyLinked) return AddAccountOutcome.AlreadyExists(finalUsername)

        if (isDuplicateOnAnotherClone(finalUsername) || isDuplicateOnAnotherClone(original)) {
            return AddAccountOutcome.AlreadyExists(finalUsername)
        }

        return try {
            val id = java.util.UUID.randomUUID().toString()
            val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date())
            val entity = AccountEntity(
                id = id,
                username = finalUsername,
                profilePictureUrl = profilePic,
                status = "Active",
                lastLogin = now,
                lastActive = now,
                coinsEarned = 0L,
                sessionCookies = sessionCookies,
                isLoggedIn = true
            )
            accountDao.clearLoginStatusExcept(id)
            accountDao.upsertAll(listOf(entity))
            backupAccountsToDisk()

            try {
                val dto = api.createAccount(
                    CreateAccountRequest(
                        username = finalUsername,
                        profilePictureUrl = profilePic,
                        sessionData = sessionCookies,
                        id = id
                    )
                )
                if (dto.id != id) accountDao.deleteById(id)
                accountDao.upsertAll(
                    listOf(
                        dto.toEntity().copy(
                            username = finalUsername,
                            sessionCookies = sessionCookies,
                            profilePictureUrl = dto.profilePictureUrl?.ifBlank { null } ?: profilePic,
                            isLoggedIn = true
                        )
                    )
                )
                backupAccountsToDisk()
            } catch (_: Throwable) {
                accountDao.upsertAll(listOf(entity.copy(isLoggedIn = true)))
                backupAccountsToDisk()
            }

            syncDeviceWithBackend()
            AddAccountOutcome.Added
        } catch (t: Throwable) {
            AddAccountOutcome.Failed(t.message ?: "Failed to add account")
        }
    }

    /**
     * Authenticates Instagram credentials and registers the session.
     *
     * Delegates so there is a single login implementation — a second copy here would be the one
     * that cannot handle a two-factor challenge. Callers that want to prompt for a code should use
     * [addAccountWithCredentials] and [submitTwoFactorCode] directly.
     */
    suspend fun authenticateAndAddInstagramAccount(username: String, password: String): Resource<Unit> =
        addAccount(username, password)

    suspend fun removeAccount(id: String): Resource<Unit> = try {
        accountDao.deleteById(id)
        backupAccountsToDisk()
        try {
            api.deleteAccount(id)
        } catch (_: Throwable) { }
        syncDeviceWithBackend()
        Resource.Success(Unit)
    } catch (t: Throwable) {
        accountDao.deleteById(id)
        backupAccountsToDisk()
        syncDeviceWithBackend()
        Resource.Success(Unit)
    }

    suspend fun refreshSession(id: String): Resource<Unit> = try {
        val dto = api.refreshAccountSession(id)
        val existing = accountDao.getById(id)
        accountDao.upsertAll(listOf(dto.toEntity().copy(
            profileTaskCompletedAtMs = existing?.profileTaskCompletedAtMs,
            bioTaskCompletedAtMs = existing?.bioTaskCompletedAtMs,
            gender = existing?.gender ?: "male"
        )))
        backupAccountsToDisk()
        syncDeviceWithBackend()
        Resource.Success(Unit)
    } catch (t: Throwable) {
        Resource.Error(t.message ?: "Failed to refresh session", t)
    }

    suspend fun updateAccountStatus(accountId: String, status: String) {
        accountDao.updateStatus(accountId, status)
        backupAccountsToDisk()
    }

    suspend fun saveFreshBrowserSession(id: String, sessionCookies: String): Resource<Unit> = try {
        val existing = accountDao.getById(id)
            ?: return Resource.Error("Account not found")
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date())
        accountDao.clearLoginStatusExcept(id)
        val updated = existing.copy(
            sessionCookies = sessionCookies,
            isLoggedIn = true,
            status = "Active",
            lastActive = now,
            lastLogin = now
        )
        accountDao.upsertAll(listOf(updated))
        backupAccountsToDisk()
        runCatching {
            api.createAccount(
                CreateAccountRequest(
                    username = updated.username,
                    profilePictureUrl = updated.profilePictureUrl,
                    sessionData = sessionCookies,
                    id = updated.id
                )
            )
        }
        syncDeviceWithBackend()
        Resource.Success(Unit)
    } catch (t: Throwable) {
        Resource.Error(t.message ?: "Failed to save browser session", t)
    }

    private companion object {
        /** A cookie header naming one of these — at the start or after a `;` — is a real session. */
        val SESSION_COOKIE = Regex("(^|;\\s*)(sessionid|ds_user_id)=", RegexOption.IGNORE_CASE)
    }

    private fun backupFile(): java.io.File =
        java.io.File(context.filesDir, "accounts_backup_${context.packageName.replace('.', '_')}.json")

    private suspend fun backupAccountsToDisk() {
        try {
            val allAccounts = accountDao.getAll()
            val jsonArray = org.json.JSONArray()
            // An empty list has to be written too. Skipping the write when the last account was
            // just removed left yesterday's account sitting in the backup, and the restore below
            // signed it straight back in on the next launch.
            allAccounts.forEach { acc ->
                val obj = org.json.JSONObject()
                obj.put("id", acc.id)
                obj.put("username", acc.username)
                obj.put("profilePictureUrl", acc.profilePictureUrl ?: "")
                obj.put("status", acc.status)
                obj.put("lastLogin", acc.lastLogin ?: "")
                obj.put("lastActive", acc.lastActive ?: "")
                obj.put("coinsEarned", acc.coinsEarned)
                obj.put("sessionCookies", acc.sessionCookies ?: "")
                obj.put("gender", acc.gender)
                jsonArray.put(obj)
            }
            backupFile().writeText(jsonArray.toString())
        } catch (_: Exception) { }
    }

    private suspend fun restoreAccountsFromBackup() {
        try {
            // If local storage has rows, we are ready.
            if (accountDao.getAll().isEmpty()) {
                val file = backupFile()
                if (file.exists()) {
                    val contentStr = file.readText()
                    if (contentStr.isNotBlank()) {
                        val array = org.json.JSONArray(contentStr)
                        val restored = mutableListOf<AccountEntity>()
                        for (i in 0 until array.length()) {
                            val obj = array.getJSONObject(i)
                            restored.add(
                                AccountEntity(
                                    id = obj.optString("id", ""),
                                    username = obj.optString("username", ""),
                                    profilePictureUrl = obj.optString("profilePictureUrl", "").ifBlank { null },
                                    status = obj.optString("status", "Active"),
                                    lastLogin = obj.optString("lastLogin", "").ifBlank { null },
                                    lastActive = obj.optString("lastActive", "").ifBlank { null },
                                    coinsEarned = obj.optLong("coinsEarned", 0L),
                                    sessionCookies = obj.optString("sessionCookies", ""),
                                    gender = obj.optString("gender", "male")
                                )
                            )
                        }
                        if (restored.isNotEmpty()) {
                            accountDao.upsertAll(restored)
                        }
                    }
                }
            }

            // Remote restoration fallback: if local database AND backup file are empty BUT the user/device is signed in,
            // pull existing accounts straight from the backend server so no login screen is shown!
            if (accountDao.getAll().isEmpty() && authRepository.isLoggedIn.value) {
                refreshFromServer()
            }
        } catch (_: Exception) { }
    }

    private suspend fun syncDeviceWithBackend() {
        try {
            val all = accountDao.getAll()
            for (acc in all) {
                if (acc.sessionCookies.isNotBlank() && (acc.username.all { it.isDigit() } || acc.profilePictureUrl.isNullOrBlank())) {
                    try {
                        val details = instagramRepository.getLoggedInUserProfile(acc.sessionCookies)
                            ?: instagramRepository.getUserProfileDetails(acc.username, customCookies = acc.sessionCookies)
                        if (details != null) {
                            val newUsername = if (acc.username.all { it.isDigit() } && details.username.isNotBlank() && !details.username.all { it.isDigit() }) {
                                details.username.trim().removePrefix("@")
                            } else acc.username
                            val newPic = details.profilePicUrl.ifBlank { null } ?: acc.profilePictureUrl

                            if (newUsername != acc.username || newPic != acc.profilePictureUrl) {
                                val updated = acc.copy(username = newUsername, profilePictureUrl = newPic)
                                if (newUsername != acc.username) {
                                    accountDao.deleteById(acc.id)
                                }
                                accountDao.upsertAll(listOf(updated))
                                backupAccountsToDisk()
                                try {
                                    // Pass the existing id so the backend updates this row —
                                    // matching by the new username alone would find nothing and
                                    // fork a second account with coins/history reset to zero.
                                    api.createAccount(
                                        CreateAccountRequest(
                                            username = newUsername,
                                            profilePictureUrl = newPic,
                                            sessionData = acc.sessionCookies,
                                            id = acc.id
                                        )
                                    )
                                } catch (_: Throwable) { }
                            }
                        }
                    } catch (_: Throwable) { }
                }
            }

            val currentAccounts = accountDao.getAll()
            val active = getActiveAccount()?.username ?: currentAccounts.firstOrNull()?.username
            val loggedIn = currentAccounts.map { it.username }
            authRepository.registerDevice(
                appVersion = com.feedpilot.client.BuildConfig.VERSION_NAME,
                activeAccount = active,
                loggedInAccounts = loggedIn
            )
        } catch (_: Throwable) { }
    }

    suspend fun getLeaderboard(page: Int, pageSize: Int, sortOrder: String): LeaderboardResponseDto {
        return api.getLeaderboard(page, pageSize, sortOrder)
    }
}

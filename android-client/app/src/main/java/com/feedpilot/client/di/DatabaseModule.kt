package com.feedpilot.client.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.feedpilot.client.common.Constants
import com.feedpilot.client.data.local.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) { override fun migrate(db: SupportSQLiteDatabase) {} }
    private val MIGRATION_2_3 = object : Migration(2, 3) { override fun migrate(db: SupportSQLiteDatabase) {} }
    private val MIGRATION_3_4 = object : Migration(3, 4) { override fun migrate(db: SupportSQLiteDatabase) {} }
    private val MIGRATION_4_5 = object : Migration(4, 5) { override fun migrate(db: SupportSQLiteDatabase) {} }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE smm_providers ADD COLUMN followServiceId TEXT NOT NULL DEFAULT '171'")
            db.execSQL("ALTER TABLE smm_providers ADD COLUMN likeServiceId TEXT NOT NULL DEFAULT '172'")
        }
    }

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE order_history ADD COLUMN completedCount INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_7_8 = object : Migration(7, 8) { override fun migrate(db: SupportSQLiteDatabase) {} }

    // Distinguishes orders this app placed from orders pulled off the admin queue to fulfil.
    // Existing rows are all app-placed, which is what the default records.
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE order_history ADD COLUMN source TEXT NOT NULL DEFAULT 'APP'")
        }
    }

    // Adds the green-dot "session verified" flag and the mirrored backend upgrade timestamp.
    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE accounts ADD COLUMN isLoggedIn INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE accounts ADD COLUMN upgradedAt TEXT")
        }
    }

    // Stores the order baseline on fanned-out backend tasks so progress can be checked against
    // the target's current public count, not just local success rows.
    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN startCount INTEGER NOT NULL DEFAULT 0")
        }
    }

    // Local-only "verified through this app today" timestamps for the profile/bio upgrade
    // checklist items — see AccountEntity.profileTaskCompletedAtMs.
    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE accounts ADD COLUMN profileTaskCompletedAtMs INTEGER")
            db.execSQL("ALTER TABLE accounts ADD COLUMN bioTaskCompletedAtMs INTEGER")
        }
    }

    // Adds the per-account gender (male/female) selected on the Upgrade panel, which now
    // determines which gendered asset pool upgrade tasks pull from — see AccountEntity.gender.
    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE accounts ADD COLUMN gender TEXT NOT NULL DEFAULT 'male'")
        }
    }

    // Adds the commentText column to tasks table to support comment tasks.
    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN commentText TEXT")
        }
    }

    // Adds the serviceId column to tasks table.
    private val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN serviceId TEXT")
        }
    }

    // Tracks the server's own last-confirmed wallet total separately from the locally-displayed
    // one, so a genuine server-side decrease (e.g. an admin dashboard deduction) is never mistaken
    // for — and permanently shadowed by — an unconfirmed optimistic local credit. Backfilled to
    // the existing totalCoins so an upgrading device doesn't read the very next refresh as a drop.
    private val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE wallet ADD COLUMN lastServerCoins INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE wallet SET lastServerCoins = totalCoins")
        }
    }

    private val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS pending_earnings (
                    id TEXT NOT NULL PRIMARY KEY,
                    taskId TEXT NOT NULL,
                    orderId TEXT,
                    accountId TEXT NOT NULL,
                    rewardCoins INTEGER NOT NULL,
                    createdAtMs INTEGER NOT NULL
                )
            """.trimIndent())
        }
    }

    // One-time cleanup for the random-UUID success-log-id bug (TaskRunnerService used to mint a
    // fresh id per write, so a target re-logged as a success more than once — a re-claimed order
    // whose completion report didn't fully land, a retry, a canonical-key edge case — piled up as
    // extra rows instead of replacing the existing one). getDailyCountForAccountAction counts raw
    // rows, so those duplicates inflated the daily action-limit tally past the number of targets
    // actually acted on. Going forward the id is deterministic (see that insert call site) so this
    // can't recur; this migration only clears out what already accumulated on upgrading devices.
    // Only success=1 rows are touched — failure rows keep every distinct attempt for diagnostics.
    // Uses a correlated MAX(timestampMs) subquery rather than ROW_NUMBER()/window functions, which
    // the SQLite bundled with this app's minSdk=24 floor does not support.
    private val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                DELETE FROM account_logs
                WHERE success = 1
                AND id NOT IN (
                    SELECT id FROM account_logs a1
                    WHERE success = 1
                    AND timestampMs = (
                        SELECT MAX(timestampMs) FROM account_logs a2
                        WHERE a2.accountId = a1.accountId
                          AND a2.action = a1.action
                          AND a2.target = a1.target
                          AND a2.success = 1
                    )
                )
            """.trimIndent())
        }
    }

    private val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS watched_handles (
                    id TEXT NOT NULL PRIMARY KEY,
                    username TEXT NOT NULL,
                    profilePictureUrl TEXT,
                    fullName TEXT,
                    isPrivate INTEGER NOT NULL,
                    followerCount INTEGER NOT NULL DEFAULT 0,
                    followingCount INTEGER NOT NULL DEFAULT 0,
                    mediaCount INTEGER NOT NULL DEFAULT 0,
                    watchEnabled INTEGER NOT NULL,
                    pollIntervalMinutes INTEGER NOT NULL,
                    lastFetchedAt TEXT,
                    createdAt TEXT NOT NULL,
                    savedPostCount INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS watched_posts (
                    id TEXT NOT NULL PRIMARY KEY,
                    watchedHandleId TEXT NOT NULL,
                    postId TEXT NOT NULL,
                    code TEXT,
                    caption TEXT,
                    mediaUrl TEXT,
                    permalink TEXT,
                    mediaType INTEGER NOT NULL,
                    likeCount INTEGER NOT NULL,
                    commentCount INTEGER NOT NULL,
                    takenAt TEXT,
                    fetchedAt TEXT NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_watched_posts_watchedHandleId_postId ON watched_posts (watchedHandleId, postId)")
        }
    }

    private val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE watched_handles ADD COLUMN followerCount INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE watched_handles ADD COLUMN followingCount INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE watched_handles ADD COLUMN mediaCount INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "feedpilot.db")
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18,
                MIGRATION_18_19, MIGRATION_19_20
            )
            .fallbackToDestructiveMigration()
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    @Provides fun provideAccountDao(db: AppDatabase): AccountDao = db.accountDao()
    @Provides fun provideTaskDao(db: AppDatabase): TaskDao = db.taskDao()
    @Provides fun provideWalletDao(db: AppDatabase): WalletDao = db.walletDao()
    @Provides fun provideWithdrawalDao(db: AppDatabase): WithdrawalDao = db.withdrawalDao()
    @Provides fun provideSmmProviderDao(db: AppDatabase): com.feedpilot.client.data.local.dao.SmmProviderDao = db.smmProviderDao()
    @Provides fun provideOrderHistoryDao(db: AppDatabase): com.feedpilot.client.data.local.dao.OrderHistoryDao = db.orderHistoryDao()
    @Provides fun provideAccountLogDao(db: AppDatabase): AccountLogDao = db.accountLogDao()
    @Provides fun providePendingEarningDao(db: AppDatabase): PendingEarningDao = db.pendingEarningDao()
    @Provides fun provideWatchedHandleDao(db: AppDatabase): WatchedHandleDao = db.watchedHandleDao()
}

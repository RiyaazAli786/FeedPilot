package com.feedpilot.client.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        TaskEntity::class,
        WalletEntity::class,
        WalletTransactionEntity::class,
        WithdrawalEntity::class,
        SmmProviderEntity::class,
        OrderHistoryEntity::class,
        AccountLogEntity::class,
        PendingEarningEntity::class,
        WatchedHandleEntity::class,
        WatchedPostEntity::class
    ],
    version = 20,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun taskDao(): TaskDao
    abstract fun walletDao(): WalletDao
    abstract fun withdrawalDao(): WithdrawalDao
    abstract fun smmProviderDao(): com.feedpilot.client.data.local.dao.SmmProviderDao
    abstract fun orderHistoryDao(): com.feedpilot.client.data.local.dao.OrderHistoryDao
    abstract fun accountLogDao(): AccountLogDao
    abstract fun pendingEarningDao(): PendingEarningDao
    abstract fun watchedHandleDao(): WatchedHandleDao
}

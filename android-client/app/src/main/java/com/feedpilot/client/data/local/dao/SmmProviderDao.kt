package com.feedpilot.client.data.local.dao

import androidx.room.*
import com.feedpilot.client.data.local.SmmProviderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SmmProviderDao {
    @Query("SELECT * FROM smm_providers ORDER BY isDefault DESC, nickname ASC")
    fun getAllProviders(): Flow<List<SmmProviderEntity>>

    @Query("SELECT * FROM smm_providers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SmmProviderEntity?

    @Query("SELECT * FROM smm_providers WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultProvider(): SmmProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(provider: SmmProviderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(providers: List<SmmProviderEntity>)

    @Query("DELETE FROM smm_providers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE smm_providers SET isDefault = 0")
    suspend fun clearDefaultFlag()
}

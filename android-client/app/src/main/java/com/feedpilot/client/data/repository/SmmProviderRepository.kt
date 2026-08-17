package com.feedpilot.client.data.repository

import com.feedpilot.client.data.local.SmmProviderEntity
import com.feedpilot.client.data.local.dao.SmmProviderDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmmProviderRepository @Inject constructor(
    private val smmProviderDao: SmmProviderDao
) {
    // No seeded provider: shipping a built-in panel would bake someone's API key into every
    // install. The list starts empty and the operator adds their own provider in Settings.
    val providers: Flow<List<SmmProviderEntity>> = smmProviderDao.getAllProviders()

    suspend fun saveProvider(provider: SmmProviderEntity) {
        if (provider.isDefault) {
            smmProviderDao.clearDefaultFlag()
        }
        smmProviderDao.insertOrUpdate(provider)
    }

    suspend fun deleteProvider(id: String) {
        smmProviderDao.deleteById(id)
    }

    suspend fun setDefaultProvider(id: String) {
        val p = smmProviderDao.getById(id) ?: return
        smmProviderDao.clearDefaultFlag()
        smmProviderDao.insertOrUpdate(p.copy(isDefault = true))
    }
}

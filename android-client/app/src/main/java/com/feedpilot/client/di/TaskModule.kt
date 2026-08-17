package com.feedpilot.client.di

import com.feedpilot.client.task.EngagementEngine
import com.feedpilot.client.task.InstagramEngagementEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the engagement driver used by the task handlers. */
@Module
@InstallIn(SingletonComponent::class)
abstract class TaskModule {

    @Binds
    @Singleton
    abstract fun bindEngagementEngine(impl: InstagramEngagementEngine): EngagementEngine
}

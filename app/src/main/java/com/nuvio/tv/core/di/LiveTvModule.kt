package com.nuvio.tv.core.di

import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.data.livetv.LiveTvStorage
import com.nuvio.tv.data.livetv.LiveTvRecordings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
internal abstract class LiveTvModule {
    @Binds
    @IntoSet
    abstract fun bindLiveTvStorage(storage: LiveTvStorage): ProfileScopedCredentialStore

    @Binds
    @IntoSet
    abstract fun bindLiveTvRecordings(recordings: LiveTvRecordings): ProfileScopedCredentialStore
}

package com.colorwalk.app.di

import android.content.Context
import androidx.room.Room
import com.colorwalk.app.data.db.AppDatabase
import com.colorwalk.app.data.db.PhotoDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "colorwalk.db").build()

    @Provides
    fun providePhotoDao(db: AppDatabase): PhotoDao = db.photoDao()
}

package com.example.guet_map.di

import android.app.Application
import androidx.room.Room
import com.example.guet_map.BuildConfig
import com.example.guet_map.core.database.AppDatabase
import com.example.guet_map.core.dao.LocationDao
import com.example.guet_map.core.dao.GuideStepDao
import com.example.guet_map.core.dao.NotificationDao
import com.example.guet_map.core.dao.ContributeDraftDao
import com.example.guet_map.local.dao.LegacyFavoriteDao
import com.example.guet_map.local.dao.LegacyLocationDao
import com.example.guet_map.repository.LegacyFavoriteRepository
import com.example.guet_map.network.ApiService
import com.example.guet_map.network.AuthInterceptor
import com.example.guet_map.network.MockInterceptor
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import com.example.guet_map.data.UserPrefs

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()

    @Provides
    @Singleton
    fun provideOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)

        if (BuildConfig.USE_MOCK_API) {
            builder.addInterceptor(MockInterceptor())
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideDatabase(application: Application): AppDatabase {
        return Room.databaseBuilder(
            application,
            AppDatabase::class.java,
            "guet_map.db"
        )
            .fallbackToDestructiveMigration()
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    // ========== Core DAOs ==========
    @Provides
    fun provideLocationDao(database: AppDatabase): LocationDao = database.locationDao()

    @Provides
    fun provideGuideStepDao(database: AppDatabase): GuideStepDao = database.guideStepDao()

    @Provides
    fun provideNotificationDao(database: AppDatabase): NotificationDao = database.notificationDao()

    @Provides
    fun provideContributeDraftDao(database: AppDatabase): ContributeDraftDao = database.contributeDraftDao()

    // ========== AI 模块 DAOs ==========
    @Provides
    fun provideChatMessageDao(database: AppDatabase) = database.chatMessageDao()

    @Provides
    fun provideReviewDao(database: AppDatabase) = database.reviewDao()

    @Provides
    fun provideTimetableEntryDao(database: AppDatabase) = database.timetableEntryDao()

    // ========== Location 模块 DAOs ==========
    @Provides
    fun provideAnnouncementDao(database: AppDatabase) = database.announcementDao()

    @Provides
    fun provideOfflineMapDao(database: AppDatabase) = database.offlineMapDao()

    // ========== AI 模块 ==========
    @Provides
    @Singleton
    fun provideTimetableRepository(
        timetableEntryDao: com.example.guet_map.module.ai.data.local.dao.TimetableEntryDao,
        userPrefs: UserPrefs
    ): com.example.guet_map.module.ai.data.repository.TimetableRepository =
        com.example.guet_map.module.ai.data.repository.TimetableRepository(timetableEntryDao, userPrefs)

    // ========== Legacy DAOs (for old code compatibility) ==========
    @Provides
    fun provideLegacyFavoriteDao(database: AppDatabase): LegacyFavoriteDao = database.legacyFavoriteDao()

    @Provides
    fun provideLegacyLocationDao(database: AppDatabase): LegacyLocationDao = database.legacyLocationDao()

    @Provides
    @Singleton
    fun provideLegacyFavoriteRepository(
        apiService: ApiService,
        legacyFavoriteDao: LegacyFavoriteDao,
        legacyLocationDao: LegacyLocationDao,
        userPrefs: UserPrefs
    ): LegacyFavoriteRepository = LegacyFavoriteRepository(apiService, legacyFavoriteDao, legacyLocationDao, userPrefs)

    // ========== Social 模块 DAOs ==========
    @Provides
    fun provideWeatherDao(database: AppDatabase) = database.weatherDao()

    @Provides
    fun provideFavoriteDao(database: AppDatabase) = database.favoriteDao()

    @Provides
    fun providePhotoDao(database: AppDatabase) = database.photoDao()
}

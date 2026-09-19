package com.campusmeal.android.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.campusmeal.android.BuildConfig
import com.campusmeal.android.core.analytics.AnalyticsTracker
import com.campusmeal.android.core.analytics.NoOpAnalyticsTracker
import com.campusmeal.android.core.database.CampusMealDatabase
import com.campusmeal.android.core.datastore.CampusMealPreferences
import com.campusmeal.android.core.location.FusedLocationProvider
import com.campusmeal.android.core.location.LocationProvider
import com.campusmeal.android.core.network.ApiClientFactory
import com.campusmeal.android.core.network.NetworkConfig
import com.campusmeal.android.core.session.KeystoreSessionStorage
import com.campusmeal.android.core.session.AuthorizationHeaderProvider
import com.campusmeal.android.core.session.SessionAuthorizationHeaderProvider
import com.campusmeal.android.core.session.SessionRepository
import com.campusmeal.android.core.session.SessionStorage
import com.campusmeal.android.core.session.SessionRefreshInterceptor
import com.campusmeal.android.feature.auth.data.AuthApi
import com.campusmeal.android.feature.auth.data.NetworkAuthRepository
import com.campusmeal.android.feature.auth.domain.AuthRepository
import com.campusmeal.android.feature.decision.data.remote.MealDecisionApi
import com.campusmeal.android.feature.decision.data.repository.MealDecisionRepository
import com.campusmeal.android.feature.decision.data.repository.NetworkMealDecisionRepository
import com.campusmeal.android.feature.decision.domain.CompareMealOptionsUseCase
import com.campusmeal.android.feature.inventory.data.local.RoomInventoryCache
import com.campusmeal.android.feature.restaurants.data.NetworkRestaurantRepository
import com.campusmeal.android.feature.restaurants.data.RestaurantApi
import com.campusmeal.android.feature.restaurants.data.RestaurantRepository
import com.campusmeal.android.feature.inventory.data.remote.InventoryApi
import com.campusmeal.android.feature.inventory.data.repository.OfflineFirstInventoryRepository
import com.campusmeal.android.feature.inventory.domain.repository.InventoryRepository
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/**
 * Manual dependency container for the prototype. Features receive dependencies from here
 * instead of constructing them, so a DI framework can replace this later without touching them.
 */
interface AppContainer {
    val networkConfig: NetworkConfig
    val okHttpClient: OkHttpClient
    val retrofit: Retrofit
    val database: CampusMealDatabase
    val preferences: DataStore<Preferences>
    val sessionStorage: SessionStorage
    val sessionRepository: SessionRepository
    val authRepository: AuthRepository
    val locationProvider: LocationProvider
    val analyticsTracker: AnalyticsTracker
    val authorizationHeaderProvider: AuthorizationHeaderProvider
    val inventoryRepository: InventoryRepository
    val mealDecisionRepository: MealDecisionRepository
    val compareMealOptions: CompareMealOptionsUseCase
    val restaurantRepository: RestaurantRepository
}

class DefaultAppContainer(context: Context) : AppContainer {

    private val appContext = context.applicationContext

    override val networkConfig: NetworkConfig by lazy {
        NetworkConfig(
            baseUrl = BuildConfig.API_BASE_URL,
            httpLoggingEnabled = BuildConfig.DEBUG,
        )
    }

    // The interceptor reaches authRepository lazily, only when a 401 arrives, so there is no init cycle.
    override val okHttpClient: OkHttpClient by lazy {
        ApiClientFactory.createOkHttpClient(
            config = networkConfig,
            interceptors = listOf(
                SessionRefreshInterceptor(
                    authRepository = {
                        authRepository
                    },
                    authorizationHeaderProvider = {
                        authorizationHeaderProvider
                    },
                ),
            ),
        )
    }

    override val retrofit: Retrofit by lazy { ApiClientFactory.createRetrofit(networkConfig, okHttpClient) }

    override val database: CampusMealDatabase by lazy { CampusMealDatabase.create(appContext) }

    override val preferences: DataStore<Preferences> by lazy { CampusMealPreferences.dataStore(appContext) }

    // PENDING: replace with an Android Keystore-backed implementation before real login ships.
    override val sessionStorage: SessionStorage by lazy {
        KeystoreSessionStorage(appContext)
    }
    override val sessionRepository: SessionRepository by lazy { SessionRepository(sessionStorage) }

    // INTERIM: replaced by the full authentication data layer behind the same interface.
    override val authRepository: AuthRepository by lazy {
        NetworkAuthRepository(retrofit.create(AuthApi::class.java), sessionStorage)
    }

    override val locationProvider: LocationProvider by lazy { FusedLocationProvider(appContext) }

    override val analyticsTracker: AnalyticsTracker by lazy { NoOpAnalyticsTracker() }

    override val authorizationHeaderProvider: AuthorizationHeaderProvider by lazy {
        SessionAuthorizationHeaderProvider(sessionStorage)
    }

    override val inventoryRepository: InventoryRepository by lazy {
        OfflineFirstInventoryRepository(
            api = retrofit.create(InventoryApi::class.java),
            authorizationHeaderProvider = authorizationHeaderProvider,
            cache = RoomInventoryCache(database),
        )
    }

    override val mealDecisionRepository: MealDecisionRepository by lazy {
        NetworkMealDecisionRepository(
            api = retrofit.create(MealDecisionApi::class.java),
            authorizationHeaderProvider = authorizationHeaderProvider,
        )
    }

    override val compareMealOptions: CompareMealOptionsUseCase by lazy {
        CompareMealOptionsUseCase(mealDecisionRepository)
    }

    override val restaurantRepository: RestaurantRepository by lazy {
        NetworkRestaurantRepository(
            api = retrofit.create(RestaurantApi::class.java),
            authorizationHeaderProvider = authorizationHeaderProvider,
            preferences = preferences,
        )
    }
}

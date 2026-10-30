package com.campusmeal.android.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.campusmeal.android.BuildConfig
import com.campusmeal.android.core.analytics.AnalyticsTracker
import com.campusmeal.android.core.analytics.NoOpAnalyticsTracker
import com.campusmeal.android.core.common.CachePolicy
import com.campusmeal.android.core.database.CampusMealDatabase
import com.campusmeal.android.core.datastore.CampusMealPreferences
import com.campusmeal.android.core.location.FusedLocationProvider
import com.campusmeal.android.core.location.LocationProvider
import com.campusmeal.android.core.network.AndroidConnectivityObserver
import com.campusmeal.android.core.network.ApiClientFactory
import com.campusmeal.android.core.network.ConnectivityObserver
import com.campusmeal.android.core.network.NetworkConfig
import com.campusmeal.android.core.outbox.OutboxRepository
import com.campusmeal.android.core.outbox.UsageEventsApi
import com.campusmeal.android.core.outbox.WorkManagerOutboxScheduler
import com.campusmeal.android.core.session.AccountDataCleaner
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountWorkCanceller
import com.campusmeal.android.core.session.KeystoreSessionStorage
import com.campusmeal.android.core.session.AuthorizationHeaderProvider
import com.campusmeal.android.core.session.SessionAuthorizationHeaderProvider
import com.campusmeal.android.core.session.SessionRepository
import com.campusmeal.android.core.session.SessionStorage
import com.campusmeal.android.core.session.SessionRefreshInterceptor
import com.campusmeal.android.feature.auth.data.AuthApi
import com.campusmeal.android.feature.auth.data.NetworkAuthRepository
import com.campusmeal.android.feature.auth.domain.AuthRepository
import com.campusmeal.android.feature.analytics.data.FeatureAdoptionApi
import com.campusmeal.android.feature.analytics.data.FeatureAdoptionRepository
import com.campusmeal.android.feature.context.data.local.ContextPreferencesStore
import com.campusmeal.android.feature.decision.data.local.ComparisonHistoryStore
import com.campusmeal.android.feature.decision.data.local.MealPlanStore
import com.campusmeal.android.feature.decision.data.local.RecentDecisionStore
import com.campusmeal.android.feature.decision.data.remote.MealDecisionApi
import com.campusmeal.android.feature.decision.data.remote.RecommendationEventsApi
import com.campusmeal.android.feature.decision.data.repository.RecommendationEventReporter
import com.campusmeal.android.feature.decision.data.repository.MealDecisionRepository
import com.campusmeal.android.feature.decision.data.repository.NetworkMealDecisionRepository
import com.campusmeal.android.feature.decision.domain.CompareMealOptionsUseCase
import com.campusmeal.android.feature.inventory.data.local.RoomInventoryStore
import com.campusmeal.android.feature.profile.data.CurrentUserApi
import com.campusmeal.android.feature.profile.data.ProfileRepository
import com.campusmeal.android.feature.restaurants.data.NetworkRestaurantRepository
import com.campusmeal.android.feature.restaurants.data.RestaurantApi
import com.campusmeal.android.feature.restaurants.data.RestaurantRepository
import com.campusmeal.android.feature.inventory.data.remote.InventoryApi
import com.campusmeal.android.feature.inventory.data.remote.InventoryMutationApi
import com.campusmeal.android.feature.inventory.data.repository.OfflineFirstInventoryRepository
import com.campusmeal.android.feature.inventory.domain.repository.InventoryRepository
import com.campusmeal.android.feature.recipes.data.local.RecipeFilterStore
import com.campusmeal.android.feature.recipes.data.local.RoomRecipeCatalogStore
import com.campusmeal.android.feature.recipes.data.remote.RecipeApi
import com.campusmeal.android.feature.recipes.data.repository.OfflineFirstRecipeRepository
import com.campusmeal.android.feature.recipes.data.usage.OutboxRecipeUsageRecorder
import com.campusmeal.android.feature.recipes.domain.RecipeRepository
import com.campusmeal.android.feature.recipes.domain.RecipeUsageRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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

    /** For non-idempotent writes: no transparent connection retries (see ApiClientFactory). */
    val mutationRetrofit: Retrofit
    val database: CampusMealDatabase
    val preferences: DataStore<Preferences>
    val sessionStorage: SessionStorage
    val sessionRepository: SessionRepository

    /** The single owner of the active account and session generation; shared by every account-scoped component. */
    val sessionCoordinator: AccountSessionCoordinator
    val cachePolicy: CachePolicy
    val connectivityObserver: ConnectivityObserver
    val authRepository: AuthRepository
    val locationProvider: LocationProvider
    val analyticsTracker: AnalyticsTracker
    val authorizationHeaderProvider: AuthorizationHeaderProvider
    val inventoryRepository: InventoryRepository
    val mealDecisionRepository: MealDecisionRepository
    val compareMealOptions: CompareMealOptionsUseCase

    /** S3-09: durable delivery of BQ8 and BQ6 events (Room + WorkManager). */
    val outboxRepository: OutboxRepository
    val recommendationEventReporter: RecommendationEventReporter
    val restaurantRepository: RestaurantRepository
    val profileRepository: ProfileRepository
    val recipeRepository: RecipeRepository
    val recipeFilterStore: RecipeFilterStore

    /** S3-11: BQ6 report for ANALYST accounts, cached per account and month. */
    val featureAdoptionRepository: FeatureAdoptionRepository

    /** Last meal context per account (no coordinates), restored after the app is closed. */
    val contextPreferencesStore: ContextPreferencesStore

    /** Last current recommendation and choice per account, shown again for 5 minutes. */
    val recentDecisionStore: RecentDecisionStore

    /** The chosen alternative, shown on Home for the available lunch time. */
    val mealPlanStore: MealPlanStore

    /** BQ3 usage admission, stored durably in the S3-09 outbox. */
    val recipeUsageRecorder: RecipeUsageRecorder
}

class DefaultAppContainer(context: Context) : AppContainer {

    private val appContext = context.applicationContext

    /** Application-lifetime scope for work no screen should wait for, such as the remote logout request. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
                ),
            ),
        )
    }

    override val retrofit: Retrofit by lazy { ApiClientFactory.createRetrofit(networkConfig, okHttpClient) }

    // Same pool, timeouts and interceptors as okHttpClient; writes are never retried behind the app's back.
    override val mutationRetrofit: Retrofit by lazy {
        ApiClientFactory.createRetrofit(networkConfig, ApiClientFactory.createMutationOkHttpClient(okHttpClient))
    }

    override val database: CampusMealDatabase by lazy { CampusMealDatabase.create(appContext) }

    override val preferences: DataStore<Preferences> by lazy { CampusMealPreferences.dataStore(appContext) }

    // Tokens are encrypted with an Android Keystore key.
    override val sessionStorage: SessionStorage by lazy {
        KeystoreSessionStorage(appContext)
    }
    override val sessionRepository: SessionRepository by lazy { SessionRepository(sessionStorage) }

    override val sessionCoordinator: AccountSessionCoordinator by lazy {
        AccountSessionCoordinator(
            preferences = preferences,
            hasCredentials = { sessionStorage.session.first() != null },
            listeners = {
                listOf(
                    // Each account-owned store deletes only its own rows; later issues register theirs here.
                    AccountDataCleaner(
                        stores = listOf(
                            inventoryStore,
                            restaurantRepository,
                            profileRepository,
                            recipeFilterStore,
                            contextPreferencesStore,
                            comparisonHistoryStore,
                            recentDecisionStore,
                            mealPlanStore,
                            featureAdoptionRepository,
                        ),
                    ),
                    AccountWorkCanceller(appContext),
                    // Not an AccountScopedStore: pending events survive logout (AccountLifecycle.kt).
                    outboxRepository,
                )
            },
        )
    }

    override val cachePolicy: CachePolicy by lazy { CachePolicy() }

    override val connectivityObserver: ConnectivityObserver by lazy {
        AndroidConnectivityObserver(appContext, applicationScope)
    }

    override val authRepository: AuthRepository by lazy {
        NetworkAuthRepository(
            api = retrofit.create(AuthApi::class.java),
            storage = sessionStorage,
            coordinator = sessionCoordinator,
            accountIdentifier = profileRepository,
            backgroundScope = applicationScope,
        )
    }

    override val locationProvider: LocationProvider by lazy { FusedLocationProvider(appContext) }

    // Location-context events have no backend endpoint yet. BQ8 and BQ6 events go through the
    // durable outbox (outboxRepository).
    override val analyticsTracker: AnalyticsTracker by lazy { NoOpAnalyticsTracker() }

    override val authorizationHeaderProvider: AuthorizationHeaderProvider by lazy {
        SessionAuthorizationHeaderProvider(sessionStorage, sessionCoordinator)
    }

    private val inventoryStore: RoomInventoryStore by lazy { RoomInventoryStore(database) }

    override val inventoryRepository: InventoryRepository by lazy {
        OfflineFirstInventoryRepository(
            api = retrofit.create(InventoryApi::class.java),
            mutationApi = mutationRetrofit.create(InventoryMutationApi::class.java),
            authorizationHeaderProvider = authorizationHeaderProvider,
            store = inventoryStore,
            coordinator = sessionCoordinator,
            cachePolicy = cachePolicy,
        )
    }

    override val mealDecisionRepository: MealDecisionRepository by lazy {
        NetworkMealDecisionRepository(
            api = retrofit.create(MealDecisionApi::class.java),
            authorizationHeaderProvider = authorizationHeaderProvider,
            history = comparisonHistoryStore,
        )
    }

    private val comparisonHistoryStore: ComparisonHistoryStore by lazy { ComparisonHistoryStore(database, sessionCoordinator) }

    override val featureAdoptionRepository: FeatureAdoptionRepository by lazy {
        FeatureAdoptionRepository(
            api = retrofit.create(FeatureAdoptionApi::class.java),
            authorizationHeaderProvider = authorizationHeaderProvider,
            coordinator = sessionCoordinator,
            database = database,
            preferences = preferences,
            cachePolicy = cachePolicy,
        )
    }

    override val compareMealOptions: CompareMealOptionsUseCase by lazy {
        CompareMealOptionsUseCase(mealDecisionRepository)
    }

    // Events are idempotent by clientEventId, so the regular client (with connection retries) is safe.
    override val outboxRepository: OutboxRepository by lazy {
        OutboxRepository(
            database = database,
            recommendationApi = retrofit.create(RecommendationEventsApi::class.java),
            usageApi = retrofit.create(UsageEventsApi::class.java),
            authorizationHeaderProvider = authorizationHeaderProvider,
            coordinator = sessionCoordinator,
            scheduler = WorkManagerOutboxScheduler(appContext),
        )
    }

    override val recommendationEventReporter: RecommendationEventReporter by lazy {
        RecommendationEventReporter(outbox = outboxRepository)
    }

    override val restaurantRepository: NetworkRestaurantRepository by lazy {
        NetworkRestaurantRepository(
            api = retrofit.create(RestaurantApi::class.java),
            authorizationHeaderProvider = authorizationHeaderProvider,
            preferences = preferences,
            coordinator = sessionCoordinator,
            database = database,
            cachePolicy = cachePolicy,
        )
    }

    // The catalog is global, non-personal data and is not an AccountScopedStore: it survives logout.
    override val recipeRepository: RecipeRepository by lazy {
        OfflineFirstRecipeRepository(
            api = retrofit.create(RecipeApi::class.java),
            authorizationHeaderProvider = authorizationHeaderProvider,
            store = RoomRecipeCatalogStore(database),
            coordinator = sessionCoordinator,
            cachePolicy = cachePolicy,
        )
    }

    override val recipeFilterStore: RecipeFilterStore by lazy { RecipeFilterStore(preferences, sessionCoordinator) }

    override val contextPreferencesStore: ContextPreferencesStore by lazy { ContextPreferencesStore(preferences, sessionCoordinator) }

    override val recentDecisionStore: RecentDecisionStore by lazy { RecentDecisionStore(preferences, sessionCoordinator) }

    override val mealPlanStore: MealPlanStore by lazy { MealPlanStore(preferences, sessionCoordinator) }

    override val recipeUsageRecorder: RecipeUsageRecorder by lazy { OutboxRecipeUsageRecorder(outboxRepository) }

    override val profileRepository: ProfileRepository by lazy {
        ProfileRepository(
            api = retrofit.create(CurrentUserApi::class.java),
            authorizationHeaderProvider = authorizationHeaderProvider,
            coordinator = sessionCoordinator,
            dao = database.accountProfileDao(),
        )
    }
}

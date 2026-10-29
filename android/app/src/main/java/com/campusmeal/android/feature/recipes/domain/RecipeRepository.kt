package com.campusmeal.android.feature.recipes.domain

import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.feature.recipes.domain.model.RecipeCatalogSnapshot
import kotlinx.coroutines.flow.Flow

sealed interface CatalogError {
    /** No HTTP answer (offline, timeout) or a temporary token-refresh failure. */
    data object Unreachable : CatalogError

    /** 503: the backend has no seeded catalog yet, or is temporarily unavailable. */
    data object NotAvailableYet : CatalogError

    /** Another 5xx. */
    data class Server(val httpCode: Int) : CatalogError

    /** Another non-2xx status except 401. */
    data class Http(val httpCode: Int) : CatalogError

    /** Undecodable body, unsupported contract version or a broken rule; [reason] has no user data. */
    data class InvalidResponse(val reason: String) : CatalogError

    /**
     * The stored version came back with different content or production time. The backend must publish a
     * new version for a change, so the stored catalog is kept.
     */
    data object SameVersionChanged : CatalogError
}

sealed interface CatalogRefreshResult {
    /** A new version was stored. */
    data object Replaced : CatalogRefreshResult

    /** The same version and content arrived again; only the fetch time was renewed. */
    data object Unchanged : CatalogRefreshResult

    /** Skipped: fresh within the 24-hour TTL, or answered by a fetch that started after this request. */
    data object AlreadyCurrent : CatalogRefreshResult

    data object Unauthorized : CatalogRefreshResult

    /** The caller's session ended meanwhile; nothing was stored. */
    data object SessionEnded : CatalogRefreshResult

    /** Nothing was stored; the previous catalog keeps its own fetch time. */
    data class Failed(val error: CatalogError) : CatalogRefreshResult
}

/**
 * The BQ3 recipe catalog, local first. The catalog is global data, but every call runs for the caller's
 * session [AccountSessionScope]: requests use only its credentials and results are stored only while it
 * is still active.
 */
interface RecipeRepository {

    /** The stored catalog now and after every replacement; null while none was ever fetched. */
    fun observeCatalog(scope: AccountSessionScope): Flow<RecipeCatalogSnapshot?>

    /** Without [force], a catalog fetched within `CachePolicy.recipeCatalog` (24 h) is not requested again. */
    suspend fun refreshCatalog(scope: AccountSessionScope, force: Boolean = false): CatalogRefreshResult
}

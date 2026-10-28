package com.campusmeal.android.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.campusmeal.android.core.session.AccountSessionScope

/**
 * Holds the ViewModels of the authenticated graph for exactly one [AccountSessionScope]. It lives in
 * the Activity's ViewModelStore, so those ViewModels survive configuration changes, but switching to
 * another scope (logout, expiry, another account or a new session of the same account) clears them
 * and cancels their `viewModelScope` work. Account A's screen state can never appear in B's graph.
 */
class SessionViewModelStores : ViewModel() {

    private var scope: AccountSessionScope? = null
    private var owner: ViewModelStoreOwner? = null

    fun ownerFor(scope: AccountSessionScope): ViewModelStoreOwner {
        val current = owner
        if (current != null && this.scope == scope) return current
        clear()
        val store = ViewModelStore()
        return object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }.also {
            this.scope = scope
            owner = it
        }
    }

    fun clear() {
        owner?.viewModelStore?.clear()
        owner = null
        scope = null
    }

    override fun onCleared() = clear()
}

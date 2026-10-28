package com.campusmeal.android.core.session

import java.io.IOException

/**
 * A protected request got HTTP 401 and the token refresh failed for a temporary reason (no
 * connection, timeout, 408, 429, 5xx or an unreadable answer). It is an [IOException], so `apiCall`
 * reports it as [com.campusmeal.android.core.network.ApiResult.NetworkUnavailable] and callers take
 * their offline path: the session and every local cache are kept.
 */
class SessionRefreshUnavailableException : IOException("Session refresh temporarily unavailable")

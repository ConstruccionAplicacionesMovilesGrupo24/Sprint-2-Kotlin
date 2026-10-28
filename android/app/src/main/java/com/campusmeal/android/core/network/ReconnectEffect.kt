package com.campusmeal.android.core.network

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * Runs [onReconnect] once each time [online] goes from false to true while the caller is composed.
 * The initial value never triggers it, so it does not duplicate a screen's first load.
 */
@Composable
fun ReconnectEffect(online: Boolean, onReconnect: () -> Unit) {
    val action by rememberUpdatedState(onReconnect)
    var wasOnline by remember { mutableStateOf(online) }
    LaunchedEffect(online) {
        if (online && !wasOnline) action()
        wasOnline = online
    }
}

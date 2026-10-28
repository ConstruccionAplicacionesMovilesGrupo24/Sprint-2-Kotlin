package com.campusmeal.android.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.campusmeal.android.R
import kotlinx.coroutines.delay

/** How long a request runs before the "taking longer than usual" notice appears. */
const val SLOW_RESPONSE_NOTICE_DELAY_MILLIS = 8_000L

/**
 * Says the server is slow while [active] has been true for [delayMillis]. A slow answer is often a
 * Render Free instance waking up, but the text never claims that as a confirmed cause.
 */
@Composable
fun SlowResponseNotice(
    active: Boolean,
    modifier: Modifier = Modifier,
    delayMillis: Long = SLOW_RESPONSE_NOTICE_DELAY_MILLIS,
    textAlign: TextAlign? = null,
) {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        slow = false
        if (active) {
            delay(delayMillis)
            slow = true
        }
    }
    if (active && slow) {
        Text(
            text = stringResource(R.string.slow_response_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.campusMealColors.textSecondary,
            textAlign = textAlign,
            modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

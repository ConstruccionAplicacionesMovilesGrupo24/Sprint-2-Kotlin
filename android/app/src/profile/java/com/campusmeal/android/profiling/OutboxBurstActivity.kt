package com.campusmeal.android.profiling

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * S4-11 workload launcher, `profile` build only (`scripts/profiling/s4-11-burst.sh` starts it):
 *
 * ```
 * adb shell am start -W -S -n com.campusmeal.android.profile/com.campusmeal.android.profiling.OutboxBurstActivity \
 *     --es cm.outbox.burst stress
 * ```
 *
 * `cm.outbox.burst` is `representative`, `stress` or `check`. The result is one log line with tag [TAG],
 * which the script waits for.
 */
class OutboxBurstActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            text = "S4-11 outbox burst running…"
            setPadding(48, 160, 48, 48)
        }
        setContentView(status)
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: OutboxBurst.REPRESENTATIVE
        lifecycleScope.launch {
            val line = runCatching { withContext(Dispatchers.Default) { OutboxBurst(applicationContext).run(mode) } }
                .getOrElse { "S4-11 burst mode=$mode FAILED ${it::class.java.simpleName}" }
            Log.i(TAG, line)
            status.text = line
        }
    }

    companion object {
        const val TAG = "CampusMealBurst"
        const val EXTRA_MODE = "cm.outbox.burst"
    }
}

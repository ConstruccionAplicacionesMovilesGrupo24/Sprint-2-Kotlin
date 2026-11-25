package com.campusmeal.android.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.campusmeal.android.BuildConfig
import com.campusmeal.android.core.designsystem.CampusMealTheme
import com.campusmeal.android.feature.recipes.domain.Bq3ProfilingDataset

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // S4-05: the profile build may evaluate BQ3 on a synthetic dataset chosen at launch (`--es cm.bq3.dataset stress`).
        if (BuildConfig.BUILD_TYPE == "profile") Bq3ProfilingDataset.select(intent?.getStringExtra(Bq3ProfilingDataset.EXTRA))
        enableEdgeToEdge()
        setContent {
            CampusMealTheme {
                CampusMealApp()
            }
        }
    }
}

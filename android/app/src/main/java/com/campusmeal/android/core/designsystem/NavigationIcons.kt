package com.campusmeal.android.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Bottom-navigation icons as local vectors (Material Symbols shapes), so the app does not need the
 * material-icons artifact for four glyphs. `Icon` tints them, so the fill color is irrelevant.
 */
object NavigationIcons {
    val Home: ImageVector = icon("Home", "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z")
    val Inventory: ImageVector = icon(
        "Inventory",
        "M20,2H4C3,2 2,2.9 2,4v3.01c0,0.72 0.43,1.34 1,1.69V20c0,1.1 1.1,2 2,2h14c0.9,0 2,-0.9 2,-2V8.7" +
            "c0.57,-0.35 1,-0.97 1,-1.69V4C22,2.9 21,2 20,2zM15,14H9v-2h6V14zM20,7H4V4h16V7z",
    )
    val Decide: ImageVector = icon("Decide", "M5,9.2h3V19H5zM10.6,5h2.8v14h-2.8zM16.2,13H19v6h-2.8z")
    val Profile: ImageVector = icon(
        "Profile",
        "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2" +
            "c0,-2.66 -5.33,-4 -8,-4z",
    )

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.Black))
            .build()
}

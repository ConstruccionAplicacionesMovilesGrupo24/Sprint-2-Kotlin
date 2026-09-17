package com.campusmeal.android.feature.context

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmeal.android.R
import com.campusmeal.android.core.designsystem.CampusMealTheme
import com.campusmeal.android.core.designsystem.campusMealColors
import com.campusmeal.android.feature.context.domain.Campus
import com.campusmeal.android.feature.context.domain.CampusCatalog
import kotlin.math.roundToInt

private val PagePadding = 24.dp
private val CardShape = RoundedCornerShape(12.dp)
private val ButtonShape = RoundedCornerShape(10.dp)
private val ButtonHeight = 48.dp

/**
 * Standalone host for the location flow, matching screens 04, 13 and 14 of the CampusMeal UI file.
 * The Set Context screen embeds [LocationContextSection] instead and supplies its own app bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationContextScreen(
    viewModel: LocationContextViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val title = when (uiState) {
        is LocationContextUiState.LocationGranted -> R.string.context_location_appbar_with
        LocationContextUiState.PermissionDenied, LocationContextUiState.Error ->
            R.string.context_location_appbar_without
        else -> R.string.context_location_appbar
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
                },
                navigationIcon = { BackButton(onBack) },
                // The app Scaffold already consumes the status bar inset.
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.campusMealColors.textPrimary,
                ),
            )
        },
    ) { innerPadding ->
        LocationContextSection(viewModel, Modifier.padding(innerPadding))
    }
}

/**
 * Location section of the BQ4 context flow. It is a section, not a full screen, so the Set Context
 * screen can host it above the time, budget and dietary inputs.
 */
@Composable
fun LocationContextSection(
    viewModel: LocationContextViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val mealLocation by viewModel.mealLocation.collectAsStateWithLifecycle()

    // Approximate location is enough for BQ4; the fine permission is only accepted if the user grants it.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        viewModel.onPermissionResult(granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
    }

    LaunchedEffect(Unit) { viewModel.onContextOpened() }

    LocationContextContent(
        uiState = uiState,
        selectedCampus = mealLocation?.campus,
        onRequestPermission = {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            )
        },
        onRetry = viewModel::requestLocation,
        onOpenCampusSelection = viewModel::openCampusSelection,
        onCampusSelected = viewModel::onCampusSelected,
        modifier = modifier,
    )
}

@Composable
fun LocationContextContent(
    uiState: LocationContextUiState,
    selectedCampus: Campus?,
    onRequestPermission: () -> Unit,
    onRetry: () -> Unit,
    onOpenCampusSelection: () -> Unit,
    onCampusSelected: (Campus) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PagePadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (uiState) {
            LocationContextUiState.Initial, LocationContextUiState.Loading -> LoadingState()

            LocationContextUiState.PermissionRequired -> PermissionRationale(
                onRequestPermission = onRequestPermission,
                onOpenCampusSelection = onOpenCampusSelection,
            )

            is LocationContextUiState.LocationGranted -> {
                StatusBanner(
                    tone = BannerTone.POSITIVE,
                    label = stringResource(R.string.context_banner_enabled_label),
                    body = grantedBannerBody(uiState),
                )
                SectionLabel(stringResource(R.string.context_location_section))
                LocationCard(
                    dotColor = MaterialTheme.colorScheme.primary,
                    title = uiState.campus?.name
                        ?: stringResource(R.string.context_location_no_campus_title),
                    subtitle = stringResource(R.string.context_location_detected),
                    action = stringResource(R.string.context_action_change),
                    onAction = onOpenCampusSelection,
                )
            }

            LocationContextUiState.PermissionDenied -> DeniedState(
                label = stringResource(R.string.context_banner_denied_label),
                body = stringResource(R.string.context_banner_denied_body),
                selectedCampus = selectedCampus,
                onCampusSelected = onCampusSelected,
            )

            LocationContextUiState.Error -> {
                DeniedState(
                    label = stringResource(R.string.context_banner_unavailable_label),
                    body = stringResource(R.string.context_banner_unavailable_body),
                    selectedCampus = selectedCampus,
                    onCampusSelected = onCampusSelected,
                )
                TextButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.context_location_retry),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            is LocationContextUiState.ManualCampusSelection -> {
                SectionLabel(stringResource(R.string.context_location_section))
                LocationCard(
                    dotColor = uiState.selected
                        ?.let { MaterialTheme.colorScheme.primary }
                        ?: MaterialTheme.campusMealColors.textSecondary,
                    title = uiState.selected?.name
                        ?: stringResource(R.string.context_campus_none_title),
                    subtitle = uiState.selected
                        ?.let { stringResource(R.string.context_campus_manual_subtitle) }
                        ?: stringResource(R.string.context_campus_none_subtitle),
                    action = null,
                    onAction = {},
                )
                CampusChips(uiState.campuses, uiState.selected, onCampusSelected)
            }
        }
    }
}

@Composable
private fun grantedBannerBody(state: LocationContextUiState.LocationGranted): String {
    val place = state.campus?.name ?: stringResource(R.string.context_location_no_campus_title)
    return state.accuracyMeters
        ?.let { stringResource(R.string.context_banner_enabled_body, place, it.roundToInt()) }
        ?: stringResource(R.string.context_banner_enabled_body_no_accuracy, place)
}

@Composable
private fun DeniedState(
    label: String,
    body: String,
    selectedCampus: Campus?,
    onCampusSelected: (Campus) -> Unit,
) {
    StatusBanner(tone = BannerTone.WARNING, label = label, body = body)
    SectionLabel(stringResource(R.string.context_location_section))
    LocationCard(
        dotColor = selectedCampus
            ?.let { MaterialTheme.colorScheme.primary }
            ?: MaterialTheme.campusMealColors.textSecondary,
        title = selectedCampus?.name ?: stringResource(R.string.context_campus_none_title),
        subtitle = selectedCampus
            ?.let { stringResource(R.string.context_campus_manual_subtitle) }
            ?: stringResource(R.string.context_campus_none_subtitle),
        action = null,
        onAction = {},
    )
    CampusChips(CampusCatalog.campuses, selectedCampus, onCampusSelected)
}

@Composable
private fun PermissionRationale(
    onRequestPermission: () -> Unit,
    onOpenCampusSelection: () -> Unit,
) {
    Spacer(Modifier.height(32.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LocationMark() }
    Text(
        text = stringResource(R.string.context_location_title),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.campusMealColors.textPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.context_location_rationale),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.campusMealColors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onRequestPermission,
        shape = ButtonShape,
        modifier = Modifier
            .fillMaxWidth()
            .height(ButtonHeight),
    ) {
        Text(
            text = stringResource(R.string.context_location_use_my_location),
            style = MaterialTheme.typography.labelLarge,
        )
    }
    OutlinedButton(
        onClick = onOpenCampusSelection,
        shape = ButtonShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(ButtonHeight),
    ) {
        Text(
            text = stringResource(R.string.context_location_choose_campus),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun LoadingState() {
    Spacer(Modifier.height(32.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(
            text = stringResource(R.string.context_location_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.campusMealColors.textSecondary,
        )
    }
}

/** The concentric location mark from screen 04. */
@Composable
private fun LocationMark(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(140.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(70.dp)) {
            drawCircle(primary, radius = size.minDimension / 2f, style = Stroke(width = 2.dp.toPx()))
            drawCircle(primary, radius = 17.dp.toPx())
        }
    }
}

private enum class BannerTone { POSITIVE, WARNING }

@Composable
private fun StatusBanner(tone: BannerTone, label: String, body: String) {
    val colors = MaterialTheme.campusMealColors
    val background = if (tone == BannerTone.POSITIVE) colors.positiveBackground else colors.warningBackground
    val foreground = if (tone == BannerTone.POSITIVE) colors.positiveForeground else colors.warningForeground
    Surface(color = background, shape = CardShape, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BannerMark(tone, foreground)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = label, style = MaterialTheme.typography.labelSmall, color = foreground)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textPrimary,
                )
            }
        }
    }
}

/** A dot for the positive tone and a triangle for the warning one, so tone never relies on color alone. */
@Composable
private fun BannerMark(tone: BannerTone, color: Color) {
    if (tone == BannerTone.POSITIVE) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
    } else {
        Canvas(
            Modifier
                .padding(top = 4.dp)
                .size(10.dp),
        ) { drawWarningTriangle(color) }
    }
}

private fun DrawScope.drawWarningTriangle(color: Color) {
    val path = Path().apply {
        moveTo(size.width / 2f, 0f)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }
    drawPath(path, color)
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.campusMealColors.textPrimary,
    )
}

@Composable
private fun LocationCard(
    dotColor: Color,
    title: String,
    subtitle: String,
    action: String?,
    onAction: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = CardShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.campusMealColors.textPrimary,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.campusMealColors.textSecondary,
                )
            }
            if (action != null) {
                TextButton(onClick = onAction) {
                    Text(
                        text = action,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CampusChips(
    campuses: List<Campus>,
    selected: Campus?,
    onCampusSelected: (Campus) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        campuses.forEach { campus ->
            val isSelected = campus.id == selected?.id
            OutlinedButton(
                onClick = { onCampusSelected(campus) },
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.height(36.dp),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.campusMealColors.textPrimary
                    },
                ),
            ) {
                // The check mark keeps the selection readable without relying on color.
                Text(
                    text = if (isSelected) {
                        stringResource(R.string.context_campus_option_selected, campus.name)
                    } else {
                        campus.name
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    TextButton(onClick = onBack) {
        Text(
            text = stringResource(R.string.context_back),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.campusMealColors.textPrimary,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF3F5F4)
@Composable
private fun PermissionRequiredPreview() {
    CampusMealTheme {
        LocationContextContent(LocationContextUiState.PermissionRequired, null, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF3F5F4)
@Composable
private fun LocationGrantedPreview() {
    CampusMealTheme {
        LocationContextContent(
            uiState = LocationContextUiState.LocationGranted(CampusCatalog.campuses.first(), 120f),
            selectedCampus = CampusCatalog.campuses.first(),
            onRequestPermission = {},
            onRetry = {},
            onOpenCampusSelection = {},
            onCampusSelected = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF3F5F4)
@Composable
private fun PermissionDeniedPreview() {
    CampusMealTheme {
        LocationContextContent(LocationContextUiState.PermissionDenied, null, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF3F5F4)
@Composable
private fun CampusSelectionPreview() {
    CampusMealTheme {
        LocationContextContent(
            uiState = LocationContextUiState.ManualCampusSelection(
                campuses = CampusCatalog.campuses,
                selected = CampusCatalog.campuses.first(),
            ),
            selectedCampus = CampusCatalog.campuses.first(),
            onRequestPermission = {},
            onRetry = {},
            onOpenCampusSelection = {},
            onCampusSelected = {},
        )
    }
}

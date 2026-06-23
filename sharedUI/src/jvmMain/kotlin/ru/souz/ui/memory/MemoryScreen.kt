package ru.souz.ui.memory

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.localDI
import ru.souz.memory.MemoryMaintenanceMode
import ru.souz.ui.common.DraggableWindowArea
import ru.souz.ui.glassColors
import ru.souz.ui.common.RealLiquidGlassCard
import souz.sharedui.generated.resources.*

@Composable
fun MemoryScreen(
    onClose: () -> Unit,
    onShowSnack: (String) -> Unit = {},
) {
    val di = localDI()
    val viewModel = viewModel { MemoryViewModel(di) }
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is MemoryEffect.ShowError -> onShowSnack(effect.message)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.onAction(MemoryAction.Load)
    }

    MemoryScreen(
        state = state,
        onAction = viewModel::onAction,
        onClose = onClose,
    )
}

@Composable
fun MemoryScreen(
    state: MemoryUiState,
    onAction: (MemoryAction) -> Unit,
    onClose: () -> Unit,
) {
    val windowInfo = LocalWindowInfo.current
    val isFocused = windowInfo.isWindowFocused
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    when {
                        state.editor != null -> onAction(MemoryAction.CloseDialog)
                        state.confirm != null -> onAction(MemoryAction.CancelConfirmAction)
                        state.detailsFactId != null -> onAction(MemoryAction.CloseDetails)
                        else -> onClose()
                    }
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        RealLiquidGlassCard(
            modifier = Modifier.fillMaxSize(),
            isWindowFocused = isFocused,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                DraggableWindowArea {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(Res.string.memory_title),
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.glassColors.textPrimary,
                            )
                            Text(
                                text = stringResource(Res.string.memory_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.7f),
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { onAction(MemoryAction.OpenCreateDialog) }) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.glassColors.textPrimary,
                                )
                                Text(
                                    text = stringResource(Res.string.memory_create),
                                    color = MaterialTheme.glassColors.textPrimary,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            IconButton(onClick = onClose) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(Res.string.button_close),
                                    tint = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                }

                MemoryFilters(
                    filters = state.filters,
                    onFiltersChange = { onAction(MemoryAction.ChangeFilters(it)) },
                )

                MemoryMaintenanceControls(
                    state = state.maintenance,
                    onAction = onAction,
                )

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        MemoryFactsContent(
                            state = state,
                            onAction = onAction,
                        )
                    }

                    if (state.detailsFactId != null || state.selectedFact != null || state.isDetailsLoading) {
                        Box(
                            modifier = Modifier
                                .width(360.dp)
                                .fillMaxHeight(),
                        ) {
                            MemoryFactDetailsPanel(
                                state = state,
                                onAction = onAction,
                            )
                        }
                    }
                }
            }

            state.editor?.let { editor ->
                MemoryFactEditorDialog(
                    editor = editor,
                    isSaving = state.isSaving,
                    onDismiss = { onAction(MemoryAction.CloseDialog) },
                    onSave = { onAction(MemoryAction.SaveFact(it)) },
                )
            }

            state.confirm?.let { confirmAction ->
                MemoryConfirmDialog(
                    action = confirmAction,
                    factTitle = state.factTitle(confirmAction.factId),
                    onConfirm = { onAction(MemoryAction.ConfirmAction) },
                    onDismiss = { onAction(MemoryAction.CancelConfirmAction) },
                )
            }
        }
    }
}

@Composable
private fun MemoryMaintenanceControls(
    state: MemoryMaintenanceUiState,
    onAction: (MemoryAction) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.28f),
        contentColor = MaterialTheme.glassColors.textPrimary,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Dreamer", fontWeight = FontWeight.SemiBold)
                Switch(
                    checked = state.isEnabled,
                    onCheckedChange = { onAction(MemoryAction.SetDreamerEnabled(it)) },
                )
                SingleChoiceSegmentedButtonRow {
                    listOf(
                        MemoryMaintenanceMode.OFF to "Off",
                        MemoryMaintenanceMode.LOCAL_ONLY to "Local",
                        MemoryMaintenanceMode.LOCAL_THEN_CLOUD to "Cloud",
                    ).forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = state.mode == mode,
                            onClick = { onAction(MemoryAction.SelectDreamerMode(mode)) },
                            shape = SegmentedButtonDefaults.itemShape(index, 3),
                            enabled = mode != MemoryMaintenanceMode.LOCAL_THEN_CLOUD || state.isEnabled,
                        ) {
                            Text(label)
                        }
                    }
                }
                Text(
                    text = "Pending ${state.pendingClusters} · Blocked ${state.blockedClusters}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.72f),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { onAction(MemoryAction.RunDreamerNow) },
                    enabled = state.isEnabled && !state.isRunningNow,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(if (state.isRunningNow) "Running" else "Run")
                }
            }
            val statusText = buildList {
                state.lastErrorCode?.let { add("Error $it") }
                state.blockedReason?.let { add("Blocked ${it.name.lowercase().replace('_', ' ')}") }
                if (state.mode == MemoryMaintenanceMode.LOCAL_THEN_CLOUD) {
                    add("Cloud ${state.tokensUsedToday}/${state.dailyCloudTokenLimitInput} tokens")
                    add("${state.cloudCallsToday}/${state.maxCloudCallsPerDayInput} calls")
                }
            }.joinToString(" · ")
            if (statusText.isNotBlank()) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.72f),
                )
            }
            if (state.mode == MemoryMaintenanceMode.LOCAL_THEN_CLOUD) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = state.dailyCloudTokenLimitInput,
                        onValueChange = { onAction(MemoryAction.SetDailyTokenLimit(it)) },
                        label = { Text("Tokens/day") },
                        modifier = Modifier.width(124.dp),
                        singleLine = true,
                        colors = memoryTextFieldColors(),
                    )
                    OutlinedTextField(
                        value = state.maxCloudCallsPerDayInput,
                        onValueChange = { onAction(MemoryAction.SetDailyCallLimit(it)) },
                        label = { Text("Calls/day") },
                        modifier = Modifier.width(116.dp),
                        singleLine = true,
                        colors = memoryTextFieldColors(),
                    )
                }
            }
        }
    }
}

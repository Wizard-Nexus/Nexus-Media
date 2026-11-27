package com.nuvio.tv.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.livetv.LiveTvCategories
import com.nuvio.tv.data.livetv.LiveTvCategorySort
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun LiveTvCategoriesDialog(
    providerOrder: List<String>,
    preferences: LiveTvCategories,
    onUpdate: ((LiveTvCategories) -> LiveTvCategories) -> Unit,
    onDismiss: () -> Unit
) {
    val order = remember(providerOrder, preferences.sort, preferences.customOrder) { preferences.ordered(providerOrder) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val picked = selected.intersect(providerOrder.toSet())
    var pickingSort by remember { mutableStateOf(false) }
    val firstGroup = remember { FocusRequester() }
    val sortButton = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scrollHeight = (LocalConfiguration.current.screenHeightDp - 154).coerceIn(190, 354).dp

    LaunchedEffect(order) {
        if (picked.isEmpty()) {
            listState.scrollToItem(0)
        } else {
            val firstSelected = order.indexOfFirst { it in picked }
            if (firstSelected >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == firstSelected }) {
                listState.scrollToItem(firstSelected)
            }
        }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.live_tv_manage_groups),
        subtitle = stringResource(R.string.live_tv_manage_groups_help),
        width = 840.dp,
        usePlatformDefaultWidth = false,
        contentSpacing = 10.dp
    ) {
        Text(
            stringResource(R.string.live_tv_groups_selected, picked.size),
            color = NuvioTheme.colors.TextSecondary,
            style = MaterialTheme.typography.labelMedium
        )
        Row(Modifier.fillMaxWidth().height(scrollHeight), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LazyColumn(Modifier.weight(1f), state = listState) {
                itemsIndexed(order, key = { _, group -> group }) { index, group ->
                    val marked = group in picked
                    SettingsActionRow(
                        title = group,
                        subtitle = null,
                        value = stringResource(
                            if (group in preferences.hidden) R.string.live_tv_group_hidden else R.string.live_tv_group_visible
                        ),
                        titleTrailingIcon = if (marked) Icons.Default.Check else null,
                        modifier = (if (index == 0) Modifier.focusRequester(firstGroup) else Modifier)
                            .focusProperties { right = sortButton }
                            .semantics { this.selected = marked },
                        onClick = { selected = if (marked) selected - group else selected + group }
                    )
                }
            }
            Column(Modifier.width(240.dp).verticalScroll(rememberScrollState())) {
                SettingsActionRow(
                    title = stringResource(R.string.live_tv_groups_sort),
                    subtitle = stringResource(categorySortLabel(preferences.sort)),
                    modifier = Modifier.focusRequester(sortButton),
                    onClick = { pickingSort = true }
                )
                SettingsActionRow(
                    title = stringResource(R.string.live_tv_groups_move_up),
                    subtitle = null,
                    enabled = preferences.moveSelected(order, picked, down = false) != preferences,
                    onClick = { onUpdate { it.moveSelected(it.ordered(providerOrder), picked, down = false) } }
                )
                SettingsActionRow(
                    title = stringResource(R.string.live_tv_groups_move_down),
                    subtitle = null,
                    enabled = preferences.moveSelected(order, picked, down = true) != preferences,
                    onClick = { onUpdate { it.moveSelected(it.ordered(providerOrder), picked, down = true) } }
                )
                SettingsActionRow(
                    title = stringResource(R.string.live_tv_groups_hide),
                    subtitle = null,
                    enabled = picked.any { it !in preferences.hidden },
                    onClick = { onUpdate { it.copy(hidden = it.hidden + picked) } }
                )
                SettingsActionRow(
                    title = stringResource(R.string.live_tv_groups_show),
                    subtitle = null,
                    enabled = picked.any(preferences.hidden::contains),
                    onClick = { onUpdate { it.copy(hidden = it.hidden - picked) } }
                )
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            firstGroup.requestFocus()
        }
        if (pickingSort) {
            SettingsSingleChoiceDialog(
                title = stringResource(R.string.live_tv_groups_sort),
                options = LiveTvCategorySort.entries.map { SettingsPickerOption(it, stringResource(categorySortLabel(it))) },
                selectedValue = preferences.sort,
                onOptionSelected = { sort ->
                    onUpdate { it.copy(sort = sort) }
                    pickingSort = false
                },
                onDismiss = { pickingSort = false }
            )
        }
    }
}

@StringRes
private fun categorySortLabel(sort: LiveTvCategorySort): Int = when (sort) {
    LiveTvCategorySort.PROVIDER -> R.string.live_tv_groups_provider_order
    LiveTvCategorySort.NAME_ASCENDING -> R.string.live_tv_groups_ascending
    LiveTvCategorySort.NAME_DESCENDING -> R.string.live_tv_groups_descending
    LiveTvCategorySort.CUSTOM -> R.string.live_tv_groups_custom
}

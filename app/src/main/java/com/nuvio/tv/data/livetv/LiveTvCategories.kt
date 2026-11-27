package com.nuvio.tv.data.livetv

import kotlinx.serialization.Serializable

@Serializable
internal enum class LiveTvCategorySort { PROVIDER, NAME_ASCENDING, NAME_DESCENDING, CUSTOM }

@Serializable
internal data class LiveTvCategories(
    val sort: LiveTvCategorySort = LiveTvCategorySort.PROVIDER,
    val customOrder: List<String> = emptyList(),
    val hidden: Set<String> = emptySet()
) {
    fun ordered(providerOrder: List<String>): List<String> = when (sort) {
        LiveTvCategorySort.PROVIDER -> providerOrder
        LiveTvCategorySort.NAME_ASCENDING -> providerOrder.sortedWith(String.CASE_INSENSITIVE_ORDER)
        LiveTvCategorySort.NAME_DESCENDING -> providerOrder.sortedWith(String.CASE_INSENSITIVE_ORDER.reversed())
        LiveTvCategorySort.CUSTOM -> {
            val available = providerOrder.toHashSet()
            val saved = customOrder.filter(available::contains)
            saved + (providerOrder - saved.toHashSet())
        }
    }

    fun moveSelected(order: List<String>, selected: Set<String>, down: Boolean): LiveTvCategories {
        val moving = order.filter(selected::contains)
        if (moving.isEmpty()) return this
        val remaining = order.filterNot(selected::contains)
        val anchor = if (down) moving.last() else moving.first()
        val before = order.takeWhile { it != anchor }.count { it !in selected }
        val index = (before + if (down) 1 else -1).coerceIn(0, remaining.size)
        val reordered = remaining.take(index) + moving + remaining.drop(index)
        if (reordered == order) return this
        return copy(sort = LiveTvCategorySort.CUSTOM, customOrder = reordered + (customOrder - order.toHashSet()))
    }
}

internal fun XtreamCatalog.groupNames(): List<String> {
    val used = channels.mapNotNull(LiveChannel::group).toSet()
    return categoryOrder.filter(used::contains) + (used - categoryOrder.toHashSet())
}

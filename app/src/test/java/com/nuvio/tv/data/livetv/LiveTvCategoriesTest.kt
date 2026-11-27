package com.nuvio.tv.data.livetv

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTvCategoriesTest {
    private val provider = listOf("Sport", "Drama", "News", "Kids", "Music")

    @Test
    fun selectedGroupsMoveTogetherInTheirExistingOrder() {
        val before = LiveTvCategories(hidden = setOf("Kids"))
        val moved = before.moveSelected(provider, setOf("Kids", "Drama"), down = false)
        assertEquals(listOf("Drama", "Kids", "Sport", "News", "Music"), moved.ordered(provider))
        assertEquals(moved, moved.moveSelected(moved.ordered(provider), setOf("Kids", "Drama"), down = false))
        val down = moved.moveSelected(moved.ordered(provider), setOf("Kids", "Drama"), down = true)
        assertEquals(listOf("Sport", "Drama", "Kids", "News", "Music"), down.ordered(provider))
        assertEquals(before.hidden, down.hidden)
    }

    @Test
    fun sortingKeepsCustomOrderForWhenItIsSelectedAgain() {
        val custom = LiveTvCategories(LiveTvCategorySort.CUSTOM, listOf("News", "Music", "Missing"))
        assertEquals(listOf("News", "Music", "Sport", "Drama", "Kids"), custom.ordered(provider))
        assertEquals(
            listOf("Drama", "Kids", "Music", "News", "Sport"),
            custom.copy(sort = LiveTvCategorySort.NAME_ASCENDING).ordered(provider)
        )
        assertEquals(listOf("Sport", "News", "Music", "Kids", "Drama"), custom.copy(sort = LiveTvCategorySort.NAME_DESCENDING).ordered(provider))
    }
}

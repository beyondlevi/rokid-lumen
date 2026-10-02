package dev.lumen.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class GridStoreTest {
    private val n = "notifications"
    private val s = "settings"

    @Test
    fun `nothing stored gives the web apps, settings`() {
        assertEquals(listOf("web:a", "web:b", s), GridStore.resolve(emptyList(), emptySet(), listOf("web:a", "web:b"), emptySet()))
    }

    @Test
    fun `the phone's order holds, gone items and notifications (a tab now) drop out`() {
        val order = listOf("app:x", "web:b", s, n, "web:gone", "app:gone")
        assertEquals(listOf("app:x", "web:b", s), GridStore.resolve(order, emptySet(), listOf("web:b"), setOf("app:x")))
    }

    @Test
    fun `a new web app joins before settings when settings is last`() {
        val order = listOf(n, "web:a", s)
        assertEquals(listOf("web:a", "web:new", s), GridStore.resolve(order, emptySet(), listOf("web:a", "web:new"), emptySet()))
        val settingsFirst = listOf(s, n, "web:a")
        assertEquals(listOf(s, "web:a", "web:new"), GridStore.resolve(settingsFirst, emptySet(), listOf("web:a", "web:new"), emptySet()))
    }

    @Test
    fun `hidden items stay out, settings never does`() {
        val hidden = setOf(n, "web:b", s)
        assertEquals(listOf("web:a", s), GridStore.resolve(listOf("web:a"), hidden, listOf("web:a", "web:b"), emptySet()))
        assertEquals(listOf(s), GridStore.resolve(listOf(n, n), emptySet(), emptyList(), emptySet()))
    }
}

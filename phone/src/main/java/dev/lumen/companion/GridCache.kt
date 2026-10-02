package dev.lumen.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import dev.lumen.protocol.GridEvent
import dev.lumen.protocol.GridItem
import dev.lumen.protocol.GridOps

/**
 * The glasses' apps grid as they last described it ([GridEvent]), with the icons received so
 * far. The glasses own the grid: changes go to them ([GridOps]) and come back as a new state.
 */
object GridCache {
    @Volatile var items: List<GridItem> = emptyList()
        private set

    @Volatile var available: List<GridItem> = emptyList()
        private set

    @Volatile var known = false
        private set

    val icons = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private val iconStamps = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The last refusal from the glasses, until the next state. */
    @Volatile var lastError: GridEvent.Result? = null
        private set

    val listeners = mutableSetOf<() -> Unit>()

    fun onEvent(event: GridEvent) {
        when (event) {
            is GridEvent.State -> {
                // An icon that changed on the glasses (an update, a page's new favicon) is asked for again.
                (event.items + event.available).forEach { item ->
                    if (iconStamps.put(item.id, item.iconStamp).let { it != null && it != item.iconStamp }) icons.remove(item.id)
                }
                items = event.items
                available = event.available
                known = true
            }
            is GridEvent.Result -> if (!PackageShare.onResult(event)) lastError = event.takeIf { !it.ok }
            is GridEvent.Icon -> runCatching {
                val bytes = Base64.decode(event.png, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { icons[event.id] = it }
            }
        }
        listeners.toList().forEach { it() }
    }

    /** Web and native items whose icon hasn't arrived: ask the glasses for them. */
    fun missingIcons(): List<String> = (items + available)
        .filter { (it.kind == GridItem.Kind.WEB || it.kind == GridItem.Kind.NATIVE) && !icons.containsKey(it.id) }
        .map { it.id }

    /** The grid with [id] added: before Settings when Settings is last, else at the end. */
    fun added(id: String): List<String> {
        val order = items.map { it.id }.filter { it != id }.toMutableList()
        val settings = order.indexOf(GridItem.SETTINGS_ID)
        if (settings >= 0 && settings == order.lastIndex) order.add(settings, id) else order += id
        return order
    }

    /** What must stay hidden given a new [order]: web apps and Notifications left out of it. */
    fun hiddenFor(order: List<String>): List<String> = (items + available)
        .filter { it.id !in order && (it.kind == GridItem.Kind.WEB || it.kind == GridItem.Kind.NOTIFICATIONS) }
        .map { it.id }
}

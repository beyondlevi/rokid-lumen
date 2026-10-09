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
 * Until they do, [items] and [available] show the changes on their way ([GridRequests]) as made,
 * so the screen answers at once whatever Rokid's link does with the message.
 */
object GridCache {
    @Volatile private var stateItems: List<GridItem> = emptyList()

    @Volatile private var stateAvailable: List<GridItem> = emptyList()

    /** The grid with the changes still on their way applied. */
    val items: List<GridItem> get() = view().first

    val available: List<GridItem> get() = view().second

    @Volatile var known = false
        private set

    val icons = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private val iconStamps = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The last refusal from the glasses, until the next state. */
    @Volatile var lastError: GridEvent.Result? = null
        private set

    val listeners = mutableSetOf<() -> Unit>()

    fun onEvent(event: GridEvent) {
        GridRequests.onEvent(event)
        when (event) {
            is GridEvent.State -> {
                // An icon that changed on the glasses (an update, a page's new favicon) is asked for again.
                (event.items + event.available).forEach { item ->
                    if (iconStamps.put(item.id, item.iconStamp).let { it != null && it != item.iconStamp }) icons.remove(item.id)
                }
                stateItems = event.items
                stateAvailable = event.available
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

    /** Items with a change on its way to the glasses. */
    fun sending(): Set<String> = GridRequests.pending.values.map { it.item }.filter { it.isNotEmpty() }.toSet()

    /** Configuration fields with a value on its way, as `<item id>/<key>`. */
    fun sendingFields(): Set<String> = GridRequests.pending.values.filter { it.op == GridOps.CONFIG }
        .map { "${it.item}/${it.json.optString("key")}" }.toSet()

    /** The last state with every change still on its way applied, oldest first. */
    private fun view(): Pair<List<GridItem>, List<GridItem>> =
        GridEvent.State(stateItems, stateAvailable).with(GridRequests.pending.values.map { it.json }).let { it.items to it.available }

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

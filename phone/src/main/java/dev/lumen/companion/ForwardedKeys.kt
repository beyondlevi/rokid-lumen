package dev.lumen.companion

/**
 * The keys of the notifications sent to the glasses, newest kept (at most [capacity]): a dismiss
 * from the glasses clears only those from the phone, never another app's notification named by
 * key. Thread-safe.
 */
class ForwardedKeys(private val capacity: Int) {
    private val keys = LinkedHashSet<String>()

    /** Sent to the glasses (again): it becomes the newest. */
    @Synchronized
    fun add(key: String) {
        keys.remove(key)
        keys.add(key)
        while (keys.size > capacity) keys.remove(keys.first())
    }

    /** Gone from the phone (and so from the glasses). */
    @Synchronized
    fun remove(key: String) {
        keys.remove(key)
    }

    /** The glasses start over ([dev.lumen.protocol.NotifyEvent.reset]). */
    @Synchronized
    fun clear() = keys.clear()

    @Synchronized
    operator fun contains(key: String): Boolean = key in keys

    /** Those of [asked] the glasses were sent, in the order asked. */
    @Synchronized
    fun sentOf(asked: List<String>): List<String> = asked.filter { it in keys }

    @get:Synchronized
    val size: Int get() = keys.size
}

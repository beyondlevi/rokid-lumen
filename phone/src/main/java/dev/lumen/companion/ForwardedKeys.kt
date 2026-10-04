package dev.lumen.companion

/**
 * The keys of the notifications sent to the glasses, newest kept (at most [capacity]): a dismiss
 * from the glasses clears only those from the phone, never another app's notification named by
 * key. Each key keeps a digest of what it last said, so a re-post saying the same isn't sent
 * again. Thread-safe.
 */
class ForwardedKeys(private val capacity: Int) {
    private val keys = LinkedHashMap<String, Int>()

    /** Sent to the glasses (again), saying [content]: it becomes the newest. */
    @Synchronized
    fun add(key: String, content: Int = 0) {
        keys.remove(key)
        keys[key] = content
        while (keys.size > capacity) keys.remove(keys.keys.first())
    }

    /** Gone from the phone (and so from the glasses); false when the glasses never had it. */
    @Synchronized
    fun remove(key: String): Boolean = keys.remove(key) != null

    /** The glasses start over ([dev.lumen.protocol.NotifyEvent.reset]). */
    @Synchronized
    fun clear() = keys.clear()

    @Synchronized
    operator fun contains(key: String): Boolean = key in keys

    /** The glasses already have [key] saying [content]. */
    @Synchronized
    fun isUnchanged(key: String, content: Int): Boolean = keys[key] == content

    /** Those of [asked] the glasses were sent, in the order asked. */
    @Synchronized
    fun sentOf(asked: List<String>): List<String> = asked.filter { it in keys }

    @get:Synchronized
    val size: Int get() = keys.size
}

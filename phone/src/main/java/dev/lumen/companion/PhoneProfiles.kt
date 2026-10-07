package dev.lumen.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gesture profiles for the band on the phone: each is a whole gesture map ("Media", "Navigation",
 * or one of the person's own), with what pinch and turn does when nothing plays and whether the
 * band still counts while the phone is locked. One is active; a switch gesture, the same in
 * every profile, moves to the next one (and any gesture can be mapped to the next, the previous
 * or a given profile). The wrist stays one setting for all.
 *
 * Pinch and turn is contextual: while audio plays it's the volume, otherwise [Profile.dial].
 */
object PhoneProfiles {
    /** What pinch and turn does while nothing plays (with audio it's always the volume). */
    val DIALS = linkedMapOf(
        "brightness" to "Brightness",
        "arrows" to "Arrow up and down",
        "volume" to "Volume",
        "none" to "Nothing",
    )

    const val NEXT = "profile.next"
    const val PREVIOUS = "profile.previous"
    /** `profile.go:<id>`: to that profile. */
    const val GO_PREFIX = "profile.go:"

    /** Pinch and turn's two action names; [PhoneBand] picks volume or [Profile.dial] at each step. */
    const val DIAL_UP = "dial.up"
    const val DIAL_DOWN = "dial.down"

    data class Profile(
        val id: String,
        val name: String,
        /** `media`, `navigation` or `custom`: the chip's icon. */
        val kind: String,
        val actions: Map<String, String>,
        val apps: Map<String, String> = emptyMap(),
        val dial: String = "brightness",
        /** The band still counts while the phone is locked (media: yes; navigation: no). */
        val whenLocked: Boolean = false,
    ) {
        fun action(gesture: String) = actions[gesture] ?: PhoneSettings.GESTURE_DEFAULTS[gesture] ?: PhoneSettings.NONE
        fun app(gesture: String) = apps[gesture].orEmpty()

        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("name", name).put("kind", kind).put("dial", dial).put("when_locked", whenLocked)
            .put("actions", JSONObject(actions)).put("apps", JSONObject(apps))

        companion object {
            fun fromJson(json: JSONObject) = Profile(
                id = json.getString("id"),
                name = json.optString("name"),
                kind = json.optString("kind", "custom"),
                actions = json.optJSONObject("actions")?.toMap().orEmpty(),
                apps = json.optJSONObject("apps")?.toMap().orEmpty(),
                dial = json.optString("dial", "brightness").takeIf { it in DIALS } ?: "brightness",
                whenLocked = json.optBoolean("when_locked", false),
            )

            private fun JSONObject.toMap(): Map<String, String> = keys().asSequence().associateWith { optString(it) }
        }
    }

    data class State(val profiles: List<Profile>, val active: String, val switchGesture: String) {
        val current: Profile get() = profiles.firstOrNull { it.id == active } ?: profiles.first()
    }

    /** The media layout (the original app's defaults): play, tracks, mute; it counts while locked. */
    fun media(name: String = "Media", actions: Map<String, String> = MEDIA_ACTIONS) =
        Profile("media", name, "media", actions, dial = "brightness", whenLocked = true)

    /** Arrows and Enter, Back on the middle tap: moving through any app's screen. */
    fun navigation(name: String = "Navigation") = Profile(
        "navigation", name, "navigation",
        mapOf(
            "swipe_up" to "key.dpad_up", "swipe_down" to "key.dpad_down",
            "swipe_left" to "key.dpad_left", "swipe_right" to "key.dpad_right",
            "index_tap" to "key.enter", "middle_tap" to "screen.back",
        ),
        dial = "arrows", whenLocked = false,
    )

    private val MEDIA_ACTIONS = mapOf(
        "swipe_up" to "media.previous", "swipe_down" to "media.next",
        "index_tap" to "media.play_pause", "middle_tap" to "volume.mute",
    )

    /** The switch gesture when nothing says otherwise. */
    const val DEFAULT_SWITCH = "middle_double"

    /** Where the switch gesture goes in a migrated map, first free one first. */
    private val SWITCH_CANDIDATES = listOf("middle_double", "index_double", "middle_tap", "swipe_left", "swipe_right", "swipe_up", "swipe_down", "index_tap")

    /**
     * The profiles from the single gesture map that came before them. A map never changed from
     * the defaults becomes "Media" (the middle double tap switching, the mute on the middle tap),
     * with "Navigation" after it. A changed map is the person's own: it becomes "My layout",
     * active, kept as it was and still counting while locked (as before), with the two ready
     * ones after it, and the switch gesture on a gesture it doesn't use (none if it uses all).
     */
    fun migrate(
        actions: Map<String, String>,
        apps: Map<String, String>,
        dial: String,
        names: Names = Names(),
    ): State {
        val dialMode = if (dial == "none") "none" else "brightness"
        val media = media(names.media).copy(dial = dialMode)
        val navigation = navigation(names.navigation)
        val used = actions.filterValues { it != PhoneSettings.NONE }
        if (used == PhoneSettings.DEFAULTS) return State(listOf(media, navigation), media.id, DEFAULT_SWITCH)
        val mine = Profile("mine", names.mine, "custom", used, apps, dialMode, whenLocked = true)
        val switch = SWITCH_CANDIDATES.firstOrNull { it !in used } ?: PhoneSettings.NONE
        return State(listOf(mine, media, navigation), mine.id, switch)
    }

    /** The ready profiles' names, in the phone's language. */
    data class Names(val mine: String = "My layout", val media: String = "Media", val navigation: String = "Navigation")

    /** The bridge's `gesture=action;…` for [profile], with the switch gesture and the wrist. */
    fun mapping(profile: Profile, switchGesture: String, hand: String): String =
        PhoneSettings.GESTURES.joinToString(";") { gesture ->
            val action = if (gesture == switchGesture) NEXT else when (val id = profile.action(gesture)) {
                PhoneSettings.NONE -> ""
                PhoneSettings.OPEN_APP -> profile.app(gesture).takeIf { it.isNotEmpty() }?.let { PhoneSettings.APP_PREFIX + it } ?: ""
                else -> id
            }
            "$gesture=$action"
        } + ";dial_up=$DIAL_UP;dial_down=$DIAL_DOWN;hand=$hand"

    /** The profile after (or before) the active one, around the list. */
    fun step(state: State, delta: Int): String {
        val index = state.profiles.indexOfFirst { it.id == state.active }.coerceAtLeast(0)
        return state.profiles[Math.floorMod(index + delta, state.profiles.size)].id
    }

    // ---- Storage ----

    /** The state as last read or saved (pinch and turn asks at every step). */
    @Volatile private var cached: State? = null

    fun state(context: Context): State = cached ?: load(context).also { cached = it }

    private fun load(context: Context): State {
        val prefs = prefs(context)
        val stored = prefs.getString(KEY_PROFILES, null)?.let { text ->
            runCatching {
                val array = JSONArray(text)
                (0 until array.length()).map { Profile.fromJson(array.getJSONObject(it)) }
            }.getOrNull()
        }?.takeIf { it.isNotEmpty() }
        if (stored == null) {
            val migrated = migrate(
                PhoneSettings.GESTURES.associateWith { PhoneSettings.action(context, it) },
                PhoneSettings.GESTURES.associateWith { PhoneSettings.app(context, it) }.filterValues { it.isNotEmpty() },
                PhoneSettings.dial(context),
                Names(context.getString(R.string.profile_mine), context.getString(R.string.profile_media), context.getString(R.string.profile_navigation)),
            )
            save(context, migrated)
            return migrated
        }
        val active = prefs.getString(KEY_ACTIVE, null)?.takeIf { id -> stored.any { it.id == id } } ?: stored.first().id
        val switch = prefs.getString(KEY_SWITCH, DEFAULT_SWITCH)?.takeIf { it == PhoneSettings.NONE || it in PhoneSettings.GESTURES } ?: DEFAULT_SWITCH
        return State(stored, active, switch)
    }

    fun save(context: Context, state: State) {
        cached = state
        prefs(context).edit()
            .putString(KEY_PROFILES, JSONArray(state.profiles.map { it.toJson() }).toString())
            .putString(KEY_ACTIVE, state.active)
            .putString(KEY_SWITCH, state.switchGesture)
            .apply()
    }

    fun update(context: Context, change: (State) -> State): State = change(state(context)).also { save(context, it) }

    /** Replace the profile with [id]. */
    fun edit(context: Context, id: String, change: (Profile) -> Profile) = update(context) { state ->
        state.copy(profiles = state.profiles.map { if (it.id == id) change(it) else it })
    }

    /** A new profile after the others, copying [from] (or empty); returns its id. */
    fun add(context: Context, name: String, from: Profile?): String {
        val id = "p" + System.currentTimeMillis().toString(36)
        update(context) { state ->
            val profile = (from ?: Profile(id, name, "custom", emptyMap())).copy(id = id, name = name, kind = "custom")
            state.copy(profiles = state.profiles + profile)
        }
        return id
    }

    /** Removes a profile (never the last one); the active one moves to the first. */
    fun remove(context: Context, id: String) = update(context) { state ->
        if (state.profiles.size <= 1) state
        else {
            val rest = state.profiles.filterNot { it.id == id }
            // Gestures mapped to the removed profile go nowhere now.
            val cleaned = rest.map { profile ->
                profile.copy(actions = profile.actions.mapValues { (_, action) -> if (action == GO_PREFIX + id) PhoneSettings.NONE else action })
            }
            state.copy(profiles = cleaned, active = if (state.active == id) cleaned.first().id else state.active)
        }
    }

    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "active"
    private const val KEY_SWITCH = "switch_gesture"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("phone_profiles", Context.MODE_PRIVATE)
}

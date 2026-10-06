package dev.lumen.companion.computer

import android.content.Context
import dev.lumen.companion.PhoneProfiles
import dev.lumen.companion.PhoneSettings
import dev.lumen.companion.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gesture profiles for a computer, the band's "other device": the phone is its Bluetooth
 * keyboard and mouse ([ComputerLink]), and these say what each gesture sends ([ComputerKeys]),
 * or that it writes ([ComputerKeys.WRITE]). Kept apart from the phone's own profiles: the actions
 * are a computer's. The switch gesture is the phone's ([PhoneProfiles.State.switchGesture]), the
 * same everywhere; the band always counts here, locked phone or not (it's in a pocket).
 */
object ComputerProfiles {
    /** What pinch and turn does (no audio check: the phone can't see what the computer plays). */
    val DIALS = listOf("scroll", "volume", "brightness", "arrows", "none")

    /** The actions a gesture can have, in the picker's groups ([PhoneSettings.NONE] and the profile switches aside). */
    val GROUPS = linkedMapOf(
        "writing" to listOf(ComputerKeys.WRITE),
        "mouse" to listOf(ComputerKeys.POINTER),
        "keys" to listOf("pc.key.up", "pc.key.down", "pc.key.left", "pc.key.right", "pc.key.enter", "pc.key.escape", "pc.key.tab", "pc.key.space", "pc.key.backspace"),
        "desktops" to listOf("pc.desktop.next", "pc.desktop.previous", "pc.mission_control", "pc.app_switch"),
        "scroll" to listOf("pc.scroll.up", "pc.scroll.down"),
        "media" to listOf("pc.media.play_pause", "pc.media.next", "pc.media.previous", "pc.volume.up", "pc.volume.down", "pc.volume.mute", "pc.brightness.up", "pc.brightness.down"),
    )

    private val ACTIONS = GROUPS.values.flatten().toSet()

    fun isAction(id: String) = id in ACTIONS || id == PhoneSettings.NONE || id == PhoneProfiles.NEXT ||
        id == PhoneProfiles.PREVIOUS || id.startsWith(PhoneProfiles.GO_PREFIX)

    data class Profile(
        val id: String,
        val name: String,
        /** `notebook`, `presentation` or `custom`: the chip's icon. */
        val kind: String,
        val actions: Map<String, String>,
        val dial: String = "scroll",
    ) {
        fun action(gesture: String) = actions[gesture] ?: PhoneSettings.NONE

        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("name", name).put("kind", kind).put("dial", dial).put("actions", JSONObject(actions))

        companion object {
            fun fromJson(json: JSONObject): Profile {
                val actions = json.optJSONObject("actions")
                return Profile(
                    id = json.getString("id"),
                    name = json.optString("name"),
                    kind = json.optString("kind", "custom"),
                    actions = actions?.keys()?.asSequence()?.associateWith { actions.optString(it) }?.filterValues { isAction(it) }.orEmpty(),
                    dial = json.optString("dial", "scroll").takeIf { it in DIALS } ?: "scroll",
                )
            }
        }
    }

    data class State(val profiles: List<Profile>, val active: String) {
        val current: Profile get() = profiles.firstOrNull { it.id == active } ?: profiles.first()
    }

    /** The ready profiles' names, in the phone's language. */
    data class Names(val notebook: String = "Notebook", val presentation: String = "Presentation")

    /**
     * Scrolling, desktops, Enter and Esc, and writing on the double tap the switch doesn't take
     * ([switchGesture] is the phone's, the same in every profile).
     */
    fun notebook(name: String, switchGesture: String): Profile {
        val write = listOf("middle_double", "index_double").firstOrNull { it != switchGesture }
        return Profile(
            "notebook", name, "notebook",
            mapOf(
                "swipe_up" to "pc.scroll.up", "swipe_down" to "pc.scroll.down",
                "swipe_left" to "pc.desktop.next", "swipe_right" to "pc.desktop.previous",
                "index_tap" to "pc.key.enter", "middle_tap" to "pc.key.escape",
            ) + listOfNotNull(write?.let { it to ComputerKeys.WRITE }),
            dial = "scroll",
        )
    }

    /** Slides: forward and back with the arrows. */
    fun presentation(name: String) = Profile(
        "presentation", name, "presentation",
        mapOf(
            "swipe_left" to "pc.key.right", "swipe_right" to "pc.key.left",
            "index_tap" to "pc.key.right", "middle_tap" to "pc.key.left",
        ),
        dial = "volume",
    )

    fun defaults(names: Names, switchGesture: String) = State(
        listOf(notebook(names.notebook, switchGesture), presentation(names.presentation)), "notebook",
    )

    /** The bridge's `gesture=action;…` for [profile], with the phone's switch gesture and wrist. */
    fun mapping(profile: Profile, switchGesture: String, hand: String): String =
        PhoneSettings.GESTURES.joinToString(";") { gesture ->
            val action = if (gesture == switchGesture) PhoneProfiles.NEXT else profile.action(gesture).takeIf { it != PhoneSettings.NONE }.orEmpty()
            "$gesture=$action"
        } + ";dial_up=${PhoneProfiles.DIAL_UP};dial_down=${PhoneProfiles.DIAL_DOWN};hand=$hand"

    /** Pinch and turn's step on the computer, by the profile's [Profile.dial]. */
    fun dialAction(dial: String, up: Boolean): String? = when (dial) {
        "scroll" -> if (up) "pc.wheel.up" else "pc.wheel.down"
        "volume" -> if (up) "pc.volume.up" else "pc.volume.down"
        "brightness" -> if (up) "pc.brightness.up" else "pc.brightness.down"
        "arrows" -> if (up) "pc.key.up" else "pc.key.down"
        else -> null
    }

    /** The profile after (or before) the active one, around the list. */
    fun step(state: State, delta: Int): String {
        val index = state.profiles.indexOfFirst { it.id == state.active }.coerceAtLeast(0)
        return state.profiles[Math.floorMod(index + delta, state.profiles.size)].id
    }

    // ---- Storage ----

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
            val names = Names(context.getString(R.string.computer_profile_notebook), context.getString(R.string.computer_profile_presentation))
            return defaults(names, PhoneProfiles.state(context).switchGesture).also { save(context, it) }
        }
        val active = prefs.getString(KEY_ACTIVE, null)?.takeIf { id -> stored.any { it.id == id } } ?: stored.first().id
        return State(stored, active)
    }

    fun save(context: Context, state: State) {
        cached = state
        prefs(context).edit()
            .putString(KEY_PROFILES, JSONArray(state.profiles.map { it.toJson() }).toString())
            .putString(KEY_ACTIVE, state.active)
            .apply()
    }

    fun update(context: Context, change: (State) -> State): State = change(state(context)).also { save(context, it) }

    fun edit(context: Context, id: String, change: (Profile) -> Profile) = update(context) { state ->
        state.copy(profiles = state.profiles.map { if (it.id == id) change(it) else it })
    }

    /** A new profile after the others, copying [from] (or empty); returns its id. */
    fun add(context: Context, name: String, from: Profile?): String {
        val id = "c" + System.currentTimeMillis().toString(36)
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
            val rest = state.profiles.filterNot { it.id == id }.map { profile ->
                profile.copy(actions = profile.actions.mapValues { (_, action) -> if (action == PhoneProfiles.GO_PREFIX + id) PhoneSettings.NONE else action })
            }
            state.copy(profiles = rest, active = if (state.active == id) rest.first().id else state.active)
        }
    }

    // The computer's keyboard layout and the wheel's direction.

    fun layout(context: Context) = ComputerKeys.Layout.of(prefs(context).getString(KEY_LAYOUT, null))

    fun setLayout(context: Context, layout: ComputerKeys.Layout) = prefs(context).edit().putString(KEY_LAYOUT, layout.id).apply()

    fun invertScroll(context: Context) = prefs(context).getBoolean(KEY_INVERT, false)

    fun scrollSteps(context: Context) =
        prefs(context).getInt(KEY_SCROLL, ComputerKeys.SCROLL_DEFAULT).coerceIn(ComputerKeys.SCROLL_MIN, ComputerKeys.SCROLL_MAX)

    fun setScrollSteps(context: Context, steps: Int) =
        prefs(context).edit().putInt(KEY_SCROLL, steps.coerceIn(ComputerKeys.SCROLL_MIN, ComputerKeys.SCROLL_MAX)).apply()

    fun setInvertScroll(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_INVERT, on).apply()

    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "active"
    private const val KEY_LAYOUT = "layout"
    private const val KEY_INVERT = "invert_scroll"
    private const val KEY_SCROLL = "scroll_steps"
    private const val KEY_POINTER_SPEED = "pointer_speed"
    private const val KEY_POINTER_STEADINESS = "pointer_steadiness"
    private const val KEY_POINTER_BOOST = "pointer_boost"

    // The air mouse ([ComputerPointer]). The computer accelerates a mouse on its own, so the
    // band's flick boost starts at 1 (kinesis, which places the pointer itself, uses 1.6).

    /** Mouse counts per degree of forearm turn. */
    const val POINTER_SPEED_DEFAULT = 40
    val POINTER_SPEEDS = 10..100
    const val POINTER_STEADINESS_DEFAULT = 0.5f
    const val POINTER_BOOST_DEFAULT = 1.0f
    val POINTER_BOOSTS = 1.0f..2.5f

    fun pointerSpeed(context: Context) =
        prefs(context).getInt(KEY_POINTER_SPEED, POINTER_SPEED_DEFAULT).coerceIn(POINTER_SPEEDS)

    fun setPointerSpeed(context: Context, speed: Int) =
        prefs(context).edit().putInt(KEY_POINTER_SPEED, speed.coerceIn(POINTER_SPEEDS)).apply()

    /** 0 is the most responsive and 1 the steadiest (how still the arm must be to hold the pointer). */
    fun pointerSteadiness(context: Context) =
        prefs(context).getFloat(KEY_POINTER_STEADINESS, POINTER_STEADINESS_DEFAULT).coerceIn(0f, 1f)

    fun setPointerSteadiness(context: Context, steadiness: Float) =
        prefs(context).edit().putFloat(KEY_POINTER_STEADINESS, steadiness.coerceIn(0f, 1f)).apply()

    /** The most a quick flick multiplies the movement by. */
    fun pointerBoost(context: Context) =
        prefs(context).getFloat(KEY_POINTER_BOOST, POINTER_BOOST_DEFAULT).coerceIn(POINTER_BOOSTS)

    fun setPointerBoost(context: Context, boost: Float) =
        prefs(context).edit().putFloat(KEY_POINTER_BOOST, boost.coerceIn(POINTER_BOOSTS)).apply()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("computer_profiles", Context.MODE_PRIVATE)
}

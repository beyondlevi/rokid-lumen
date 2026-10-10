package dev.lumen.glasses

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLogicTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private val web = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT

    private fun field(
        inputType: Int,
        imeOptions: Int = 0,
        actionId: Int = 0,
        actionLabel: String? = null,
        hint: String? = null,
        label: String? = null,
        fieldId: Int = 7,
        pkg: String = "dev.lumen.glasses",
    ) = FocusedField.of(inputType, imeOptions, actionId, actionLabel, hint, label, fieldId, pkg)

    @Test
    fun noTextFieldIsNoField() {
        // GeckoView with nothing focused, a button: Android starts input with no type.
        assertNull(field(InputType.TYPE_NULL))
        assertNull(field(InputType.TYPE_NULL, EditorInfo.IME_ACTION_DONE, hint = "x"))
    }

    @Test
    fun aWebSearchFieldSearches() {
        val search = field(web, EditorInfo.IME_ACTION_SEARCH, hint = "  Search   YouTube ")!!
        assertEquals(FieldKind.TEXT, search.kind)
        assertFalse(search.multiline)
        assertEquals(EnterAction.SEARCH, search.action)
        assertEquals(EditorInfo.IME_ACTION_SEARCH, search.actionCode)
        assertEquals("Search YouTube", search.label)
        assertEquals("dev.lumen.glasses", search.packageName)
        assertTrue(search.action.named)
        assertEquals(R.string.keyboard_action_search, search.action.label)
    }

    @Test
    fun theKindsOfFields() {
        val kinds = mapOf(
            (text or InputType.TYPE_TEXT_VARIATION_PASSWORD) to FieldKind.PASSWORD,
            (text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) to FieldKind.PASSWORD,
            (text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) to FieldKind.PASSWORD,
            (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD) to FieldKind.PASSWORD,
            (text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) to FieldKind.EMAIL,
            (text or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) to FieldKind.EMAIL,
            (text or InputType.TYPE_TEXT_VARIATION_URI) to FieldKind.URL,
            InputType.TYPE_CLASS_NUMBER to FieldKind.NUMBER,
            InputType.TYPE_CLASS_PHONE to FieldKind.PHONE,
            InputType.TYPE_CLASS_DATETIME to FieldKind.TEXT,
            web to FieldKind.TEXT,
            (text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) to FieldKind.TEXT,
        )
        kinds.forEach { (type, kind) -> assertEquals("type $type", kind, field(type)!!.kind) }
        assertTrue(FieldKind.PASSWORD.secret)
        assertFalse(FieldKind.EMAIL.secret)
        // What the phone's keyboard is told, as the web shim named them.
        assertEquals("password", FieldKind.PASSWORD.phoneType)
        assertEquals("tel", FieldKind.PHONE.phoneType)
        assertEquals("email", FieldKind.EMAIL.phoneType)
    }

    @Test
    fun theEnterActions() {
        val actions = mapOf(
            EditorInfo.IME_ACTION_SEARCH to EnterAction.SEARCH,
            EditorInfo.IME_ACTION_SEND to EnterAction.SEND,
            EditorInfo.IME_ACTION_GO to EnterAction.GO,
            EditorInfo.IME_ACTION_NEXT to EnterAction.NEXT,
            EditorInfo.IME_ACTION_PREVIOUS to EnterAction.PREVIOUS,
            EditorInfo.IME_ACTION_DONE to EnterAction.DONE,
            // A single line that names nothing: a plain Enter key.
            EditorInfo.IME_ACTION_UNSPECIFIED to EnterAction.ENTER,
            EditorInfo.IME_ACTION_NONE to EnterAction.ENTER,
        )
        actions.forEach { (code, action) ->
            val described = field(web, code or EditorInfo.IME_FLAG_NO_FULLSCREEN)!!
            assertEquals("action $code", action, described.action)
            assertEquals(if (action == EnterAction.ENTER) 0 else code, described.actionCode)
        }
        val labels = mapOf(
            EnterAction.SEARCH to R.string.keyboard_action_search,
            EnterAction.SEND to R.string.keyboard_action_send,
            EnterAction.GO to R.string.keyboard_action_go,
            EnterAction.NEXT to R.string.keyboard_action_next,
            EnterAction.PREVIOUS to R.string.keyboard_action_previous,
            EnterAction.DONE to R.string.keyboard_action_done,
            EnterAction.ENTER to R.string.keyboard_action_enter,
        )
        labels.forEach { (action, label) -> assertEquals(action.name, label, action.label) }
        assertFalse(EnterAction.ENTER.named)
        assertFalse(EnterAction.NONE.named)
        assertTrue(EnterAction.CUSTOM.named)
    }

    @Test
    fun aMultiLineFieldsEnterIsANewLine() {
        val area = field(web or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION)!!
        assertTrue(area.multiline)
        assertEquals(EnterAction.NONE, area.action)
        assertEquals(0, area.actionCode)
        // Multi-line with an action of its own (a chat box whose Enter sends) keeps it.
        assertEquals(EnterAction.SEND, field(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_SEND)!!.action)
        assertEquals(EnterAction.NONE, field(text or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE)!!.action)
        // One line that wants Enter as a key: a key it is.
        assertEquals(EnterAction.ENTER, field(text, EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_ENTER_ACTION)!!.action)
    }

    @Test
    fun anAppsOwnActionLabel() {
        val wifi = field(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_DONE, actionId = 42, actionLabel = " Connect ", label = "Password")!!
        assertEquals(EnterAction.CUSTOM, wifi.action)
        assertEquals("Connect", wifi.customLabel)
        assertEquals(42, wifi.actionCode)
        assertEquals("Password", wifi.label)
        // Without an id of its own, the label goes with the field's action.
        assertEquals(EditorInfo.IME_ACTION_GO, field(text, EditorInfo.IME_ACTION_GO, actionLabel = "Sign in")!!.actionCode)
    }

    @Test
    fun fieldsAreToldApartWithoutTheirText() {
        val name = field(web, hint = "Name")!!
        assertEquals(name.key, field(web, hint = "Name")!!.key)
        assertNotEquals(name.key, field(web, hint = "Email")!!.key)
        assertNotEquals(name.key, field(web, hint = "Name", pkg = "com.android.settings")!!.key)
        assertNotEquals(name.key, field(web, hint = "Name", fieldId = 8)!!.key)
        // The log line names the kind and the app, nothing typed or labelled.
        val line = field(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, hint = "Secret label")!!.describe()
        assertTrue(line, line.startsWith("password"))
        assertFalse(line, line.contains("Secret"))
        // A long label is cut.
        assertEquals(80, field(text, hint = "x".repeat(200))!!.label.length)
    }

    @Test
    fun aPasswordIsNeverDictated() {
        assertEquals(listOf(KeyboardChoice.DICTATE, KeyboardChoice.WRITE, KeyboardChoice.PHONE), KeyboardChoice.forField(FieldKind.TEXT))
        assertEquals(listOf(KeyboardChoice.WRITE, KeyboardChoice.PHONE), KeyboardChoice.forField(FieldKind.PASSWORD))
        assertEquals(R.string.keyboard_choice_phone, KeyboardChoice.PHONE.label)
    }

    @Test
    fun editsTouchOnlyWhatChanged() {
        // A dictated phrase after the text, the cursor at the end: one insert.
        assertEquals(TextEdit(null, 0, " with cheese"), TextEdit.between("bread recipe", 12, 12, "bread recipe with cheese"))
        // The last word deleted.
        assertEquals(TextEdit(null, 5, ""), TextEdit.between("lofi girl", 9, 9, "lofi"))
        // An empty field takes it all.
        assertEquals(TextEdit(null, 0, "hello"), TextEdit.between("", 0, 0, "hello"))
        // The same text: nothing to do.
        assertNull(TextEdit.between("same", 1, 1, "same"))
        // The cursor elsewhere: it moves to the end of what changes first.
        assertEquals(TextEdit(4, 4, ""), TextEdit.between("abcdef", 0, 0, "ef"))
        assertEquals(TextEdit(3, 1, "XY"), TextEdit.between("abcdef", 6, 6, "abXYdef"))
        // A selection isn't the cursor: it collapses to where the edit is.
        assertEquals(TextEdit(5, 0, "!"), TextEdit.between("hello", 0, 5, "hello!"))
        // The phone's whole new value, replacing everything.
        assertEquals(TextEdit(null, 3, "xyz"), TextEdit.between("abc", 3, 3, "xyz"))
        // A repeated letter at the end: the insert goes at the end, not before the old one.
        assertEquals(TextEdit(null, 0, "a"), TextEdit.between("banana", 6, 6, "bananaa"))
    }

    @Test
    fun editsNeverSplitASurrogatePair() {
        // Two emoji sharing their first half: the whole of each is replaced.
        val edit = TextEdit.between("a😀", 3, 3, "a😁")!!
        assertEquals(null, edit.cursor)
        assertEquals(2, edit.deleteBefore)
        assertEquals("😁", edit.insert)
        // The same at the start of a text that ends alike.
        val front = TextEdit.between("😀b", 3, 3, "😁b")!!
        assertEquals(2, front.deleteBefore)
        assertEquals("😁", front.insert)
        assertEquals(2, front.cursor)
        // Alike second halves: the suffix stops short of one.
        val back = TextEdit.between("\uD83D\uDE00", 2, 2, "\uD83C\uDE00")!!
        assertEquals(2, back.deleteBefore)
        assertEquals("\uD83C\uDE00", back.insert)
    }

    @Test
    fun theOpenPanelTakesTheBand() {
        for (command in listOf(BandCommand.UP, BandCommand.DOWN, BandCommand.LEFT, BandCommand.RIGHT, BandCommand.FORWARD,
            BandCommand.BACKWARD, BandCommand.ACTIVATE, BandCommand.BACK, "glasses.home", "app:com.example")) {
            assertEquals(command, KeyboardRoute.PANEL, KeyboardRoute.of(panelOpen = true, field = true, hintShown = true, ownApp = false, bannerUp = false, command = command))
            // Before a banner too: it can't take the panel's gestures.
            assertEquals(command, KeyboardRoute.PANEL, KeyboardRoute.of(panelOpen = true, field = true, hintShown = true, ownApp = false, bannerUp = true, command = command))
        }
        // The volume and the brightness steps (pinch and turn) still work.
        for (command in listOf(BandCommand.VOLUME_UP, BandCommand.VOLUME_DOWN, BandCommand.BRIGHTNESS_UP, BandCommand.BRIGHTNESS_DOWN)) {
            assertEquals(command, KeyboardRoute.PASS, KeyboardRoute.of(panelOpen = true, field = true, hintShown = true, ownApp = true, bannerUp = false, command = command))
        }
    }

    @Test
    fun theIndexTapOnAFocusedFieldOpensThePanel() {
        // The hint shows (the app asked for a keyboard), in any app.
        assertEquals(KeyboardRoute.OPEN, KeyboardRoute.of(panelOpen = false, field = true, hintShown = true, ownApp = false, bannerUp = false, command = BandCommand.ACTIVATE))
        // A web app's field that didn't ask (focused by a script): activating it opens it anyway (MRBD's composer).
        assertEquals(KeyboardRoute.OPEN, KeyboardRoute.of(panelOpen = false, field = true, hintShown = false, ownApp = true, bannerUp = false, command = BandCommand.ACTIVATE))
        // Another app's field nobody asked a keyboard for (a list's search box): the tap is the app's.
        assertEquals(KeyboardRoute.PASS, KeyboardRoute.of(panelOpen = false, field = true, hintShown = false, ownApp = false, bannerUp = false, command = BandCommand.ACTIVATE))
        // A banner's index tap is the banner's.
        assertEquals(KeyboardRoute.PASS, KeyboardRoute.of(panelOpen = false, field = true, hintShown = true, ownApp = true, bannerUp = true, command = BandCommand.ACTIVATE))
        // No field: nothing for the keyboard.
        assertEquals(KeyboardRoute.PASS, KeyboardRoute.of(panelOpen = false, field = false, hintShown = false, ownApp = true, bannerUp = false, command = BandCommand.ACTIVATE))
        // Everything else goes on to the app: swipes move on, Back leaves the field.
        for (command in listOf(BandCommand.UP, BandCommand.DOWN, BandCommand.LEFT, BandCommand.RIGHT, BandCommand.BACK, BandCommand.VOLUME_UP)) {
            assertEquals(command, KeyboardRoute.PASS, KeyboardRoute.of(panelOpen = false, field = true, hintShown = true, ownApp = true, bannerUp = false, command = command))
        }
    }
}

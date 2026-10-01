package dev.lumen.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File

/**
 * The band's owner key and record in the app's private storage: `owner.key` (the
 * private key, 32 bytes, or 97 with the band's key) and `band.json` (address, name,
 * scheme guess), the same files the Linux app keeps in ~/.local/state/air-gestures.
 * `owner.pending.key` is a claim the band may have committed whose confirmation never
 * arrived; [BandLink] tries it first and confirms it. Neither app claims a band itself (Meta's
 * sign-in doesn't fit the HUD): the key comes from the original phone app's or the Linux app's
 * export, dropped into [dropFolder] on the glasses or picked from a file on the phone
 * ([import]). The same key works on both: the band talks to one of them at a time.
 */
object Identity {
    private const val BUNDLE_FORMAT = "air-gestures-band"
    private const val BUNDLE_VERSION = 1

    /** The names [importFromDropFolder] picks up. */
    val DROP_FILES = listOf("air-gestures-band.json", "owner.key", "band.json")

    fun keyFile(context: Context) = File(context.filesDir, "owner.key")
    fun pendingFile(context: Context) = File(context.filesDir, "owner.pending.key")
    fun bandFile(context: Context) = File(context.filesDir, "band.json")

    @JvmStatic
    fun present(context: Context) = keyFile(context).exists() || pendingFile(context).exists()

    /** The band's name from band.json, if there is one. */
    @JvmStatic
    fun bandName(context: Context): String? = runCatching {
        JSONObject(bandFile(context).readText()).optString("name").ifEmpty { null }
    }.getOrNull()

    /**
     * Where `adb push` can leave the export for the app: its external files directory,
     * which the app reads without a storage permission.
     */
    @JvmStatic
    fun dropFolder(context: Context): File? = context.getExternalFilesDir(null)

    /**
     * Import the files from [dropFolder] and delete them there (anyone holding them controls
     * the band). Returns what happened, for the status line.
     */
    @JvmStatic
    fun importFromDropFolder(context: Context): String {
        val folder = dropFolder(context) ?: return context.getString(R.string.identity_no_folder)
        val files = DROP_FILES.map { File(folder, it) }.filter { it.isFile }
        if (files.isEmpty()) return context.getString(R.string.identity_no_file, folder.absolutePath)
        val contents = try {
            files.map { it.readBytes() }
        } catch (e: Exception) {
            return context.getString(R.string.identity_unreadable, e.message.orEmpty())
        }
        val result = import(context, contents)
        if (present(context)) files.forEach { it.delete() }
        return result
    }

    /**
     * Import file contents, told apart by content, not name: JSON with
     * `"format": "air-gestures-band"` is an exported bundle (the key and maybe the band),
     * 32 or 97 bytes is the key (checked by opening it in the bridge), other JSON with an
     * address is band.json.
     */
    fun import(context: Context, contents: List<ByteArray>): String {
        var key: ByteArray? = null
        var band: String? = null
        for (bytes in contents) {
            val json = runCatching { JSONObject(String(bytes)) }.getOrNull()
            when {
                json?.has("format") == true -> {
                    if (json.optString("format") != BUNDLE_FORMAT) return context.getString(R.string.identity_not_band_file)
                    val version = json.optInt("version", 0)
                    if (version > BUNDLE_VERSION) {
                        return context.getString(R.string.identity_too_new, version)
                    }
                    if (version < 1) return context.getString(R.string.identity_no_version)
                    key = runCatching { Base64.decode(json.getString("owner_key"), Base64.DEFAULT) }.getOrNull()
                        ?: return context.getString(R.string.identity_key_unreadable)
                    json.optJSONObject("band")?.let { band = it.toString(2) }
                }
                bytes.size == 32 || bytes.size == 97 -> key = bytes
                json?.has("address") == true -> band = String(bytes)
            }
        }
        val owner = key ?: return context.getString(R.string.identity_no_key)
        if (owner.size != 32 && owner.size != 97) return context.getString(R.string.identity_bad_key_size, owner.size)
        try {
            Bridge.close(Bridge.open(owner, 0, false, "", ""))
        } catch (e: Exception) {
            return context.getString(R.string.identity_bad_key, e.message.orEmpty())
        }
        keyFile(context).writeBytes(owner)
        // The imported key is the newer one.
        pendingFile(context).delete()
        band?.let { bandFile(context).writeText(it) }
        return context.getString(if (band != null) R.string.identity_imported_band else R.string.identity_imported)
    }

    /**
     * Forget the band here: the key and record, and the Bluetooth bond (stop the link first).
     * The band keeps its owner; a factory reset (hold its button about 16 s) clears that.
     * Returns true when the bond couldn't be removed by the app.
     */
    @JvmStatic
    @SuppressLint("MissingPermission")
    fun forget(context: Context): Boolean {
        keyFile(context).delete()
        pendingFile(context).delete()
        bandFile(context).delete()
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val band = adapter?.bondedDevices?.firstOrNull { it.name?.lowercase()?.startsWith("meta band") == true }
            ?: return false
        // Removing a bond is a hidden API for ordinary apps; many builds still allow it.
        val removed = runCatching { band.javaClass.getMethod("removeBond").invoke(band) as Boolean }.getOrDefault(false)
        return !removed
    }
}

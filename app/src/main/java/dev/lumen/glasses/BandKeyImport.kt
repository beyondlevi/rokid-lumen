package dev.lumen.glasses

import android.content.Context
import android.util.Log
import dev.lumen.band.Identity
import dev.lumen.protocol.Link
import dev.lumen.protocol.SettingsEvent
import org.json.JSONObject

/**
 * The band's key from the phone ([Link.BAND_KEY]): the companion's `air-gestures-band` export,
 * imported as the drop folder's file would be ([Identity.import]), then the band link starts
 * over with it unless the band is with the phone. Answers with a [SettingsEvent.Result] whose
 * subject is "band_key". The key is never logged. Main thread.
 */
object BandKeyImport {
    private const val TAG = "BandKey"
    const val SUBJECT = "band_key"

    fun onPhoneMessage(context: Context, request: JSONObject) {
        val bundle = request.optString("bundle")
        val message = if (bundle.isEmpty()) "no key" else Identity.import(context, listOf(bundle.toByteArray()))
        val ok = message == context.getString(dev.lumen.band.R.string.identity_imported_band) ||
            message == context.getString(dev.lumen.band.R.string.identity_imported)
        Log.d(TAG, "band key from the phone: ${if (ok) "imported" else "refused"}")
        PhoneLink.send(Link.SETTINGS_EVENT, SettingsEvent.Result(ok, SUBJECT, if (ok) "" else message).toJson(request))
        if (ok && !GestureMappings.isBandOnPhone(context)) BandRuntime.restart(context)
        BandSettings.pushStatusNow()
    }
}

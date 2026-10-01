package dev.lumen.companion.speech

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The chosen engine, language and patience (plain preferences). */
object SpeechSettings {
    private const val PREFS = "speech"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun engine(context: Context): SpeechEngine = SpeechEngine.fromId(prefs(context).getString("engine", null)) ?: SpeechEngine.DEFAULT

    fun setEngine(context: Context, engine: SpeechEngine) = prefs(context).edit().putString("engine", engine.id).apply()

    fun language(context: Context): SpeechLanguage = SpeechLanguage.fromId(prefs(context).getString("language", null)) ?: SpeechLanguage.DEFAULT

    fun setLanguage(context: Context, language: SpeechLanguage) = prefs(context).edit().putString("language", language.id).apply()

    fun patience(context: Context): SpeechPatience = SpeechPatience.fromId(prefs(context).getString("patience", null)) ?: SpeechPatience.DEFAULT

    fun setPatience(context: Context, patience: SpeechPatience) = prefs(context).edit().putString("patience", patience.id).apply()

    /** What the chosen engine still needs before it can work. */
    enum class Missing { KEY, REGION, MICROPHONE }

    /** Null when [engine] is ready (Vosk downloads its model on the first use). */
    fun missing(context: Context, engine: SpeechEngine = engine(context)): Missing? = when {
        engine.provider.needsKey && !SpeechSecrets.hasKey(context, engine.provider) -> Missing.KEY
        engine.provider == SpeechProvider.AZURE && SpeechSecrets.azureRegion(context) == null -> Missing.REGION
        engine.provider == SpeechProvider.ANDROID &&
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED -> Missing.MICROPHONE
        else -> null
    }
}

/**
 * The providers' API keys, encrypted with an AndroidKeyStore AES-GCM key (as Nexus's
 * HubSecretStore): they never leave the phone and aren't in any backup (allowBackup is off). The
 * Azure region isn't a secret and is kept as is.
 */
object SpeechSecrets {
    private const val TAG = "NbSpeechSecrets"
    private const val PREFS = "speech_credentials"
    private const val ALIAS = "lumen_speech_secrets"
    private const val REGION = "azure_region"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun keyName(provider: SpeechProvider) = provider.name.lowercase() + "_api_key"

    fun hasKey(context: Context, provider: SpeechProvider) = prefs(context).contains(keyName(provider))

    fun key(context: Context, provider: SpeechProvider): String? {
        val stored = prefs(context).getString(keyName(provider), null) ?: return null
        return runCatching {
            val json = JSONObject(stored)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(json.getString("iv"), Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(json.getString("ciphertext"), Base64.NO_WRAP)), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "couldn't read the ${provider.displayName} key", it) }.getOrNull()
    }

    /** Stores [value] (trimmed); false when the phone couldn't encrypt it. */
    fun setKey(context: Context, provider: SpeechProvider, value: String): Boolean = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        val json = JSONObject()
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(sealed, Base64.NO_WRAP))
        prefs(context).edit().putString(keyName(provider), json.toString()).commit()
    }.onFailure { Log.w(TAG, "couldn't save the ${provider.displayName} key", it) }.getOrDefault(false)

    fun removeKey(context: Context, provider: SpeechProvider) {
        prefs(context).edit().remove(keyName(provider)).apply {
            if (provider == SpeechProvider.AZURE) remove(REGION)
        }.apply()
    }

    fun azureRegion(context: Context): String? = prefs(context).getString(REGION, null)?.takeIf { it.isNotBlank() }

    /** False when [region] isn't a region name (letters, digits and dashes, as `westeurope`). */
    fun setAzureRegion(context: Context, region: String): Boolean {
        val clean = region.trim().lowercase()
        if (!isValidAzureRegion(clean)) return false
        prefs(context).edit().putString(REGION, clean).apply()
        return true
    }

    fun isValidAzureRegion(region: String) = region.matches(Regex("^[a-z0-9-]+$"))

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}

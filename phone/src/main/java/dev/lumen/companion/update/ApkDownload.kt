package dev.lumen.companion.update

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a release's APK and checks it before anything installs it: its SHA-256 against
 * GitHub's digest (required), its package name and version, and its signer, which must be the
 * one this companion is signed with (the companion and the glasses app share a key). Blocking.
 */
object ApkDownload {
    /** Why an APK was refused; [message] is for the log. */
    class Refused(val reason: Reason, message: String) : IOException(message)

    enum class Reason { NO_DIGEST, DIGEST, PACKAGE, VERSION, SIGNER, NETWORK }

    @JvmStatic
    fun download(context: Context, asset: ReleaseAsset, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): File {
        val expected = asset.sha256 ?: throw Refused(Reason.NO_DIGEST, "${asset.name} has no digest")
        val dir = File(context.cacheDir, "app-updates").apply { mkdirs() }
        val file = File(dir, asset.name)
        val digest = MessageDigest.getInstance("SHA-256")
        var url = URL(asset.url)
        var connection: HttpURLConnection
        var hops = 0
        // GitHub answers with a redirect to its CDN; follow it only while it stays HTTPS.
        while (true) {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "RokidLumenCompanion")
            }
            val code = connection.responseCode
            if (code in 300..399 && hops++ < 5) {
                val next = connection.getHeaderField("Location") ?: throw Refused(Reason.NETWORK, "redirect without a location")
                connection.disconnect()
                url = URL(url, next)
                if (url.protocol != "https") throw Refused(Reason.NETWORK, "redirect off HTTPS")
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) throw Refused(Reason.NETWORK, "HTTP $code")
            break
        }
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: asset.size
            var done = 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw InterruptedException("cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) {
                            lastReport = now
                            progress(done, total)
                        }
                    }
                }
            }
            progress(done, total)
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            connection.disconnect()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            throw Refused(Reason.DIGEST, "sha256 $actual != $expected")
        }
        return file
    }

    /** Package, version and signer of [apk]; throws [Refused] (and deletes it) when one is off. */
    @JvmStatic
    fun verify(context: Context, apk: File, packageName: String, version: SemVer) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apk.path, flags)
        fun refuse(reason: Reason, message: String): Nothing {
            apk.delete()
            throw Refused(reason, message)
        }
        info ?: refuse(Reason.PACKAGE, "not an APK")
        if (info.packageName != packageName) refuse(Reason.PACKAGE, "package ${info.packageName}")
        if (SemVer.parse(info.versionName) != version) refuse(Reason.VERSION, "versionName ${info.versionName} != $version")
        val theirs = signers(info).map(::fingerprint).toSet()
        val ours = signers(pm.getPackageInfo(context.packageName, flags)).map(::fingerprint).toSet()
        if (theirs.isEmpty() || theirs != ours) refuse(Reason.SIGNER, "signer differs")
    }

    private fun signers(info: android.content.pm.PackageInfo): List<Signature> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners.toList() else it.signingCertificateHistory.toList().takeLast(1) }.orEmpty()
        } else {
            @Suppress("DEPRECATION") info.signatures?.toList().orEmpty()
        }

    private fun fingerprint(signature: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
}

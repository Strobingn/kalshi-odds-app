package com.dirk.kalshiodds.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.dirk.kalshiodds.AppIdentity
import java.io.File
import java.security.MessageDigest

/**
 * Prompts the system installer only after both the zip inspector and
 * PackageManager agree on package id and the Kashi debug cert.
 * Prefer a FileProvider content URI + ACTION_VIEW. PackageInstaller is the fallback.
 */

sealed class InstallPrompt {
    data object Started : InstallPrompt()
    data object NeedUnknownApps : InstallPrompt()
    data class Rejected(val reason: String) : InstallPrompt()
}
class KashiApkInstaller(private val context: Context) {
    fun verify(file: File): ApkInstallDecision {
        val archive = ApkArchiveInspector.inspect(file.readBytes())
        val fromZip = ApkInstallGate.decide(archive.packageName, archive.certSha256)
        if (fromZip is ApkInstallDecision.Reject) return fromZip
        val fromPm = packageManagerFacts(file)
        return ApkInstallGate.decide(fromPm.packageName, fromPm.certSha256)
    }

    fun promptInstall(file: File): InstallPrompt {
        val decision = verify(file)
        if (decision is ApkInstallDecision.Reject) return InstallPrompt.Rejected(decision.reason)
        if (InstallUnknownApps.needsPermission(Build.VERSION.SDK_INT, canRequestInstalls())) {
            return InstallPrompt.NeedUnknownApps
        }
        val uri = contentUri(file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(view)
            InstallPrompt.Started
        } catch (_: Exception) {
            when (val session = runCatching { install(file) }.getOrElse {
                return InstallPrompt.Rejected(it.message ?: "Could not open the package installer")
            }) {
                is ApkInstallDecision.Reject -> InstallPrompt.Rejected(session.reason)
                ApkInstallDecision.Allow -> InstallPrompt.Started
            }
        }
    }

    fun contentUri(file: File): Uri =
        FileProvider.getUriForFile(context, AppIdentity.FILE_PROVIDER_AUTHORITY, file)

    fun unknownAppsIntent(): Intent =
        Intent(InstallUnknownApps.settingsAction(), Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun canRequestInstalls(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    fun install(file: File): ApkInstallDecision {
        val decision = verify(file)
        if (decision is ApkInstallDecision.Reject) return decision
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("kashi", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, com.dirk.kalshiodds.MainActivity::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getActivity(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
        return ApkInstallDecision.Allow
    }

    private fun packageManagerFacts(file: File): ApkArchiveInspector.Facts {
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return ApkArchiveInspector.Facts(null, null)
        val cert = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.let { sha256(it) }
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.let { sha256(it) }
        }
        return ApkArchiveInspector.Facts(info.packageName, cert)
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }

    private fun sha256(signature: Signature): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

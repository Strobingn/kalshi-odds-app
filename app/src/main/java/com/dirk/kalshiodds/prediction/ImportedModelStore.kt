package com.dirk.kalshiodds.prediction

import android.content.Context
import java.io.File

class ImportedModelStore(context: Context) {
    private val dir = context.applicationContext.filesDir
    private val file = File(dir, FILE_NAME)
    private val previousFile = File(dir, PREV_FILE_NAME)
    private val manifestFile = File(dir, MANIFEST_FILE)
    private val previousManifestFile = File(dir, PREV_MANIFEST_FILE)
    @Volatile
    private var cached: EdgeModel? = null

    /**
     * Installed model that still clears package, sha256 (when present),
     * and the beat-market gate. Otherwise scoring keeps the bundled TFLite model.
     */
    fun trustedCurrent(): EdgeModel? {
        val model = current() ?: return null
        val manifest = currentManifest() ?: return null
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        if (manifest.sha256.isNotBlank() && !ModelDigest.matches(manifest.sha256, raw)) return null
        if (manifest.packageId.isNotBlank() &&
            manifest.packageId != com.dirk.kalshiodds.AppIdentity.APPLICATION_ID
        ) {
            return null
        }
        if (!ModelActivation.decide(manifest, modelValid = true).activate) return null
        return model
    }

    fun current(): EdgeModel? {
        cached?.let { return it }
        if (!file.exists()) return null
        val model = runCatching { EdgeModel.parse(file.readText()) }.getOrNull() ?: return null
        cached = model
        return model
    }

    fun previous(): EdgeModel? {
        if (!previousFile.exists()) return null
        return runCatching { EdgeModel.parse(previousFile.readText()) }.getOrNull()
    }

    fun currentManifest(): EdgeModelManifest? {
        if (!manifestFile.exists()) return null
        return runCatching { EdgeModelManifest.parse(manifestFile.readText()) }.getOrNull()
    }

    fun importJson(raw: String): EdgeModel {
        val model = EdgeModel.parse(raw)
        val manifest = runCatching {
            EdgeModelManifest.fromModelMetrics(
                model,
                trainedAt = java.time.Instant.now().toString()
            )
        }.getOrNull()
        val decision = if (manifest != null) {
            ModelActivation.decide(manifest, modelValid = true, requireProvenance = false)
        } else {
            ModelActivation.decideMissingManifest()
        }
        if (!decision.activate) {
            error(decision.reason)
        }
        activate(model, manifest, rawJson = raw)
        return model
    }

    /**
     * Keep the current files for rollback, then write [rawJson] unchanged so
     * a published sha256 still matches. Callers activate only after the gate.
     */
    fun activate(
        model: EdgeModel,
        manifest: EdgeModelManifest?,
        rawJson: String = model.toJson()
    ): EdgeModel {
        if (file.exists()) {
            runCatching { previousFile.writeText(file.readText()) }
        }
        if (manifestFile.exists()) {
            runCatching { previousManifestFile.writeText(manifestFile.readText()) }
        }
        file.writeText(rawJson)
        if (manifest != null) {
            runCatching { manifestFile.writeText(manifest.toJson()) }
        }
        cached = model
        return model
    }

    fun rollback(): EdgeModel? {
        if (!previousFile.exists()) return null
        val raw = runCatching { previousFile.readText() }.getOrNull() ?: return null
        val prev = runCatching { EdgeModel.parse(raw) }.getOrNull() ?: return null
        if (file.exists()) {
            runCatching { file.copyTo(File(dir, "imported_edge_model.rolled.json"), overwrite = true) }
        }
        file.writeText(raw)
        if (previousManifestFile.exists()) {
            runCatching { manifestFile.writeText(previousManifestFile.readText()) }
        }
        cached = prev
        return prev
    }

    fun clear() {
        cached = null
        if (file.exists()) file.delete()
        if (previousFile.exists()) previousFile.delete()
        if (manifestFile.exists()) manifestFile.delete()
        if (previousManifestFile.exists()) previousManifestFile.delete()
    }

    fun installed(): Boolean = file.exists()

    companion object {
        const val FILE_NAME = "imported_edge_model.json"
        const val PREV_FILE_NAME = "imported_edge_model.prev.json"
        const val MANIFEST_FILE = "imported_edge_model.manifest.json"
        const val PREV_MANIFEST_FILE = "imported_edge_model.prev.manifest.json"
    }
}

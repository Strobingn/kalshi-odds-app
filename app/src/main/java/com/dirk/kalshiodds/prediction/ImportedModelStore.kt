package com.dirk.kalshiodds.prediction

import android.content.Context
import java.io.File

class ImportedModelStore(context: Context) {
    private val dir = context.applicationContext.filesDir
    private val file = File(dir, FILE_NAME)
    private val previousFile = File(dir, PREV_FILE_NAME)
    private val manifestFile = File(dir, MANIFEST_FILE)
    @Volatile
    private var cached: EdgeModel? = null

    fun current(): EdgeModel? {
        cached?.let { return it }
        if (!file.exists()) return null
        // Releases published before provenance was recorded may contain
        // settlement-look-ahead or synthetic training data. Manual imports
        // have no manifest and remain an explicit user choice.
        if (manifestFile.exists()) {
            val manifest = currentManifest() ?: return null
            if (!manifest.beatsMarket) return null
        }
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
        activate(model, manifest = null)
        return model
    }

    /**
     * Keep the current model as rollback, then write [model] if [activate]
     * is true. Callers should only activate after [ModelActivation.decide].
     */
    fun activate(model: EdgeModel, manifest: EdgeModelManifest?): EdgeModel {
        current()?.let { prev ->
            runCatching { previousFile.writeText(prev.toJson()) }
        }
        file.writeText(model.toJson())
        if (manifest != null) {
            runCatching { manifestFile.writeText(manifest.toJson()) }
        } else if (manifestFile.exists()) {
            manifestFile.delete()
        }
        cached = model
        return model
    }

    fun rollback(): EdgeModel? {
        val prev = previous() ?: return null
        if (file.exists()) {
            runCatching { file.copyTo(File(dir, "imported_edge_model.rolled.json"), overwrite = true) }
        }
        file.writeText(prev.toJson())
        cached = prev
        return prev
    }

    fun clear() {
        cached = null
        if (file.exists()) file.delete()
        if (previousFile.exists()) previousFile.delete()
        if (manifestFile.exists()) manifestFile.delete()
    }

    fun installed(): Boolean = file.exists()

    companion object {
        const val FILE_NAME = "imported_edge_model.json"
        const val PREV_FILE_NAME = "imported_edge_model.prev.json"
        const val MANIFEST_FILE = "imported_edge_model.manifest.json"
    }
}

package com.dirk.kalshiodds.prediction

import android.content.Context
import java.io.File

class ImportedModelStore(context: Context) {
    private val assets = context.applicationContext.assets
    private val dir = context.applicationContext.filesDir
    private val file = File(dir, FILE_NAME)
    private val previousFile = File(dir, PREV_FILE_NAME)
    private val manifestFile = File(dir, MANIFEST_FILE)
    @Volatile
    private var cached: EdgeModel? = null

    fun current(): EdgeModel? {
        cached?.let { return it }
        if (!file.exists()) return null
        val model = runCatching { EdgeModel.parse(file.readText()) }.getOrNull() ?: return null
        cached = model
        return model
    }

    /**
     * The imported/downloaded model, else the APK's bundled `edge_model.json`
     * — but only when its bundled manifest passes [ModelActivation] (beats the
     * mid out of sample with a CI above zero). Schema-1 files no longer parse.
     */
    fun currentOrBundled(): EdgeModel? = current() ?: bundled()

    fun bundled(): EdgeModel? = runCatching {
        val manifest = EdgeModelManifest.parse(assets.open(BUNDLED_MANIFEST).bufferedReader().use { it.readText() })
        if (!ModelActivation.decide(manifest, modelValid = true).activate) return@runCatching null
        EdgeModel.parse(assets.open(BUNDLED_MODEL).bufferedReader().use { it.readText() })
    }.getOrNull()

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
        const val BUNDLED_MODEL = "edge_model.json"
        const val BUNDLED_MANIFEST = "edge_model_manifest.json"
    }
}

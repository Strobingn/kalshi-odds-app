package com.dirk.kalshiodds.prediction

import android.content.Context
import java.io.File

class ImportedModelStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    @Volatile
    private var cached: EdgeModel? = null

    fun current(): EdgeModel? {
        cached?.let { return it }
        if (!file.exists()) return null
        val model = runCatching { EdgeModel.parse(file.readText()) }.getOrNull() ?: return null
        cached = model
        return model
    }

    fun importJson(raw: String): EdgeModel {
        val model = EdgeModel.parse(raw)
        file.writeText(model.toJson())
        cached = model
        return model
    }

    fun clear() {
        cached = null
        if (file.exists()) file.delete()
    }

    fun installed(): Boolean = file.exists()

    companion object {
        const val FILE_NAME = "imported_edge_model.json"
    }
}

package com.dirk.kalshiodds.decision

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Minimal ONNX ModelProto reader/writer for a single fixed-shape TreeEnsemble graph.
 * No dynamic axes and no If / Loop / Scan. Enough to load a compact `.onnx` the scalper exported
 * or a TreeEnsembleClassifier / TreeEnsembleRegressor file dropped in storage. `.ort` flatbuffers
 * are recognized and left for the QNN context cache; this build does not execute them.
 */
object OnnxGraph {
    val CONTROL_FLOW = setOf("If", "Loop", "Scan")

    fun isOrtFlatbuffer(bytes: ByteArray): Boolean {
        if (bytes.size >= 8) {
            val id = String(bytes, 4, 4, StandardCharsets.ISO_8859_1)
            if (id == "ORTM") return true
        }
        return bytes.size >= 4 && String(bytes, 0, 4, StandardCharsets.ISO_8859_1) == "ORTM"
    }

    /** Null when [dims] is exactly `[1, N]` and [ops] has no control-flow. */
    fun fixedShapeError(dims: List<Long?>, ops: List<String>): String? {
        val flow = ops.firstOrNull { it in CONTROL_FLOW }
        if (flow != null) return "control-flow op $flow is not allowed"
        if (dims.any { it == null }) return "dynamic axis is not allowed"
        if (dims.size != 2 || dims[0] != 1L || dims[1]!! <= 0L) return "fixed shape [1, N] required"
        return null
    }

    fun export(model: TreeEnsemble): ByteArray {
        require(model.treeCount > 0) { "empty forest" }
        return exportGraph(model.inputShape, listOf(if (model.logistic) "TreeEnsembleClassifier" else "TreeEnsembleRegressor"), model)
    }

    fun exportGraph(shape: IntArray, ops: List<String>, model: TreeEnsemble? = null, dynamicAxis: Boolean = false): ByteArray {
        val out = ProtoBuf()
        out.varintField(1, 8L)
        out.strField(2, "kalshi-scalper")
        out.msgField(7, graph(shape, ops, model, dynamicAxis))
        out.msgField(8, opset("", 17))
        out.msgField(8, opset("ai.onnx.ml", 3))
        return out.toByteArray()
    }

    fun parse(bytes: ByteArray): TreeEnsemble {
        require(bytes.isNotEmpty()) { "empty model" }
        if (bytes[0] == '{'.code.toByte()) return parseJson(bytes)
        if (isOrtFlatbuffer(bytes)) throw IllegalArgumentException("ORT flatbuffer is a QNN context blob, not a TreeEnsemble graph")
        val fields = ProtoBuf.parse(bytes)
        val graph = fields.msgs(7).firstOrNull() ?: throw IllegalArgumentException("ONNX graph missing")
        return parseGraph(graph)
    }

    private fun parseGraph(graph: ByteArray): TreeEnsemble {
        val g = ProtoBuf.parse(graph)
        val nodes = g.msgs(1)
        val ops = ArrayList<String>(nodes.size)
        var tree: ByteArray? = null
        var opName = ""
        for (node in nodes) {
            val n = ProtoBuf.parse(node)
            val op = n.str(4)
            ops += op
            if (op == "TreeEnsembleClassifier" || op == "TreeEnsembleRegressor") {
                tree = node
                opName = op
            }
        }
        val inputs = g.msgs(11)
        require(inputs.isNotEmpty()) { "graph input missing" }
        val dims = dimsOf(inputs[0])
        val err = fixedShapeError(dims, ops)
        if (err != null) throw IllegalArgumentException(err)
        val nFeat = dims[1]!!.toInt()
        val node = tree ?: throw IllegalArgumentException("no TreeEnsembleClassifier or TreeEnsembleRegressor")
        val attrs = ProtoBuf.parse(node).msgs(5)
        val logistic = opName == "TreeEnsembleClassifier" && stringAttr(attrs, "post_transform").let { it == null || it == "LOGISTIC" }
        val base = floatAttr(attrs, "base_values")?.firstOrNull()?.toDouble() ?: 0.0
        val treeIds = intsAttr(attrs, "nodes_treeids")
        val nodeIds = intsAttr(attrs, "nodes_nodeids")
        val featureIds = intsAttr(attrs, "nodes_featureids")
        val values = floatAttr(attrs, "nodes_values") ?: DoubleArray(treeIds.size)
        val modes = stringsAttr(attrs, "nodes_modes")
        val trueIds = intsAttr(attrs, "nodes_truenodeids")
        val falseIds = intsAttr(attrs, "nodes_falsenodeids")
        require(treeIds.isNotEmpty()) { "TreeEnsemble has no nodes" }
        require(nodeIds.size == treeIds.size && featureIds.size == treeIds.size && modes.size == treeIds.size && trueIds.size == treeIds.size && falseIds.size == treeIds.size) {
            "TreeEnsemble node attributes disagree"
        }
        val leaves = leafWeights(attrs, opName)
        val specs = ArrayList<TreeEnsemble.Spec>(treeIds.size)
        for (i in treeIds.indices) {
            val key = (treeIds[i] shl 32) or (nodeIds[i] and 0xffffffffL)
            specs += TreeEnsemble.Spec(
                treeIds[i].toInt(),
                nodeIds[i].toInt(),
                featureIds[i].toInt(),
                if (i < values.size) values[i] else 0.0,
                modes[i],
                trueIds[i].toInt(),
                falseIds[i].toInt(),
                leaves[key] ?: 0.0
            )
        }
        return TreeEnsemble.build(intArrayOf(1, nFeat), specs, logistic, base)
    }

    private fun leafWeights(attrs: List<ByteArray>, op: String): Map<Long, Double> {
        val trees: LongArray
        val nodes: LongArray
        val weights: DoubleArray
        val ids: LongArray?
        if (op == "TreeEnsembleRegressor") {
            trees = intsAttr(attrs, "target_treeids")
            nodes = intsAttr(attrs, "target_nodeids")
            weights = floatAttr(attrs, "target_weights") ?: DoubleArray(0)
            ids = null
        } else {
            trees = intsAttr(attrs, "class_treeids")
            nodes = intsAttr(attrs, "class_nodeids")
            weights = floatAttr(attrs, "class_weights") ?: DoubleArray(0)
            ids = intsAttr(attrs, "class_ids")
        }
        val prefer = HashMap<Long, Double>()
        val any = HashMap<Long, Double>()
        val n = minOf(trees.size, nodes.size, weights.size)
        for (i in 0 until n) {
            val key = (trees[i] shl 32) or (nodes[i] and 0xffffffffL)
            any[key] = weights[i]
            if (ids == null || i >= ids.size || ids[i] == 1L) prefer[key] = weights[i]
        }
        any.putAll(prefer)
        return any
    }

    private fun parseJson(bytes: ByteArray): TreeEnsemble {
        val o = org.json.JSONObject(String(bytes, StandardCharsets.UTF_8))
        val shape = o.getJSONArray("input_shape")
        val dims = listOf(shape.optLong(0), if (shape.isNull(1)) null else shape.optLong(1))
        val op = o.optString("op", "TreeEnsembleClassifier")
        val err = fixedShapeError(dims, listOf(op))
        if (err != null) throw IllegalArgumentException(err)
        val modes = o.getJSONArray("nodes_modes")
        val n = modes.length()
        val specs = ArrayList<TreeEnsemble.Spec>(n)
        val trees = o.getJSONArray("nodes_treeids")
        val nodes = o.getJSONArray("nodes_nodeids")
        val feats = o.getJSONArray("nodes_featureids")
        val values = o.getJSONArray("nodes_values")
        val trues = o.getJSONArray("nodes_truenodeids")
        val falses = o.getJSONArray("nodes_falsenodeids")
        val leaves = HashMap<Long, Double>()
        val ct = o.optJSONArray("class_treeids")
        val cn = o.optJSONArray("class_nodeids")
        val cw = o.optJSONArray("class_weights")
        if (ct != null && cn != null && cw != null) {
            for (i in 0 until ct.length()) {
                leaves[(ct.getLong(i) shl 32) or (cn.getLong(i) and 0xffffffffL)] = cw.getDouble(i)
            }
        }
        for (i in 0 until n) {
            val key = (trees.getLong(i) shl 32) or (nodes.getLong(i) and 0xffffffffL)
            specs += TreeEnsemble.Spec(
                trees.getInt(i), nodes.getInt(i), feats.getInt(i), values.getDouble(i), modes.getString(i),
                trues.getInt(i), falses.getInt(i), leaves[key] ?: 0.0
            )
        }
        val logistic = o.optString("post_transform", "LOGISTIC") == "LOGISTIC"
        val base = o.optJSONArray("base_values")?.optDouble(0) ?: 0.0
        return TreeEnsemble.build(intArrayOf(1, dims[1]!!.toInt()), specs, logistic, base)
    }

    private fun graph(shape: IntArray, ops: List<String>, model: TreeEnsemble?, dynamicAxis: Boolean): ByteArray {
        val g = ProtoBuf()
        for (op in ops) {
            g.msgField(1, node(op, model))
        }
        g.strField(2, "scalp")
        g.msgField(11, valueInfo("features", shape, dynamicAxis))
        g.msgField(12, valueInfo("score", intArrayOf(1, 1), false))
        return g.toByteArray()
    }

    private fun node(op: String, model: TreeEnsemble?): ByteArray {
        val n = ProtoBuf()
        n.strField(1, "features")
        n.strField(2, "score")
        n.strField(3, "trees")
        n.strField(4, op)
        if (model != null && (op == "TreeEnsembleClassifier" || op == "TreeEnsembleRegressor")) {
            val specs = model.specs
            fun longs(sel: (TreeEnsemble.Spec) -> Long) = LongArray(specs.size) { sel(specs[it]) }
            n.msgField(5, attrInts("nodes_treeids", longs { it.treeId.toLong() }))
            n.msgField(5, attrInts("nodes_nodeids", longs { it.nodeId.toLong() }))
            n.msgField(5, attrInts("nodes_featureids", longs { it.feature.toLong() }))
            n.msgField(5, attrFloats("nodes_values", FloatArray(specs.size) { specs[it].threshold.toFloat() }))
            n.msgField(5, attrStrings("nodes_modes", specs.map { it.mode }))
            n.msgField(5, attrInts("nodes_truenodeids", longs { it.trueId.toLong() }))
            n.msgField(5, attrInts("nodes_falsenodeids", longs { it.falseId.toLong() }))
            val leaves = specs.filter { !it.mode.startsWith("BRANCH") }
            if (op == "TreeEnsembleRegressor") {
                n.msgField(5, attrInts("target_treeids", LongArray(leaves.size) { leaves[it].treeId.toLong() }))
                n.msgField(5, attrInts("target_nodeids", LongArray(leaves.size) { leaves[it].nodeId.toLong() }))
                n.msgField(5, attrFloats("target_weights", FloatArray(leaves.size) { leaves[it].leaf.toFloat() }))
            } else {
                n.msgField(5, attrInts("class_treeids", LongArray(leaves.size) { leaves[it].treeId.toLong() }))
                n.msgField(5, attrInts("class_nodeids", LongArray(leaves.size) { leaves[it].nodeId.toLong() }))
                n.msgField(5, attrInts("class_ids", LongArray(leaves.size) { 1L }))
                n.msgField(5, attrFloats("class_weights", FloatArray(leaves.size) { leaves[it].leaf.toFloat() }))
            }
            n.msgField(5, attrString("post_transform", if (model.logistic) "LOGISTIC" else "NONE"))
            n.msgField(5, attrFloats("base_values", floatArrayOf(model.base.toFloat())))
        }
        if (op == "TreeEnsembleClassifier" || op == "TreeEnsembleRegressor") n.strField(7, "ai.onnx.ml")
        return n.toByteArray()
    }

    private fun valueInfo(name: String, shape: IntArray, dynamicAxis: Boolean): ByteArray {
        val dims = ProtoBuf()
        for (i in shape.indices) {
            val dim = ProtoBuf()
            if (dynamicAxis && i == shape.lastIndex) dim.strField(2, "N") else dim.varintField(1, shape[i].toLong())
            dims.msgField(1, dim.toByteArray())
        }
        val tensor = ProtoBuf()
        tensor.varintField(1, 1L)
        tensor.msgField(2, dims.toByteArray())
        val type = ProtoBuf()
        type.msgField(1, tensor.toByteArray())
        val info = ProtoBuf()
        info.strField(1, name)
        info.msgField(2, type.toByteArray())
        return info.toByteArray()
    }

    private fun opset(domain: String, version: Long): ByteArray {
        val o = ProtoBuf()
        o.strField(1, domain)
        o.varintField(2, version)
        return o.toByteArray()
    }

    private fun dimsOf(info: ByteArray): List<Long?> {
        val type = ProtoBuf.parse(info).msgs(2).firstOrNull() ?: return emptyList()
        val tensor = ProtoBuf.parse(type).msgs(1).firstOrNull() ?: return emptyList()
        val shape = ProtoBuf.parse(tensor).msgs(2).firstOrNull() ?: return emptyList()
        return ProtoBuf.parse(shape).msgs(1).map { dim ->
            val d = ProtoBuf.parse(dim)
            when {
                d.has(2) -> null
                d.has(1) -> d.lng(1)
                else -> null
            }
        }
    }

    private fun stringAttr(attrs: List<ByteArray>, name: String): String? =
        attrs.firstNotNullOfOrNull { a ->
            val p = ProtoBuf.parse(a)
            if (p.str(1) == name) p.str(4).ifEmpty { null } else null
        }

    private fun floatAttr(attrs: List<ByteArray>, name: String): DoubleArray? =
        attrs.firstNotNullOfOrNull { a ->
            val p = ProtoBuf.parse(a)
            if (p.str(1) != name) null else p.floats(7).takeIf { it.isNotEmpty() }
        }

    private fun intsAttr(attrs: List<ByteArray>, name: String): LongArray =
        attrs.firstNotNullOfOrNull { a ->
            val p = ProtoBuf.parse(a)
            if (p.str(1) != name) null else p.ints(8).takeIf { it.isNotEmpty() }
        } ?: LongArray(0)

    private fun stringsAttr(attrs: List<ByteArray>, name: String): List<String> =
        attrs.firstNotNullOfOrNull { a ->
            val p = ProtoBuf.parse(a)
            if (p.str(1) != name) null else p.strs(9).takeIf { it.isNotEmpty() }
        } ?: emptyList()

    private fun attrInts(name: String, values: LongArray): ByteArray {
        val a = ProtoBuf()
        a.strField(1, name)
        a.packedVarint(8, values)
        return a.toByteArray()
    }

    private fun attrFloats(name: String, values: FloatArray): ByteArray {
        val a = ProtoBuf()
        a.strField(1, name)
        a.packedFloat(7, values)
        return a.toByteArray()
    }

    private fun attrString(name: String, value: String): ByteArray {
        val a = ProtoBuf()
        a.strField(1, name)
        a.strField(4, value)
        return a.toByteArray()
    }

    private fun attrStrings(name: String, values: List<String>): ByteArray {
        val a = ProtoBuf()
        a.strField(1, name)
        for (v in values) a.strField(9, v)
        return a.toByteArray()
    }
}

/** Protobuf wire codec for the ONNX subset above. Not a general protobuf library. */
internal class ProtoBuf {
    private val buf = ByteArrayOutputStream()

    fun varintField(field: Int, value: Long) {
        writeVarint(((field shl 3) or 0).toLong())
        writeVarint(value)
    }

    fun strField(field: Int, value: String) = msgField(field, value.toByteArray(StandardCharsets.UTF_8))

    fun msgField(field: Int, payload: ByteArray) {
        writeVarint(((field shl 3) or 2).toLong())
        writeVarint(payload.size.toLong())
        buf.write(payload)
    }

    fun packedVarint(field: Int, values: LongArray) {
        val raw = ByteArrayOutputStream()
        val inner = ProtoBuf()
        for (v in values) inner.writeVarint(v)
        raw.write(inner.toByteArray())
        msgField(field, raw.toByteArray())
    }

    fun packedFloat(field: Int, values: FloatArray) {
        val raw = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in values) raw.putFloat(v)
        msgField(field, raw.array())
    }

    fun writeVarint(value: Long) {
        var n = value
        while (true) {
            var b = (n and 0x7F).toInt()
            n = n ushr 7
            if (n != 0L) b = b or 0x80
            buf.write(b)
            if (n == 0L) break
        }
    }

    fun toByteArray(): ByteArray = buf.toByteArray()

    companion object {
        class Msg(private val raw: ByteArray) {
            private val items = ArrayList<Triple<Int, Int, ByteArray>>()

            init {
                var i = 0
                while (i < raw.size) {
                    val key = readVarint(raw, i)
                    i = key.second
                    val field = (key.first ushr 3).toInt()
                    val wire = (key.first and 7).toInt()
                    when (wire) {
                        0 -> {
                            val v = readVarint(raw, i)
                            i = v.second
                            items += Triple(field, wire, varintBytes(v.first))
                        }
                        1 -> {
                            items += Triple(field, wire, raw.copyOfRange(i, i + 8))
                            i += 8
                        }
                        2 -> {
                            val len = readVarint(raw, i)
                            i = len.second
                            val n = len.first.toInt()
                            items += Triple(field, wire, raw.copyOfRange(i, i + n))
                            i += n
                        }
                        5 -> {
                            items += Triple(field, wire, raw.copyOfRange(i, i + 4))
                            i += 4
                        }
                        else -> throw IllegalArgumentException("unsupported protobuf wire $wire")
                    }
                }
            }

            fun msgs(field: Int): List<ByteArray> = items.filter { it.first == field && it.second == 2 }.map { it.third }
            fun has(field: Int): Boolean = items.any { it.first == field }
            fun str(field: Int): String = msgs(field).firstOrNull()?.toString(StandardCharsets.UTF_8) ?: ""
            fun lng(field: Int): Long {
                val hit = items.firstOrNull { it.first == field && it.second == 0 } ?: return 0L
                return readVarint(hit.third, 0).first
            }

            fun ints(field: Int): LongArray {
                val out = ArrayList<Long>()
                for ((f, wire, payload) in items) {
                    if (f != field) continue
                    if (wire == 0) out += readVarint(payload, 0).first
                    if (wire == 2) {
                        var i = 0
                        while (i < payload.size) {
                            val v = readVarint(payload, i)
                            out += v.first
                            i = v.second
                        }
                    }
                }
                return out.toLongArray()
            }

            fun floats(field: Int): DoubleArray {
                val out = ArrayList<Double>()
                for ((f, wire, payload) in items) {
                    if (f != field) continue
                    if (wire == 5 && payload.size == 4) out += ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).float.toDouble()
                    if (wire == 2) {
                        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                        while (buf.remaining() >= 4) out += buf.float.toDouble()
                    }
                }
                return out.toDoubleArray()
            }

            fun strs(field: Int): List<String> = msgs(field).map { it.toString(StandardCharsets.UTF_8) }
        }

        fun parse(bytes: ByteArray) = Msg(bytes)

        private fun readVarint(raw: ByteArray, start: Int): Pair<Long, Int> {
            var result = 0L
            var shift = 0
            var i = start
            while (i < raw.size && shift < 64) {
                val b = raw[i].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                i++
                if (b and 0x80 == 0) return result to i
                shift += 7
            }
            throw IllegalArgumentException("truncated varint")
        }

        private fun varintBytes(value: Long): ByteArray {
            val p = ProtoBuf()
            p.writeVarint(value)
            return p.toByteArray()
        }
    }
}

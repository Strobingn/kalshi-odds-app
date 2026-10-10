package com.dirk.kalshiodds.data.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.serializer
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.Type

/**
 * 0.3.52: decodes responses straight from the body stream (no whole-body String copy — the 0.3.50 OOM stack was in
 * decodeFromString of a full MarketsResponse body). Requests encode as before.
 */
@OptIn(ExperimentalSerializationApi::class)
class StreamJsonConverterFactory(private val json: Json, private val contentType: MediaType) : Converter.Factory() {
    override fun responseBodyConverter(type: Type, annotations: Array<out Annotation>, retrofit: Retrofit): Converter<ResponseBody, *> {
        val ser = json.serializersModule.serializer(type)
        return Converter<ResponseBody, Any?> { body -> body.use { b -> b.byteStream().use { json.decodeFromStream(ser, it) } } }
    }

    override fun requestBodyConverter(
        type: Type, parameterAnnotations: Array<out Annotation>, methodAnnotations: Array<out Annotation>, retrofit: Retrofit
    ): Converter<*, RequestBody> {
        val ser = json.serializersModule.serializer(type)
        return Converter<Any?, RequestBody> { v -> json.encodeToString(ser, v).toRequestBody(contentType) }
    }
}

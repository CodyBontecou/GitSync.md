package com.bontecou.syncmd.services.publication

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.math.BigDecimal
import java.nio.charset.CodingErrorAction

/** Strict input shared by remote replies and private journals. Duplicate fields never choose an identity. */
internal object PublicationJson {
    fun parse(bytes:ByteArray,limit:Int=2*1024*1024,maxNodes:Int=100000):JsonElement {
        require(bytes.size<=limit)
        val reader=JsonReader(InputStreamReader(ByteArrayInputStream(bytes),Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))
        reader.isLenient=false
        var nodes=0
        fun read(depth:Int):JsonElement {
            require(depth<=32 && ++nodes<=maxNodes)
            return when(reader.peek()) {
                JsonToken.BEGIN_OBJECT -> JsonObject().also { value ->
                    reader.beginObject()
                    while(reader.hasNext()) { val name=reader.nextName();require(!value.has(name));value.add(name,read(depth+1)) }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> JsonArray().also { value ->reader.beginArray();while(reader.hasNext())value.add(read(depth+1));reader.endArray() }
                JsonToken.STRING -> JsonPrimitive(reader.nextString())
                JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
                JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull();JsonNull.INSTANCE }
                else -> error("Malformed publication JSON")
            }
        }
        return reader.use { val value=read(0);require(reader.peek()==JsonToken.END_DOCUMENT);value }
    }
    fun text(value:JsonElement?):String { requireNotNull(value);require(value.isJsonPrimitive && value.asJsonPrimitive.isString);return value.asString }
    fun integer(value:JsonElement?):Long { requireNotNull(value);require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber);return value.asBigDecimal.longValueExact() }
    fun bool(value:JsonElement?):Boolean { requireNotNull(value);require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean);return value.asBoolean }
}

package com.cybersiren.android.nostr

import com.google.gson.*
import java.lang.reflect.Type

sealed class NostrRequest {

    data class Event(val event: NostrEvent) : NostrRequest()

    data class Subscribe(
        val subscriptionId: String,
        val filters: List<NostrFilter>
    ) : NostrRequest()

    data class Close(val subscriptionId: String) : NostrRequest()

    class RequestSerializer : JsonSerializer<NostrRequest> {
        override fun serialize(src: NostrRequest, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
            val array = JsonArray()

            when (src) {
                is Event -> {
                    array.add("EVENT")
                    array.add(context.serialize(src.event))
                }

                is Subscribe -> {
                    array.add("REQ")
                    array.add(src.subscriptionId)
                    src.filters.forEach { filter ->
                        array.add(context.serialize(filter, NostrFilter::class.java))
                    }
                }

                is Close -> {
                    array.add("CLOSE")
                    array.add(src.subscriptionId)
                }
            }

            return array
        }
    }

    companion object {

        fun createGson(): Gson {
            return GsonBuilder()
                .registerTypeAdapter(NostrRequest::class.java, RequestSerializer())
                .registerTypeAdapter(NostrFilter::class.java, NostrFilter.FilterSerializer())
                .disableHtmlEscaping()
                .create()
        }

        fun toJson(request: NostrRequest): String {
            return createGson().toJson(request)
        }
    }
}

sealed class NostrResponse {

    data class Event(
        val subscriptionId: String,
        val event: NostrEvent
    ) : NostrResponse()

    data class EndOfStoredEvents(
        val subscriptionId: String
    ) : NostrResponse()

    data class Ok(
        val eventId: String,
        val accepted: Boolean,
        val message: String?
    ) : NostrResponse()

    data class Notice(
        val message: String
    ) : NostrResponse()

    data class Unknown(
        val raw: String
    ) : NostrResponse()

    companion object {

        fun fromJsonArray(jsonArray: JsonArray): NostrResponse {
            return try {
                when (val type = jsonArray[0].asString) {
                    "EVENT" -> {
                        if (jsonArray.size() >= 3) {
                            val subscriptionId = jsonArray[1].asString
                            val eventJson = jsonArray[2].asJsonObject
                            val event = parseEventFromJson(eventJson)
                            Event(subscriptionId, event)
                        } else {
                            Unknown(jsonArray.toString())
                        }
                    }

                    "EOSE" -> {
                        if (jsonArray.size() >= 2) {
                            val subscriptionId = jsonArray[1].asString
                            EndOfStoredEvents(subscriptionId)
                        } else {
                            Unknown(jsonArray.toString())
                        }
                    }

                    "OK" -> {
                        if (jsonArray.size() >= 3) {
                            val eventId = jsonArray[1].asString
                            val accepted = jsonArray[2].asBoolean
                            val message = if (jsonArray.size() >= 4) {
                                jsonArray[3].asString
                            } else null
                            Ok(eventId, accepted, message)
                        } else {
                            Unknown(jsonArray.toString())
                        }
                    }

                    "NOTICE" -> {
                        if (jsonArray.size() >= 2) {
                            val message = jsonArray[1].asString
                            Notice(message)
                        } else {
                            Unknown(jsonArray.toString())
                        }
                    }

                    else -> Unknown(jsonArray.toString())
                }
            } catch (e: Exception) {
                Unknown(jsonArray.toString())
            }
        }

        private fun parseEventFromJson(jsonObject: JsonObject): NostrEvent {
            return NostrEvent(
                id = jsonObject.get("id")?.asString ?: "",
                pubkey = jsonObject.get("pubkey")?.asString ?: "",
                createdAt = jsonObject.get("created_at")?.asInt ?: 0,
                kind = jsonObject.get("kind")?.asInt ?: 0,
                tags = parseTagsFromJson(jsonObject.get("tags")?.asJsonArray),
                content = jsonObject.get("content")?.asString ?: "",
                sig = jsonObject.get("sig")?.asString
            )
        }

        private fun parseTagsFromJson(tagsArray: JsonArray?): List<List<String>> {
            if (tagsArray == null) return emptyList()

            return try {
                tagsArray.map { tagElement ->
                    if (tagElement.isJsonArray) {
                        val tagArray = tagElement.asJsonArray
                        tagArray.map { it.asString }
                    } else {
                        emptyList()
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

package com.sloopworks.dayfold.client

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

@Serializable
private data class TriggerWhenKnown(
  val at: String? = null,
  val relative: String? = null,
  val recurring: String? = null,
  @SerialName("alert_offset") val alertOffset: String? = null,
  @SerialName("fact_ref") val factRef: String? = null,
)

object TriggerWhenSerializer : KSerializer<TriggerWhen> {
  override val descriptor: SerialDescriptor = TriggerWhenKnown.serializer().descriptor

  override fun deserialize(decoder: Decoder): TriggerWhen {
    val input = decoder as? JsonDecoder ?: error("TriggerWhen requires JSON")
    val raw = input.decodeJsonElement().jsonObject
    val known = input.json.decodeFromJsonElement(TriggerWhenKnown.serializer(), raw)
    return TriggerWhen(known.at, known.relative, known.recurring, known.alertOffset, known.factRef, raw)
  }

  override fun serialize(encoder: Encoder, value: TriggerWhen) {
    val output = encoder as? JsonEncoder ?: error("TriggerWhen requires JSON")
    val known = output.json.encodeToJsonElement(
      TriggerWhenKnown.serializer(),
      TriggerWhenKnown(value.at, value.relative, value.recurring, value.alertOffset, value.factRef),
    ).jsonObject
    val merged = value.raw.orEmptyExcept(TRIGGER_WHEN_KEYS) + known
    output.encodeJsonElement(JsonObject(merged))
  }
}

@Serializable
private data class BlockTriggerKnown(
  val geo: TriggerGeo? = null,
  @SerialName("when") val whenTrigger: TriggerWhen? = null,
  val activity: TriggerActivity? = null,
)

object BlockTriggerSerializer : KSerializer<BlockTrigger> {
  override val descriptor: SerialDescriptor = BlockTriggerKnown.serializer().descriptor

  override fun deserialize(decoder: Decoder): BlockTrigger {
    val input = decoder as? JsonDecoder ?: error("BlockTrigger requires JSON")
    val raw = input.decodeJsonElement().jsonObject
    val known = input.json.decodeFromJsonElement(BlockTriggerKnown.serializer(), raw)
    return BlockTrigger(known.geo, known.whenTrigger, known.activity, raw)
  }

  override fun serialize(encoder: Encoder, value: BlockTrigger) {
    val output = encoder as? JsonEncoder ?: error("BlockTrigger requires JSON")
    val known = output.json.encodeToJsonElement(
      BlockTriggerKnown.serializer(), BlockTriggerKnown(value.geo, value.whenTrigger, value.activity),
    ).jsonObject
    val merged = value.raw.orEmptyExcept(BLOCK_TRIGGER_KEYS) + known
    output.encodeJsonElement(JsonObject(merged))
  }
}

private val TRIGGER_WHEN_KEYS = setOf("at", "relative", "recurring", "alert_offset", "fact_ref")
private val BLOCK_TRIGGER_KEYS = setOf("geo", "when", "activity")

private fun JsonObject?.orEmptyExcept(known: Set<String>): Map<String, kotlinx.serialization.json.JsonElement> =
  this?.filterKeys { it !in known }.orEmpty()

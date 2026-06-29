package com.sloopworks.dayfold.client

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Slice 5b — decode the `sub` (user id) from an access-token JWT so the client can drive the
// author-only delete gate (createdBy == userId). DECODE-ONLY: the signature is NOT verified
// here — the server is the sole authz authority (it re-resolves the caller per request). This
// is used purely to shape the UI; a forged token can at most reveal a delete option the server
// then rejects (403). Tolerates base64url payloads with or without padding.
@OptIn(ExperimentalEncodingApi::class)
private val urlB64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
private val jwtJson = Json { ignoreUnknownKeys = true }

fun jwtSub(token: String): String? = runCatching {
  val payload = token.split(".").getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
  val claims = jwtJson.parseToJsonElement(urlB64.decode(payload).decodeToString()).jsonObject
  claims["sub"]?.jsonPrimitive?.contentOrNull
}.getOrNull()

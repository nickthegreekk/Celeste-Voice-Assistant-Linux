package com.nikosm.voiceassistant

import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The vision seam both M18.3 and M19 use: one PNG to the persona's **direct Ollama** vision model,
 * which is the route this machine actually runs. Cloud vision is a follow-up rather than a second
 * path invented here; a cloud persona gets null.
 *
 * [resolveVisionTarget] is pure and shared by the OCR path, the screen tool and the availability
 * check, so the three cannot disagree about which model can see a picture.
 */
internal data class VisionTarget(val baseUrl: String, val model: String)

/**
 * The direct-Ollama model this persona can send an image to, or null when it has none.
 *
 * The same rules as the chat route: a gateway (8880) is never an Ollama base, and a blank model is
 * no target. A `[Server] model` tag resolves the server by name; without a tag the persona's own
 * backend URL is used. A cloud persona is not a target in v1 — the four provider image shapes are
 * a second implementation, and the vision model this machine runs is direct Ollama.
 */
internal fun resolveVisionTarget(persona: Persona, ollamaServers: List<ServerConfig>): VisionTarget? {
    if (persona.isCloud) return null
    val rawModel = persona.model.trim()
    val displayServer = if (rawModel.startsWith("[") && rawModel.contains("] ")) {
        rawModel.substring(1, rawModel.indexOf("] "))
    } else {
        null
    }
    val model = if (rawModel.contains("] ")) rawModel.substringAfter("] ") else rawModel
    if (model.isBlank()) return null

    val configured = persona.backendUrl.trim().takeIf { it.isNotBlank() && !it.contains(":8880") }
    if (displayServer == null && configured == null) return null
    val base = configured
        ?: displayServer?.let { name -> ollamaServers.find { it.name == name }?.url }
        ?: return null
    return VisionTarget(base.trimEnd('/'), model)
}

/**
 * One image and one instruction to the persona's vision model. Deliberately standalone: no
 * history, no memory, no tools, no persona prompt — an OCR pass should transcribe, and a screen
 * pass should describe, not answer as the character.
 */
internal suspend fun AssistantService.visionPng(
    png: ByteArray,
    persona: Persona,
    prompt: String,
    maxChars: Int,
    numPredict: Int = 4096,
): String? = withContext(Dispatchers.IO) {
    val target = resolveVisionTarget(persona, _ollamaBaseUrls.value) ?: return@withContext null
    val image = Base64.getEncoder().encodeToString(png)
    val body = JSONObject()
        .put("model", target.model)
        .put("stream", false)
        .put("think", false)
        .put(
            "messages",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", prompt)
                    .put("images", JSONArray().put(image)),
            ),
        )
        .put("options", JSONObject().put("temperature", 0).put("num_predict", numPredict))

    val request = Request.Builder()
        .url(target.baseUrl + "/api/chat")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()
    val call = client.newCall(request)
    call.timeout().timeout(60, TimeUnit.SECONDS)
    try {
        call.execute().use { response ->
            if (!response.isSuccessful) {
                logger.w("Vision", "vision model answered HTTP ${response.code}")
                return@withContext null
            }
            val responseBody = response.body.string()
            JSONObject(responseBody)
                .optJSONObject("message")
                ?.optString("content")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.take(maxChars)
        }
    } catch (e: Exception) {
        logger.w("Vision", "vision pass failed: ${e.message ?: e.javaClass.simpleName}")
        null
    }
}

/**
 * M18.3's OCR pass: one rendered page to the persona's direct Ollama vision model. A null means
 * OCR was unavailable — a cloud persona, a gateway-only persona, or a model that could not read
 * the page — and the attachment says so.
 */
internal suspend fun AssistantService.ocrPng(png: ByteArray, persona: Persona): String? =
    visionPng(png, persona, OCR_PROMPT, OCR_MAX_CHARS_PER_PAGE)

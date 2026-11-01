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
 * M18.3's vision pass: one rendered page to the persona's **direct Ollama** vision model, which
 * is the route this machine actually runs. Cloud OCR is a follow-up rather than a second path
 * invented here; a cloud persona gets null and the attachment says OCR was unavailable.
 *
 * The request is deliberately standalone: no history, no memory, no tools, no persona prompt.
 * OCR should transcribe the page, not answer as the character. The model still comes from the
 * persona, so the user configures no second model just to read a scan.
 */
internal suspend fun AssistantService.ocrPng(png: ByteArray, persona: Persona): String? =
    withContext(Dispatchers.IO) {
        if (persona.isCloud) return@withContext null
        val rawModel = persona.model.trim()
        val displayServer = if (rawModel.startsWith("[") && rawModel.contains("] ")) {
            rawModel.substring(1, rawModel.indexOf("] "))
        } else {
            null
        }
        val model = if (rawModel.contains("] ")) rawModel.substringAfter("] ") else rawModel
        if (model.isBlank()) return@withContext null

        // The same rule as the chat route: never send a gateway (8880) as an Ollama base URL.
        // A gateway-only persona has no direct model to ask, so it gets no OCR rather than a
        // guess at whichever Ollama server happens to be first.
        val configured = persona.backendUrl.trim().takeIf { it.isNotBlank() && !it.contains(":8880") }
        if (displayServer == null && configured == null) return@withContext null
        val base = configured
            ?: displayServer?.let { name -> _ollamaBaseUrls.value.find { it.name == name }?.url }
            ?: return@withContext null

        val image = Base64.getEncoder().encodeToString(png)
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("think", false)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", OCR_PROMPT)
                        .put("images", JSONArray().put(image)),
                ),
            )
            .put("options", JSONObject().put("temperature", 0).put("num_predict", 4096))

        val request = Request.Builder()
            .url(base.trimEnd('/') + "/api/chat")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val call = client.newCall(request)
        call.timeout().timeout(60, TimeUnit.SECONDS)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    logger.w("DocumentOcr", "vision model answered HTTP ${response.code}; OCR unavailable")
                    return@withContext null
                }
                val responseBody = response.body.string()
                JSONObject(responseBody)
                    .optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.take(OCR_MAX_CHARS_PER_PAGE)
            }
        } catch (e: Exception) {
            logger.w("DocumentOcr", "vision OCR failed: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

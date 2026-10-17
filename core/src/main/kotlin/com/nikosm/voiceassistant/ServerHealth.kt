package com.nikosm.voiceassistant

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * On-demand server health (PLAN.md §10/M12): ask each configured local service whether it answers,
 * how quickly, and — where the API has something cheap to say — a detail worth seeing.
 *
 * ## On demand, never polled
 *
 * Nothing in this file runs by itself. The screen calls [checkHealth] for a target only when the
 * user presses **Check now**, and the app keeps no timer for it: a check is a network request to a
 * configured server, and the plan's no-unrequested-network rule applies to a status line as much
 * as to a model fetch.
 *
 * ## What is checked, and what is not
 *
 * The three service lists the app already knows how to talk to: Ollama servers, Voice Gateways,
 * and image-generation servers. **Cloud providers are not checked here** — their endpoints need
 * the provider's own auth shape and dialect, and each one already has a Test control where it is
 * configured; duplicating that machinery into a status list would be a second implementation that
 * could disagree with the first.
 *
 * ## A gateway that answers 401 is up
 *
 * Reachability is the question, not authorisation: an HTTP answer of any kind means the host and
 * the service are there. So a gateway's root may be 404 or 401 and still be reported as reachable,
 * with the status named — while Ollama and ComfyUI have a defined cheap endpoint, and a non-2xx
 * there is a genuine failure worth showing.
 */

internal enum class HealthKind(val label: String) {
    OLLAMA("Ollama"),
    GATEWAY("Voice Gateway"),
    IMAGE("Image generation"),
}

/** One server the health screen can ask. [config] carries the auth fields and the image backend. */
internal data class HealthTarget(
    val kind: HealthKind,
    val name: String,
    val url: String,
    val config: ServerConfig?,
) {
    val key: String get() = "${kind.name}:${url.trimEnd('/')}"
}

/** The outcome of one on-demand check. [Checking] is a display state owned by the screen. */
internal sealed interface HealthResult {
    data object Checking : HealthResult
    data class Ok(val latencyMs: Long, val detail: String) : HealthResult
    data class Failed(val latencyMs: Long?, val reason: String) : HealthResult
}

/**
 * The targets, from the three configured lists, deduplicated within a kind by URL — a duplicated
 * entry would otherwise be asked twice and shown twice.
 */
internal fun healthTargetsFor(manager: SettingsManager): List<HealthTarget> {
    val targets = mutableListOf<HealthTarget>()
    (manager.getOllamaBases() ?: emptyList()).forEach {
        targets += HealthTarget(HealthKind.OLLAMA, it.name, it.url, it)
    }
    (manager.getServerBases() ?: emptyList()).forEach {
        targets += HealthTarget(HealthKind.GATEWAY, it.name, it.url, it)
    }
    (manager.getImageGenBases() ?: emptyList()).forEach {
        targets += HealthTarget(HealthKind.IMAGE, it.name, it.url, it)
    }
    return targets.distinctBy { it.key }
}

private val healthClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()
}

/**
 * Ask one target. Never throws: a failure is a [HealthResult.Failed] with a sentence the screen
 * can show, which is the same contract every other network path in the app follows.
 */
internal suspend fun checkHealth(
    target: HealthTarget,
    client: OkHttpClient = healthClient,
): HealthResult = withContext(Dispatchers.IO) {
    val started = System.nanoTime()
    fun elapsed(): Long = (System.nanoTime() - started) / 1_000_000

    val base = target.url.trimEnd('/')
    val path = when (target.kind) {
        HealthKind.OLLAMA -> "/api/version"
        // The root is the cheapest question a gateway can answer; any status proves it is there.
        HealthKind.GATEWAY -> ""
        HealthKind.IMAGE ->
            if ((target.config?.backendType ?: DEFAULT_IMAGE_GEN_BACKEND) == ImageGenBackend.COMFYUI) {
                "/queue"
            } else {
                "/sdapi/v1/options"
            }
    }

    val builder = Request.Builder().url(base + path).get()
    applyHealthAuth(target.config, builder)

    try {
        client.newCall(builder.build()).execute().use { response ->
            val latency = elapsed()
            if (target.kind == HealthKind.GATEWAY) {
                val detail = if (response.code == 401) {
                    "HTTP ${response.code} — reachable, authentication required"
                } else {
                    "HTTP ${response.code}"
                }
                return@withContext HealthResult.Ok(latency, detail)
            }
            if (!response.isSuccessful) {
                return@withContext HealthResult.Failed(latency, "HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            when (target.kind) {
                HealthKind.OLLAMA -> {
                    val version = runCatching { JSONObject(body).optString("version") }
                        .getOrDefault("")
                    HealthResult.Ok(latency, if (version.isBlank()) "reachable" else "version $version")
                }
                HealthKind.IMAGE -> {
                    val json = runCatching { JSONObject(body) }.getOrNull()
                    val running = json?.optJSONArray("queue_running")?.length()
                    val pending = json?.optJSONArray("queue_pending")?.length()
                    // A1111's options answer is a large object with nothing worth summarising;
                    // ComfyUI's queue is the one genuinely useful detail on this page.
                    val detail = if (running != null || pending != null) {
                        "${running ?: 0} running, ${pending ?: 0} pending"
                    } else {
                        "HTTP ${response.code}"
                    }
                    HealthResult.Ok(latency, detail)
                }
                HealthKind.GATEWAY -> HealthResult.Ok(latency, "HTTP ${response.code}")
            }
        }
    } catch (e: IOException) {
        HealthResult.Failed(elapsed(), e.message ?: e.javaClass.simpleName)
    }
}

/** The same auth shapes the chat and image code use, in one place for this read-only request. */
private fun applyHealthAuth(config: ServerConfig?, builder: Request.Builder) {
    if (config == null) return
    when (config.effectiveAuthType) {
        AuthType.NONE -> Unit
        AuthType.BASIC -> if (!config.username.isNullOrBlank()) {
            builder.header("Authorization", Credentials.basic(config.username, config.password ?: ""))
        }
        AuthType.API_KEY -> config.apiKey?.takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", "Bearer $it")
        }
    }
}

package com.nikosm.voiceassistant

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * The manual update check (About, 2026-10-19).
 *
 * ## It is a button, not a timer
 *
 * PLAN.md §8 and AGENTS.md §5 dropped the automatic update check because it called `api.github.com`
 * from `start()` on every fresh install. That decision stands. This is the other shape: one
 * anonymous GET, only when the user presses **Check for updates** in About. There is no caller on
 * launch, no retry, no interval and no background coroutine; the request sends no user data beyond
 * a `User-Agent` naming the application and its version.
 *
 * ## What it does with the answer
 *
 * The latest GitHub release's `tag_name` is compared numerically with [APP_VERSION] and the result
 * is a sentence in the About window. The check never downloads or installs anything: the link to
 * the releases page is where the user goes, and the package manager they installed with is what
 * upgrades the application. The URL opened is the configured project page, never a URL taken from
 * the response body.
 *
 * ## Failure is a sentence
 *
 * No release yet (404), GitHub's anonymous rate limit (403/429), a transport failure and an answer
 * without a tag all become a readable [UpdateCheckResult.Failed] (or [NoRelease] / [RateLimited]).
 * Nothing is cached, so a later press retries; that is the only retry there is.
 */
sealed interface UpdateCheckResult {
    /** The latest published release is this version or older. */
    data class UpToDate(val latest: String) : UpdateCheckResult

    /** The latest published release is newer than this build. */
    data class Available(val latest: String) : UpdateCheckResult

    /** The project has no published release yet. */
    data object NoRelease : UpdateCheckResult

    /** GitHub is rate-limiting anonymous requests. */
    data object RateLimited : UpdateCheckResult

    /** Anything else, in words the About window can show. */
    data class Failed(val reason: String) : UpdateCheckResult
}

/** The GitHub REST endpoint the About button asks; recorded in PLAN.md §5's URL table. */
internal const val LATEST_RELEASE_API_URL =
    "https://api.github.com/repos/nickthegreekk/Celeste-Voice-Assistant-Linux/releases/latest"

/**
 * Public API for the UI: one check, no arguments, no exposed HTTP types.
 *
 * `:core` owns the OkHttp dependency; `:desktop` and the Settings UI call this and never need to
 * name an `OkHttpClient`.
 */
suspend fun checkForLatestRelease(currentVersion: String = APP_VERSION): UpdateCheckResult =
    checkLatestRelease(LATEST_RELEASE_API_URL, currentVersion, updateCheckClient)

/**
 * The injectable form, for tests that point the check at a loopback server. Internal rather than
 * public so the client and URL stay out of the UI's API.
 */
internal suspend fun checkLatestRelease(
    url: String,
    currentVersion: String,
    client: OkHttpClient = updateCheckClient,
): UpdateCheckResult = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "celeste-linux/$APP_VERSION")
        .build()
    try {
        client.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> parseLatestRelease(response.body?.string().orEmpty(), currentVersion)
                404 -> UpdateCheckResult.NoRelease
                403, 429 -> UpdateCheckResult.RateLimited
                else -> UpdateCheckResult.Failed("GitHub answered HTTP ${response.code}.")
            }
        }
    } catch (e: IOException) {
        UpdateCheckResult.Failed("Could not reach GitHub: ${e.message ?: "network error"}.")
    }
}

/** The answer's `tag_name`, compared as a version; malformed answers are a failure, not an update. */
internal fun parseLatestRelease(body: String, currentVersion: String): UpdateCheckResult {
    val tag = runCatching { JSONObject(body).optString("tag_name").trim() }
        .getOrNull()
        .orEmpty()
        .take(64)
    if (tag.isEmpty()) return UpdateCheckResult.Failed("GitHub's answer did not name a release.")
    return if (isNewerVersion(tag, currentVersion)) {
        UpdateCheckResult.Available(tag)
    } else {
        UpdateCheckResult.UpToDate(tag)
    }
}

/**
 * Numeric comparison of release tags. A leading `v` is ignored; the numeric components are compared
 * left to right, with a missing component read as zero, so `0.10.0` is newer than `0.2.0` and
 * `v0.2.0` equals `0.2.0`. A tag with no numeric core is never newer: an update prompt is a claim
 * the application should not make on a string it cannot parse.
 */
internal fun isNewerVersion(latest: String, current: String): Boolean {
    fun parts(value: String): List<Int> = value.trim()
        .removePrefix("v")
        .removePrefix("V")
        .split('.', '-', '+')
        .mapNotNull { it.toIntOrNull() }

    val a = parts(latest)
    val b = parts(current)
    if (a.isEmpty()) return false
    for (index in 0 until maxOf(a.size, b.size)) {
        val left = a.getOrElse(index) { 0 }
        val right = b.getOrElse(index) { 0 }
        if (left != right) return left > right
    }
    return false
}

/**
 * A small client for one public endpoint. It deliberately does not use the pinned client: that one
 * is for the user's own servers and their TOFU certificates, while `api.github.com` is a public CA
 * host. The timeouts are short because this is a status check a person is waiting on.
 */
private val updateCheckClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .callTimeout(12, TimeUnit.SECONDS)
    .build()

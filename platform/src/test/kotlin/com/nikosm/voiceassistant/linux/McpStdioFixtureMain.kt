package com.nikosm.voiceassistant.linux

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A scripted MCP server, as a real process for [McpStdioTransportTest] to talk to.
 *
 * It exists because the transport's job is the process plumbing — spawning, framing, closing, the
 * force-kill — and a fake transport would exercise none of it. The script is deliberately small:
 * initialize, a two-page `tools/list`, two `tools/call` outcomes and an unknown method. Everything
 * above the bytes (handshake, retries, approval) is tested against a fake transport in `:core`,
 * where a process would only slow the test down.
 *
 * It speaks kotlinx.serialization's JSON rather than the application's `org.json`: this is test
 * code in `:platform`, and `org.json` is core-internal on purpose.
 */
fun main(args: Array<String>) {
    // The wedged-server shape: never read stdin, never write stdout, never exit. A large send
    // fills the stdin pipe and blocks in flush(), which is what close() has to break.
    if (args.firstOrNull() == "silent") {
        Thread.sleep(Long.MAX_VALUE)
        return
    }
    System.`in`.bufferedReader(Charsets.UTF_8).forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        val request = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrElse { return@forEachLine }
        // A notification carries no id and wants no answer.
        val id = request["id"] ?: return@forEachLine
        val method = request["method"]?.jsonPrimitive?.content ?: return@forEachLine
        val params = request["params"] as? JsonObject

        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            when (method) {
                "initialize" -> put(
                    "result",
                    buildJsonObject {
                        put("protocolVersion", "2025-06-18")
                        put(
                            "capabilities",
                            buildJsonObject { put("tools", buildJsonObject { put("listChanged", true) }) },
                        )
                        put("serverInfo", buildJsonObject { put("name", "fixture"); put("version", "1.0") })
                    },
                )

                "tools/list" -> {
                    val cursor = params?.get("cursor")?.jsonPrimitive?.content
                    put(
                        "result",
                        if (cursor == null) {
                            buildJsonObject {
                                put(
                                    "tools",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("name", "echo")
                                                put("description", "Echo the text argument back.")
                                                put(
                                                    "inputSchema",
                                                    buildJsonObject {
                                                        put("type", "object")
                                                        put(
                                                            "properties",
                                                            buildJsonObject {
                                                                put("text", buildJsonObject { put("type", "string") })
                                                            },
                                                        )
                                                    },
                                                )
                                            },
                                        )
                                        add(
                                            buildJsonObject {
                                                put("name", "fail")
                                                put("description", "Always reports an error.")
                                            },
                                        )
                                    },
                                )
                                put("nextCursor", "page-2")
                            }
                        } else {
                            buildJsonObject {
                                put(
                                    "tools",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("name", "slow")
                                                put("description", "Only on the second page.")
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )
                }

                "tools/call" -> {
                    val name = params?.get("name")?.jsonPrimitive?.content
                    val text = params?.get("arguments")?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
                    put(
                        "result",
                        if (name == "fail") {
                            buildJsonObject {
                                put("isError", true)
                                put(
                                    "content",
                                    buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "boom") }) },
                                )
                            }
                        } else {
                            buildJsonObject {
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(buildJsonObject { put("type", "text"); put("text", "echo: $text") })
                                    },
                                )
                            }
                        },
                    )
                }

                "ping" -> put("result", buildJsonObject {})

                else -> put(
                    "error",
                    buildJsonObject {
                        put("code", -32601)
                        put("message", "Method not found: $method")
                    },
                )
            }
        }
        print(response.toString())
        print('\n')
        System.out.flush()
    }
}

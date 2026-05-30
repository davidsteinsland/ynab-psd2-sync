package com.github.davidsteinsland.ynab_psd2_sync

import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class NtfyClient(
    private val topic: String,
    private val objectMapper: ObjectMapper,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {

    enum class Priority(val code: Int, val value: String) {
        MIN(1, "min"),
        LOW(2, "low"),
        DEFAULT(3, "default"),
        HIGH(4, "high"),
        MAX(5, "urgent");
    }
    fun notify(title: String, tags: List<String>, body: String, priority: Priority = Priority.DEFAULT) {
        val payload = objectMapper.writeValueAsString(mapOf(
            "topic" to topic,
            "title" to title,
            "priority" to priority.code,
            "tags" to tags,
            "message" to body,
        ))
        val req = HttpRequest.newBuilder()
            .uri(URI.create("https://ntfy.sh"))
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding())
        if (resp.statusCode() !in 200..299) {
            error("ntfy svarte med ${resp.statusCode()} for sync-varsel")
        }
    }
}
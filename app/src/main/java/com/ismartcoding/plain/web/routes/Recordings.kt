package com.ismartcoding.plain.web.routes

import com.ismartcoding.plain.data.RecordingsMetaDb
import com.ismartcoding.plain.web.HttpServerManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File
import java.net.URLEncoder

/**
 * Streams a hidden recording by id with optional `?download=1`. Auth is
 * enforced via the standard `c-id` + token-cache pattern, so URLs only
 * resolve for authenticated web sessions / nothing leaks if the URL is
 * shared. Range requests are supported via the global PartialContent plugin.
 */
fun Route.addRecordings() {
    get("/recordings/{id}") {
        val clientId = call.request.header("c-id") ?: ""
        if (clientId.isEmpty() || HttpServerManager.tokenCache[clientId] == null) {
            // Fallback: also accept ?c=<clientId> so HTML <video>/<audio> tags can authenticate
            // without setting custom headers (browsers can't add headers to media element fetches).
            val q = call.request.queryParameters["c"] ?: ""
            if (q.isEmpty() || HttpServerManager.tokenCache[q] == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }
        }

        val id = call.parameters["id"] ?: ""
        val row = RecordingsMetaDb.get(id)
        if (row == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val file = File(row.filePath)
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        val displayName = (row.name.ifBlank { file.nameWithoutExtension }) + "." + file.extension
        val encoded = URLEncoder.encode(displayName, "UTF-8").replace("+", "%20")
        call.response.header("Access-Control-Expose-Headers", "Content-Disposition")
        val disposition = if (call.request.queryParameters["download"] == "1") "attachment" else "inline"
        call.response.header(
            "Content-Disposition",
            "$disposition; filename=\"$encoded\"; filename*=utf-8''$encoded",
        )

        val contentType = runCatching { ContentType.parse(row.mimeType) }.getOrElse { ContentType.Application.OctetStream }
        call.respond(LocalFileContent(file, contentType))
    }
}

package com.example.auto_dead_link_remover.data

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

/**
 * Lightweight local HTTP server that serves the clean IPTV playlist.
 *
 * Optimizations:
 * - Sends Content-Length header so IPTV players know the file size upfront.
 * - Sends Cache-Control and Last-Modified so players can skip re-downloads.
 * - Only responds to /playlist.m3u — returns 404 for everything else.
 */
class LocalIptvServer(private val port: Int, private val playlistFile: File) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession?): Response {
        val uri = session?.uri ?: return notFound()

        if (uri == "/playlist.m3u" || uri == "/playlist") {
            if (!playlistFile.exists()) {
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Playlist not ready yet. Scan in progress..."
                )
            }

            val fileLength = playlistFile.length()
            val lastModified = playlistFile.lastModified()
            val fis = FileInputStream(playlistFile)

            // Use newFixedLengthResponse with known size — more efficient than chunked
            val response = newFixedLengthResponse(
                Response.Status.OK,
                "audio/mpegurl",
                fis,
                fileLength
            )

            // Help IPTV players cache intelligently
            response.addHeader("Content-Disposition", "inline; filename=\"playlist.m3u\"")
            response.addHeader("Cache-Control", "public, max-age=60")
            response.addHeader("Last-Modified", java.util.Date(lastModified).toString())
            response.addHeader("Access-Control-Allow-Origin", "*")

            return response
        }

        // Health check endpoint
        if (uri == "/status") {
            return newFixedLengthResponse(
                Response.Status.OK,
                MIME_PLAINTEXT,
                "OK - Playlist file exists: ${playlistFile.exists()}, size: ${if (playlistFile.exists()) playlistFile.length() else 0} bytes"
            )
        }

        return notFound()
    }

    private fun notFound(): Response {
        return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            MIME_PLAINTEXT,
            "Not found. Use /playlist.m3u to access the clean playlist."
        )
    }
}

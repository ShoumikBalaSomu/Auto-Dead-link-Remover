package com.example.auto_dead_link_remover.data

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

class LocalIptvServer(private val port: Int, private val playlistFile: File) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession?): Response {
        if (session?.uri == "/playlist.m3u") {
            return if (playlistFile.exists()) {
                val fis = FileInputStream(playlistFile)
                newChunkedResponse(Response.Status.OK, "audio/mpegurl", fis)
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Playlist not found or not ready.")
            }
        }
        return super.serve(session)
    }
}

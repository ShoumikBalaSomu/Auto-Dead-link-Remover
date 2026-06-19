# Auto-Dead-link-Remover

A background Android TV application that automatically removes dead links from your IPTV playlists.

## Overview
This application runs natively on your Android TV as a background service. It periodically checks the status of the streams in your provided IPTV playlist (.m3u, .m3u8, .json) using fast HTTP HEAD requests, filtering out any dead links.

It then embeds a local HTTP server (`NanoHTTPD`), serving the freshly cleaned playlist directly to your local network on `http://localhost:8080/playlist.m3u`.

## Features
- 📺 **Runs seamlessly** in the background on any Android TV.
- 🔄 **Periodically checks** your IPTV playlist and removes dead channels.
- 🌐 **Embeds a local HTTP server** (`localhost:8080`) to stream clean playlists to your favorite IPTV player (e.g. TiviMate).
- 🚀 **Auto-starts on boot** via a Broadcast Receiver so you never have to worry about starting it manually.

## Usage
1. Build the APK using Android Studio or by running `./gradlew assembleDebug` in your terminal.
2. Install the APK on your Android TV.
3. Open the app, paste your original source M3U/M3U8 link, and click **Save & Start Service**.
4. Open your favorite IPTV app on the TV and add a new playlist with the URL: `http://localhost:8080/playlist.m3u`.

## License
MIT License. See [LICENSE](LICENSE) for more information.

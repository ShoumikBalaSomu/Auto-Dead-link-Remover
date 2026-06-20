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

## Installation & Building
You can compile this Android APK directly from your computer terminal or Android Studio.

### Option 1: Android Studio (Recommended for Beginners)
1. Download and install [Android Studio](https://developer.android.com/studio).
2. Click **Open** and select the `Auto-Dead-link-Remover` project folder.
3. Wait for Gradle to sync.
4. Click the green **Play** button (Run) to install it directly to your connected Android TV or Emulator.

### Option 2: Fedora Linux (Terminal)
If you prefer building from the command line in Fedora:
```bash
# 1. Install Java and Android Tools
sudo dnf install java-latest-openjdk-devel android-tools

# 2. Download Android Command Line Tools to your home directory
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-*_latest.zip
mv cmdline-tools latest && rm commandlinetools-linux-*_latest.zip

# 3. Setup Environment Variables (you can add this to ~/.bashrc)
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# 4. Accept Licenses and Build
yes | sdkmanager --licenses
cd /path/to/Auto-Dead-link-Remover
./gradlew assembleDebug

# 5. Connect and Install to your TV
adb connect <YOUR_TV_IP>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.auto_dead_link_remover/.MainActivity
```

### Option 3: Ubuntu / Debian (Terminal)
Building from the command line in Ubuntu:
```bash
# 1. Install Java and Android Tools
sudo apt update
sudo apt install openjdk-17-jdk adb wget unzip

# 2. Download Android Command Line Tools to your home directory
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-*_latest.zip
mv cmdline-tools latest && rm commandlinetools-linux-*_latest.zip

# 3. Setup Environment Variables (you can add this to ~/.bashrc)
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# 4. Accept Licenses and Build
yes | sdkmanager --licenses
cd /path/to/Auto-Dead-link-Remover
./gradlew assembleDebug

# 5. Connect and Install to your TV
adb connect <YOUR_TV_IP>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.auto_dead_link_remover/.MainActivity
```

### Option 4: Google Colab (No local setup required!)
If you don't want to install anything on your computer, you can build the APK directly in your browser using Google Colab.

1. Go to [Google Colab](https://colab.research.google.com/) and create a new Notebook.
2. Paste the following code into a single cell and click **Run**:

```bash
# 1. Install Java and download Android Tools
!apt-get update -qq && apt-get install openjdk-17-jdk wget unzip -y -qq
!mkdir -p /root/android-sdk/cmdline-tools
!wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmd.zip
!unzip -q /tmp/cmd.zip -d /root/android-sdk/cmdline-tools
!mv /root/android-sdk/cmdline-tools/cmdline-tools /root/android-sdk/cmdline-tools/latest

# 2. Setup Environment Variables
import os
os.environ['ANDROID_HOME'] = '/root/android-sdk'
os.environ['PATH'] += ':/root/android-sdk/cmdline-tools/latest/bin:/root/android-sdk/platform-tools'

# 3. Accept Licenses and Clone Repository
!yes | sdkmanager --licenses > /dev/null
!git clone https://github.com/ShoumikBalaSomu/Auto-Dead-link-Remover.git

# 4. Build the APK
%cd Auto-Dead-link-Remover
!chmod +x gradlew
!./gradlew assembleDebug
```

3. Once the build finishes successfully, look at the **Files panel** on the left side of Colab.
4. Navigate to `Auto-Dead-link-Remover/app/build/outputs/apk/debug/`.
5. Right-click on `app-debug.apk` and select **Download**.
6. Transfer the APK to your phone or TV and install it!

## Usage
1. Open the app on your Android Phone or TV.
2. Select your source type (M3U, Xtream, or MAC) and enter your playlist links or credentials.
3. Configure your interval, timeout, and max concurrent scan limits.
4. Click **Start**.
5. Open your favorite IPTV app and add a new playlist with the URL shown in the app: `http://localhost:8080/playlist.m3u`. (You can also share this URL with other devices on your Wi-Fi network!)

## License
MIT License. See [LICENSE](LICENSE) for more information.

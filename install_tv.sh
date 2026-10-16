#!/usr/bin/env bash
# ==============================================================================
# ParentsTV - Quick Fire TV / Android TV Remote Installer
# ==============================================================================

set -e

APK_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/ParentsTV.apk"
if [ ! -f "$APK_PATH" ]; then
    APK_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/app/build/outputs/apk/release/app-release.apk"
fi

if [ ! -f "$APK_PATH" ]; then
    echo "❌ Error: Could not find ParentsTV.apk"
    echo "Please build the APK first with: ./gradlew assembleRelease"
    exit 1
fi

echo "================================================="
echo "📺 ParentsTV 1-Click TV Installer"
echo "================================================="

# Ensure ADB is available
if ! command -v adb &> /dev/null; then
    echo "❌ Error: adb command not found."
    echo "Please install Android Platform Tools (adb) or run inside Android SDK."
    exit 1
fi

# Determine target IP
TARGET_IP="$1"
if [ -z "$TARGET_IP" ]; then
    # Check if a device is already connected
    CONNECTED=$(adb devices | grep -E "device$" | head -n 1 | awk '{print $1}')
    if [ -n "$CONNECTED" ]; then
        echo "✅ Detected already connected device: $CONNECTED"
        TARGET="$CONNECTED"
    else
        read -p "Enter Fire TV / Android TV IP address (e.g. 192.168.1.100): " TARGET_IP
        if [ -z "$TARGET_IP" ]; then
            echo "❌ No IP address provided. Exiting."
            exit 1
        fi
        TARGET="$TARGET_IP:5555"
    fi
else
    if [[ "$TARGET_IP" == *:* ]]; then
        TARGET="$TARGET_IP"
    else
        TARGET="$TARGET_IP:5555"
    fi
fi

# Connect if needed
if [[ "$TARGET" == *:* ]]; then
    echo "📡 Connecting to Fire TV at $TARGET..."
    adb connect "$TARGET"
    sleep 1
fi

echo "📦 Installing ParentsTV ($APK_PATH) to $TARGET..."
adb -s "$TARGET" install -r -d "$APK_PATH"

echo "🚀 Launching ParentsTV on your TV..."
adb -s "$TARGET" shell am start -n com.parents.tv/com.parents.tv.ui.MainActivity

echo ""
echo "================================================="
echo "🎉 SUCCESS! ParentsTV is now installed and running!"
echo "Enjoy watching with your parents! ❤️"
echo "================================================="

#!/usr/bin/env bash
# ==============================================================================
# ParentsTV - Quick Fire TV / Android TV Remote Installer
# ==============================================================================

set -e

APK_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/TV.apk"
if [ ! -f "$APK_PATH" ]; then
    APK_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/app/build/outputs/apk/debug/app-debug.apk"
fi
if [ ! -f "$APK_PATH" ]; then
    APK_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/ParentsTV.apk"
fi

if [ ! -f "$APK_PATH" ]; then
    echo "❌ Error: Could not find TV.apk"
    echo "Please build the APK first with: ./gradlew assembleDebug"
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
        TARGET_IP="192.168.29.130"
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
    sleep 2
fi

echo "📦 Installing ParentsTV ($APK_PATH) to $TARGET..."
adb -s "$TARGET" install -r -d "$APK_PATH"

echo "🚀 Launching ParentsTV on your TV..."
adb -s "$TARGET" shell am start -n com.parents.tv.debug/com.parents.tv.ui.MainActivity 2>/dev/null || \
adb -s "$TARGET" shell am start -n com.parents.tv/com.parents.tv.ui.MainActivity

echo ""
echo "================================================="
echo "🎉 SUCCESS! ParentsTV is now installed and running!"
echo "Enjoy watching with your parents! ❤️"
echo "================================================="

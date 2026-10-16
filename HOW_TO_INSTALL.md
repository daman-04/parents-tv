# 📺 ParentsTV - How to Install on Fire TV / Android TV

ParentsTV is an ultra-lightweight (1.9 MB), zero-ad, elder-friendly Live TV player designed specifically for Indian parents with instant channel switching, automatic 1080p stream fallbacks, and intuitive Fire TV remote controls.

---

## ⚡ Method 1: Direct on TV using "Downloader" App (Recommended - No Computer Needed!)

1. **Enable Developer Options / Install Unknown Apps on Fire TV:**
   - Go to Fire TV **Settings ⚙️** → **My Fire TV** → **About**.
   - Hover over your device name (e.g., *Fire TV Stick Lite*) and **click the remote center button 7 times quickly** until it says *"No need, you are already a developer"*.
   - Press **Back**, open **Developer Options**.
   - Turn **ADB Debugging** `ON`.
   - Select **Install unknown apps** → Turn **Downloader** `ON`.

2. **Open the "Downloader" app on Fire TV:**
   - Search for **Downloader** (orange icon by AFTVnews) in the Amazon Appstore and install it.
   - Open Downloader and grant storage permissions.

3. **Download & Install:**
   - In the URL field, enter the direct download link for the release APK:
     ```
     https://github.com/daman-04/parents-tv/releases/download/v1.0.0/ParentsTV.apk
     ```
   - Downloader will immediately download the 1.9 MB file and prompt **Install**.
   - Click **Install**, then click **Open**!

---

## 💻 Method 2: 1-Click Install from Computer (Mac / Windows / Linux)

If your computer is on the same home Wi-Fi as the Fire TV:

1. **Find Fire TV IP Address:**
   - On Fire TV: **Settings ⚙️** → **My Fire TV** → **About** → **Network**. Note the IP Address (e.g. `192.168.1.50`).
   - Ensure **Developer Options** → **ADB Debugging** is `ON`.

2. **Run the 1-Click Script:**
   ```bash
   ./install_tv.sh 192.168.1.50
   ```
   *(Replace with your Fire TV's actual IP address)*

The script will connect, install the APK, and launch the TV app automatically!

---

## 📱 Method 3: Send from Phone ("Send Files to TV" App)

1. Download `ParentsTV.apk` on your Android phone.
2. Install **"Send Files to TV"** on both your phone and Fire TV from the Google Play Store / Amazon Appstore.
3. Also install **"CX File Explorer"** or **"Total Commander"** on your Fire TV.
4. Send the APK from your phone to Fire TV.
5. On the TV, open the file explorer, navigate to the `Download` folder, click `ParentsTV.apk`, and select **Install**.

---

## 🎮 Remote Control Shortcuts for Parents

| Button on Remote | Action |
| :--- | :--- |
| **▲ / ▼ (Up / Down)** | **Instant Next / Previous Channel** (< 1.5s switch) |
| **◀ (Left)** | **Recall** — switches back to previous watched channel |
| **▶ (Right)** | **Favorites** — opens favorite channels list |
| **OK (Center)** | **Channel Guide** (Browse Punjabi, Sports, Devotional, News) |
| **Hold OK (Long Press)** | **★ Save / Unsave Favorite** |
| **☰ (Menu)** | **Quick Settings** (Aspect Ratio per-channel, Server Cycle, Grid/List view) |

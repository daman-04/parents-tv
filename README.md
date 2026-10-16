# 📺 ParentsTV

> **An ultra-lightweight (1.9 MB), zero-ad, elder-friendly Live TV application engineered specifically for Fire TV and Android TV devices.**

ParentsTV brings immediate, seamless, and buffer-free Indian television to elderly parents—with zero complex navigation, instant startup on Gurbani (PTC Simran 1080p), and full D-pad remote support.

---

## ✨ Key Features

- ⚡ **Instant Launch:** Opens straight to **PTC Simran 1080p** live stream on cold boot without touching a button.
- 📺 **Channel Hierarchy:**
  1. **Punjabi & Devotional:** PTC Simran, PTC Punjabi, PTC News, PTC Chakde, PTC Music, PTC Punjabi Gold, DD Punjabi, 9X Tashan, Akaal Channel.
  2. **Live Sports in HD:** Star Sports 1, Star Sports 2, Star Sports Select 1/2, Sony Sports Ten 1/2/3/5, Ten Cricket, Willow Cricket HD, DD Sports HD, PTV Sports, EuroSports.
  3. **News & Entertainment:** Aaj Tak, India TV, Zee News, ABP News, NDTV, Colors, Star Plus, Zee TV, Sony SAB.
- 🎮 **Senior-Friendly Remote Control:**
  - **▲ / ▼ (Up / Down):** Instant channel switching (< 1.5 seconds)
  - **◀ (Left):** Instant Recall (switches back to last watched channel)
  - **▶ (Right):** Favorites menu
  - **OK (Center):** Visual Channel Guide with category tabs (Auto-filters on focus)
  - **Hold OK:** Toggle Favorite (★)
  - **☰ (Menu):** Quick Settings (per-channel aspect ratio Fit / Stretch / Zoom, stream server cycle)
- 🚀 **High Performance:**
  - APK size: **1.9 MB** (minified with R8 & resource shrinking)
  - RAM consumption: **< 120 MB**
  - Native ExoPlayer 3 with hardware MediaCodec decoding (`SurfaceView`)
  - Resilient OkHttp connection pooling & auto transient error recovery
  - Unmasks disguised stream segments (TikTok CDN / DaddyLive relay fallback)

---

## 📥 How to Install

See detailed step-by-step instructions in [HOW_TO_INSTALL.md](file:///Users/daman/Documents/TV/HOW_TO_INSTALL.md).

### Quick Install via 1-Click Script
```bash
git clone https://github.com/daman-04/parents-tv.git
cd parents-tv
./install_tv.sh <FIRE_TV_IP>
```

### Direct Download Link
Download the latest APK directly from GitHub Releases:
- **Download:** [ParentsTV.apk](https://github.com/daman-04/parents-tv/releases/download/v1.0.0/ParentsTV.apk)

---

## 🛠 Tech Stack

- **Platform:** Android TV / Amazon Fire OS (API 22+)
- **Architecture:** Kotlin, MVVM, StateFlow, Coroutines
- **Video Engine:** Media3 ExoPlayer 1.5.1 + Media3 OkHttp DataSource
- **Network:** OkHttpClient 4.12.0 with connection pooling
- **Image Loading:** Coil 2.6.0 (hardware-bitmap caching)

---

## 📄 License
Personal and family use.

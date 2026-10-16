# Parents Live TV App: PRD and Agent Instructions

> This file is for the coding agent (Antigravity). Read all of it first.
> Then work ONE STEP at a time. After each step, stop, report, and wait for the owner to say "next".

---

## 0. Rules for the agent (read first)

1. **Work in small steps.** Do only the step the owner asks for. Do not jump ahead.
2. **Build after every change.** The project must compile before you report.
3. **Test on the real Fire TV Stick** (not only the emulator). Report what you saw.
4. **Edit existing files.** Do not rewrite a whole file when a small edit is enough.
5. **Keep it light.** Every extra library slows an old stick. Ask before adding any library not listed in section 4.
6. **Stop and ask** if a step fails twice, or if something in this file looks wrong or impossible. Do not guess.
7. **Report format after each step:** (a) what you did, (b) what files changed, (c) test result, (d) numbers measured (see section 10), (e) problems, (f) what the next step is.
8. **Explain in simple English.** The owner is a student, not a Android expert. Explain any technical word in plain words.
9. **Personal home use only.** Do not add anything for publishing or sharing.

---

## 1. Goal

A live TV app for the owner's elderly parents on an **old 1080p Amazon Fire TV Stick**.

- Open the app and the **last watched channel plays at once**.
- Very easy: big tiles, few buttons, remote only (arrows, OK, Back, mic).
- **Super fast and lag free.** Speed is the top priority.
- **Punjabi channels first.** All other languages are available but load only when opened.
- **Voice typing search**, because the Fire remote has no number keys.
- **Channel list updates itself** every day. The app can also update itself.
- No accounts, no movies, no series, no add-ons, no settings screens.

## 2. Users

- Parents: elderly, low tech comfort, remote only.
- Owner (Daman): sets up, fixes problems remotely, updates by `adb` or the update screen.

## 3. Base and design reference

- A copy of the **Nuvio TV** repo is cloned on the owner's computer. It is used **only as a design reference**.
- **Do not fork or build on Nuvio's code.** Reason: Nuvio needs Android 10 or newer, and uses Jetpack Compose, Hilt and other heavy parts. That is a bad fit for an old stick.
- **Keep Nuvio's look:** colours, fonts, rounded corners, spacing, focus highlight style.
- **Do not copy its layouts.** Nuvio is made for movie posters (hero banners, many rows, small text). Parents need fewer things, much bigger.
- Nuvio's licence is GPL v3. Do not copy its code anyway, and keep this app private.

---

## 4. Tech stack (speed first)

| Part | Use | Do NOT use |
|---|---|---|
| Language | Kotlin, native Android | Flutter, React Native, web views |
| Screens | Classic Android views (XML) with `RecyclerView` | Jetpack Compose |
| Video | Media3 ExoPlayer, HLS part only, on `SurfaceView` | VLC, big media libraries, `TextureView` |
| Helper frameworks | None. Plain code | Hilt, Retrofit, Room |
| Network | OkHttp | Extra network libraries |
| Images | Coil, resized to tile size | Large images, GIFs |
| Saved data | `SharedPreferences` (last channel, view choice) + one cached JSON file (channels) | Database |
| Minimum Android | Whatever the stick needs (found in Step 1). API 22 if very old | API 29 |
| Build | R8 code shrinking on, strip unused code | Extra libraries |

---

## 5. Product requirements

### 5.1 Launch and last channel
- On launch, read the last channel from saved data and **start the video before the grid is ready**.
- First launch ever: play the default channel from the remote control file (section 8). If none, play the first Punjabi channel.
- If the channel fails: show "Channel not working" and move to the next channel after 5 seconds.

### 5.2 Home screen (grid)
- Top: **"Last watched"** tile, always first.
- Then the **Punjabi** section (top priority).
- A row of big **language buttons:** Punjabi, Hindi, English, Devotional, News, Music (others come from data). Opening one loads its channels only then.
- Max **4 tiles per row**. Text at least **24sp**. High contrast. **Thick focus border.**
- Only draw tiles that are on screen (`RecyclerView`). Load logos lazily.

### 5.3 List view
- One big vertical list with logo and large name.
- One button switches between grid and list. The choice is remembered.

### 5.4 While watching
- **Up / Down:** next / previous channel (inside the current section).
- **OK:** open the grid. **Back:** also open the grid.
- No hidden gestures. No auto-hiding menus.
- The Fire remote has **no number keys**. Add a **"Recent" row** (last 6 channels) for quick jumping.

### 5.5 Search by voice typing (see section 7)

### 5.6 Visual rules
- Effects: small scale-up and a border only, about **120 ms**.
- **No blur, shadows, fades or auto-playing banners.**
- One action per button press. Everything works with arrows, OK and Back.

---

## 6. Channel data

### 6.1 Sources
- Main data: the **IPTV-org** open lists (channels, streams, languages, logos). Famelack gets its channels from IPTV-org.
- Likely URLs (verify before use; if they changed, look in the `iptv-org/api` repo):
  - `https://iptv-org.github.io/api/channels.json`
  - `https://iptv-org.github.io/api/streams.json`
  - `https://iptv-org.github.io/api/languages.json`
  - `https://iptv-org.github.io/api/logos.json`
- The two pages the owner gave are web pages, not data feeds. Use them only to cross-check:
  - `https://zhangboheng.github.io/Easy-Web-TV-M3u8/catalogues/tvplay.html?type=countries&key=in&title=India`
  - `https://famelack.com/tv/in`

### 6.2 Which channels
- **Punjabi:** language Punjabi (code `pan`) from **any country** (many are listed under Pakistan, Canada, UK).
- **Everything else:** country India, grouped by language and category.
- All channels are included (not a short hand-picked list), but only Punjabi shows on the home screen first.

### 6.3 Daily script (runs on GitHub, not on the stick)
- A **GitHub Action** (free) runs once a day and builds one **small JSON file**.
- For each channel: id, name, logo, language, category, up to 3 stream links, status.
- Check each stream link. Mark it `working`, `dead` or `unverified`.
- **Geo-lock warning:** the checker runs outside India. Some Indian channels may look dead but work at home. Mark these `unverified`, show them last, and **do not delete them**.
- Apply the overrides file (section 8).
- Publish the result on GitHub Pages.

Example output (shape only):

```json
{
  "generated": "2026-10-16T00:00:00Z",
  "sections": [
    {
      "id": "punjabi",
      "title": "Punjabi",
      "channels": [
        {
          "id": "PTCPunjabi.in",
          "name": "PTC Punjabi",
          "logo": "https://.../logo.png",
          "category": "general",
          "streams": [
            { "url": "https://.../index.m3u8", "status": "working" }
          ],
          "aliases": ["PTC", "पीटीसी", "ਪੀਟੀਸੀ"]
        }
      ]
    }
  ]
}
```

### 6.4 In the app
- On start, download the file **in the background** if the saved copy is older than 24 hours. Never block the video.
- Keep the **last good copy**. If the new file is empty or broken, keep the old one.
- New channels appear in a **"New"** row. They do not join the sections until the owner approves.
- Match channels by **id**, not by link. If a link changes, use the new one.
- If a stream fails: try its backup links. If all fail, show "Channel not working" and go to the next channel after 5 seconds.
- Count failures on the device. Move a channel with repeated failures to the bottom of its section.

---

## 7. Voice typing and search

How it should work (plain words):
- Fire TV already has voice typing. When a **text box** is on screen, holding the **mic button** on the remote and speaking types the words into the box. The app only needs a normal text box.
- The app searches **as the words appear**.

Requirements:
- A big **Search** button on the home screen.
- The search screen has a **big text box**. The on-screen keyboard is **not** shown by default. Show a clear hint: "Hold the mic button on your remote and say the channel name."
- **Secondary method:** an on-screen mic button using Android speech recognition. Show it **only if** Step 3 proves it works on this stick. Otherwise hide it.
- Languages: **Punjabi, Hindi, English.** Names may arrive in Gurmukhi, Devanagari or English letters.
- Matching order, live as text appears:
  1. Exact alias.
  2. Fuzzy match (handles spelling and sound: "Aaj Tak", "Aajtak", "आज तक").
  3. Category and language words: "news", "bhajan", "Punjabi", "cricket".
- Result:
  - One clear match: show "Playing [name]. Press Back to cancel." and play after 1.5 seconds.
  - Unclear: show the top 6 matches as big tiles.
  - No match: "No channel found. Try again."
- Aliases come from the overrides file (section 8) so names can be added without a new app.
- Backup idea to mention to the owner: the Fire TV phone app can type or speak into the TV's text box from a phone.

---

## 8. Remote control file (for the owner)

One JSON file on GitHub Pages. The app downloads it with the channel file.

It holds:
- default channel
- section order (Punjabi first)
- pinned channels and hidden channels
- aliases (extra names and spellings per channel)
- channel order inside sections

Rules:
- The owner can fix problems from a phone, and the TV picks it up on its own.
- If the file cannot be reached, use the saved copy.

---

## 9. App update

- Once a day, in the background, check **GitHub Releases** for a newer version.
- Fire TV **cannot install silently.** Show one big screen: "Update available. Press OK."
- Backup: the owner updates with `adb install -r` over Wi-Fi.

---

## 10. Speed budget (must pass on the real stick)

| Item | Target |
|---|---|
| App screen visible | under 1.5 s |
| Last channel playing | under 4 s on a normal connection (if a slow channel server is the cause, say so) |
| Channel change | under 3 s |
| Scrolling | smooth with all channels loaded |
| Memory use | under 150 MB |
| APK size | under 15 MB |

How to reach it:
- Start the video before the grid loads.
- **One player, reused.** Do not rebuild it on channel change. Swap the stream.
- Small start buffer. Cap video quality at **720p**.
- Use `SurfaceView`.
- Background work (downloads, link checks) never runs while a channel is starting. Max 3 at once.
- Load other languages only when opened.

---

## 11. Not in scope

Movies, series, accounts, add-ons, recording, rewind, subtitles, casting, TV guide (EPG), settings screens.

---

## 12. Step-by-step plan (do one step, then stop and report)

### Step 1: Detect the device
- Owner enables ADB on the stick: Settings, My Fire TV, Developer options, ADB debugging on. Owner gives the stick's IP address.
- Run `adb connect <ip>:5555`.
- Run and report: `adb shell getprop ro.product.model`, `adb shell getprop ro.build.version.release`, `adb shell getprop ro.build.version.sdk`, `adb shell cat /proc/meminfo` (free memory), and the screen size.
- Decide the minimum Android level from this.
- **Done when:** a short device report is written and the owner agrees.

### Step 2: Design file from Nuvio
- Read the cloned Nuvio repo and its screenshots.
- Write `design.md`: colours, fonts, corner roundness, spacing, focus style, tile size.
- Adapt it for elderly use (bigger text, thicker focus). Do not use blur or shadows.
- **Done when:** `design.md` exists and the owner approves it.

### Step 3: Voice typing test (do this early)
- Make a tiny test screen with a text box and a mic button.
- Test (a): does holding the remote's mic button type into the text box?
- Test (b): does in-app speech recognition work on this stick?
- Report both results clearly (works / does not work / partly).
- **Done when:** results are reported. If both fail, stop and ask the owner what to do (the search screen will use the on-screen keyboard and phone app instead).

### Step 4: Project and player
- Create the new native Android TV project with the stack in section 4.
- Play a test HLS stream full screen. Add Up/Down to switch between 3 test channels.
- Save and load the last channel. Start the video on launch.
- Measure start time and memory on the stick.
- **Done when:** the app installs on the stick, plays video, resumes the last channel, and the numbers are reported.

### Step 5: Data and daily script
- Write a script that builds the channel JSON from IPTV-org (section 6), including the Punjabi-from-any-country rule.
- Add the link checker with the `working` / `dead` / `unverified` rule.
- Add the overrides file (section 8).
- Add the GitHub Action to run daily and publish to GitHub Pages.
- **Done when:** the JSON file is published, and the owner can open it in a browser.

### Step 6: Channel loading in the app
- Download, cache, and keep the last good copy (section 6.4).
- Show the "New" row and the failure counting.
- Use a bundled small channel file so the app works on the very first run with no internet.
- **Done when:** airplane mode still shows saved channels with a clear "No internet" message.

### Step 7: Home screen, language sections, list view
- Build the home screen and the language buttons (sections 5.2, 5.3).
- Use `RecyclerView`. Load a language only when opened.
- Apply `design.md`.
- **Done when:** scrolling is smooth on the stick with all channels loaded.

### Step 8: Search
- Build the search screen (section 7). Add alias and fuzzy matching.
- Only show the in-app mic button if Step 3 proved it works.
- **Done when:** 10 common channel names (Punjabi, Hindi, English) give the right channel at least 8 times.

### Step 9: Auto-update
- Add the daily release check and the one-button update screen (section 9).
- Test it with a fake newer version.
- **Done when:** the update screen appears and installs after the owner presses OK.

### Step 10: Final speed pass and cleanup
- Measure everything in section 10 again on the stick.
- Fix anything over budget. Remove unused code and libraries.
- Build a release APK and give the install command.
- **Done when:** all numbers pass, or the owner has agreed to exceptions.

---

## 13. Acceptance tests (all must pass)

- Installs and runs on the real stick.
- A new person can use the entire app with only the remote.
- The first thing shown is the last channel; the home screen shows Punjabi first.
- A dead channel never gives a black screen for more than 5 seconds.
- Airplane mode: the app opens, shows saved channels, shows "No internet".
- Search works with voice typing (if the stick supports it) and with the keyboard.
- All speed budget numbers pass.

## 14. Known risks

- Free streams break often. Expect regular upkeep. The daily script and remote file are there to reduce this.
- Some streams may be unofficial restreams. Keep the app private. Do not share the APK.
- Very old sticks may have no voice remote or no speech support. Step 3 finds out early.
- A cheap stick has about 1 GB of memory. Keep everything light.
- The checker runs outside India, so geo-locked channels can look dead. They are marked `unverified`, not deleted.

# Parents Live TV App - Design Specification (`design.md`)

Adapted from the **Nuvio TV** design system specifically for elderly users on an old 1080p Amazon Fire TV Stick.

---

## 1. Design Principles (Elderly-First & Performance-First)

1. **Extreme Legibility at 10 Feet (3 Meters)**: Big text (24sp–36sp minimum), bold typography, and stark contrast so parents never have to squint.
2. **Unmistakable Focus State**: Thick 4dp high-contrast white border and a crisp 1.04x scale. The focused item must be visible instantly with peripheral vision.
3. **Zero GPU/CPU Overhead**: Absolutely **no blur, no drop shadows, no glowing gradients, and no alpha-fade animations**. This preserves precious memory (<150 MB) and CPU cycles on an older Fire TV Stick with 1GB RAM.
4. **Instant Remote Responsiveness**: Snappy 120ms transition on focus changes. Single action per click.

---

## 2. Color Palette

Based on Nuvio's Dark / Neutral tokens, tuned for maximum contrast against TV panels:

| Token | Hex Value | Usage |
|---|---|---|
| **Background** | `#0D0D0D` | Deep canvas background (near-black for high contrast) |
| **Card Surface (Default)** | `#1E1E1E` | Unfocused tile background |
| **Card Surface (Elevated)** | `#262626` | Language selector buttons / list rows |
| **Card Surface (Focused)** | `#333333` | Background of the currently focused tile |
| **Focus Border** | `#FFFFFF` | **4dp thick solid white border** on focused items |
| **Primary Text** | `#FFFFFF` | Channel names, section titles, search text (100% white) |
| **Secondary Text** | `#D4D4D4` | Categories, language tags, hints (high contrast light grey) |
| **Accent / Brand** | `#E53935` (Crimson) / `#FFB300` (Warm Gold) | Selected language indicator & active badges |
| **Status: Working** | `#4CAF50` | Stream operational indicator |
| **Status: Dead / Error** | `#CF6679` | "Channel not working" banner text |
| **Status: Unverified** | `#FFB74D` | Unverified stream badge |
| **Player Overlay** | `#CC000000` | Opaque dark scrim behind info overlays while video plays |

---

## 3. Typography

Fonts: Clean, highly legible sans-serif (`Inter` or system sans-serif font).

| Role | Size | Weight | Line Height | Purpose |
|---|---|---|---|---|
| **Section Header** | `34sp` | Bold (700) | `42sp` | "Punjabi", "Recent", "All Languages" |
| **Channel Name (Tile)** | `26sp` | Bold (700) | `32sp` | Big channel titles on grid cards |
| **Channel Name (List)** | `30sp` | Bold (700) | `36sp` | Extra large title in vertical list view |
| **Language Button** | `24sp` | SemiBold (600) | `30sp` | Language pill buttons ("Punjabi", "Hindi", etc.) |
| **Voice / Search Hint** | `26sp` | Medium (500) | `34sp` | "Hold the mic button and say the channel name" |
| **Subtext / Category** | `20sp` | Medium (500) | `26sp` | Category or stream info (strictly no text below 20sp) |

---

## 4. Shapes & Corner Radii

Derived from Nuvio's `NuvioRadii` tokens:

| Element | Radius | Rationale |
|---|---|---|
| **Channel Tile (Grid)** | `16dp` | Soft rounded modern TV card |
| **Channel Row (List View)** | `12dp` | Compact horizontal row |
| **Language Buttons** | `14dp` | Distinct pill shape |
| **Search Box** | `16dp` | Distinct large input field |
| **Channel Logo Icon** | `10dp` | Smooth logo container |
| **Focus Outline** | Follows element shape (`12dp` - `16dp`) | Perfectly matches card outline |

---

## 5. Spacing & Grid Layout (1080p Canvas: 1920 × 1080 px)

Target resolution is **1920 × 1080** (Fire TV Stick native 1080p):

### Screen Safe Margins (Overscan Protection)
- **Horizontal Screen Padding**: `56dp` (keeps content safely inside TV borders)
- **Vertical Screen Padding**: `36dp`

### Home Screen Grid (Max 4 Tiles per Row)
- **Columns**: 4 tiles per row
- **Tile Dimensions**: Width **410dp**, Height **190dp**
  - Left inside tile: Channel Logo (`100dp × 100dp`, centered vertically, rounded `10dp`, background `#2D2D2D` fallback).
  - Right inside tile: Channel Name (`26sp` bold, up to 2 lines) + Category (`20sp` secondary).
- **Column Gap (Horizontal)**: `20dp`
- **Row Gap (Vertical)**: `24dp`
- **Section Bottom Margin**: `32dp`

### List View Layout (Vertical List)
- **Row Dimensions**: Full content width (`1808dp`), Height **100dp**
- **Row Spacing**: `12dp`
- **Inside Row**:
  - Logo: `72dp × 72dp` (margin start `20dp`)
  - Channel Name: `30sp` Bold (margin start `24dp`)
  - Category Badge: `20sp` (aligned end, margin end `24dp`)

### Language Selector Row
- **Button Height**: `56dp`
- **Button Min Width**: `160dp`
- **Padding**: Horizontal `24dp`, Vertical `12dp`
- **Gap between buttons**: `16dp`

---

## 6. Focus & Interaction States

### Focused State
- **Scale**: `1.04x` (quick subtle enlargement for immediate visual confirmation).
- **Border**: `4dp` solid `#FFFFFF` stroke.
- **Background**: Elevates to `#333333`.
- **Animation**: `120ms` linear or standard ease-out (`FastOutSlowIn`).
- **No Glow / No Drop Shadow**: Clean, sharp, zero rendering lag.

### Unfocused State
- **Scale**: `1.0x`
- **Border**: None (or `1dp` subtle `#262626` boundary).
- **Background**: `#1E1E1E`.

---

## 7. Search Screen Design

- **Search Bar**: Centered near top (`Width: 1200dp`, `Height: 80dp`, Radius: `16dp`, Background: `#222222`).
- **Hint Text**: `26sp`, `#D4D4D4`, "Hold the mic button on your remote and say the channel name."
- **Results Layout**: Same 4-tile grid layout as home screen for instant familiarity.
- **Single Clear Match Overlay**:
  - Big prominent banner: "Playing [Channel Name] in 1.5 seconds... Press Back to cancel."

---

## 8. TV Remote Navigation Model

- **Directional Pad (Up/Down/Left/Right)**: Moves focus between tiles with wrap prevention.
- **OK (Center Select)**:
  - On a channel tile: Immediately switches to that channel full-screen.
  - On a language button: Loads that language's section.
  - While watching full-screen: Opens the channel selection grid.
- **Back Button**:
  - While watching video: Opens the channel grid without stopping playback.
  - In channel grid: If last channel was playing, resumes full-screen playback. If at root, double-press exits.
- **Channel Up / Down (or Up/Down while watching)**:
  - Immediately switches to Next / Previous channel within current section.

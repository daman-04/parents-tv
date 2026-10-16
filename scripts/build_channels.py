#!/usr/bin/env python3
"""
build_channels.py
Builds the optimized channels.json for Parents TV app from IPTV-org open data.
Enforces:
  1. Punjabi channels from ANY country (language code 'pan').
  2. All other channels from India (country == 'IN'), grouped by language/category.
  3. Stream health checking (working / dead / unverified for potential geo-locks).
  4. Merges overrides.json (aliases, pin order, default channel).
  5. Compact, light JSON payload for Fire TV.
"""

import json
import os
import sys
import time
import urllib.request
import urllib.error
from datetime import datetime, timezone
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE_URL = "https://iptv-org.github.io/api"
USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Fire TV) AppleWebKit/537.36 (KHTML, like Gecko)"

def fetch_json(endpoint):
    url = f"{BASE_URL}/{endpoint}"
    print(f"Fetching {url}...")
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"Failed to fetch {url}: {e}")
        return []

def check_stream(url):
    """
    Checks stream reachability.
    Returns: 'working', 'unverified', or 'dead'
    """
    if not url or not url.startswith("http"):
        return "dead"
    
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Range": "bytes=0-1024"
        }
    )
    try:
        with urllib.request.urlopen(req, timeout=4) as resp:
            if resp.status in (200, 206):
                return "working"
            return "unverified"
    except urllib.error.HTTPError as e:
        if e.code in (401, 403, 451):
            # Geo-blocked or protected outside India - KEEP IT, mark unverified!
            return "unverified"
        if e.code in (404, 410):
            return "dead"
        return "unverified"
    except Exception:
        # Timeout or connection error outside India might be geo-route or temporary
        return "unverified"

def build():
    # Load overrides
    overrides_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), "overrides.json")
    if not os.path.exists(overrides_path):
        overrides_path = "overrides.json"
    
    overrides = {}
    if os.path.exists(overrides_path):
        with open(overrides_path, "r", encoding="utf-8") as f:
            overrides = json.load(f)

    pinned_channels = set(overrides.get("pinned_channels", []))
    hidden_channels = set(overrides.get("hidden_channels", []))
    aliases_map = overrides.get("aliases", {})
    channel_order_map = overrides.get("channel_order", {})
    default_channel = overrides.get("default_channel", "DDPunjabi.in")

    # Fetch data
    raw_channels = fetch_json("channels.json")
    raw_feeds = fetch_json("feeds.json")
    raw_streams = fetch_json("streams.json")
    raw_logos = fetch_json("logos.json")

    print(f"Loaded {len(raw_channels)} channels, {len(raw_feeds)} feeds, {len(raw_streams)} streams, {len(raw_logos)} logos.")

    # Build channel id -> languages mapping from feeds
    channel_languages = {}
    for feed in raw_feeds:
        ch_id = feed.get("channel")
        langs = feed.get("languages", [])
        if ch_id and langs:
            channel_languages.setdefault(ch_id, set()).update(langs)

    # Build channel id -> streams mapping
    channel_streams = {}
    for st in raw_streams:
        ch_id = st.get("channel")
        url = st.get("url")
        if ch_id and url and url.startswith("http"):
            channel_streams.setdefault(ch_id, []).append(url)

    # Build channel id -> logo mapping (prefer PNG/JPG)
    channel_logos = {}
    for lg in raw_logos:
        ch_id = lg.get("channel")
        url = lg.get("url")
        fmt = (lg.get("format") or "").upper()
        if ch_id and url:
            if ch_id not in channel_logos or fmt in ("PNG", "JPEG", "JPG"):
                channel_logos[ch_id] = url

    # Filter target channels:
    # 1. Punjabi channels from ANY country
    # 2. All channels from India
    candidate_channels = []
    
    for ch in raw_channels:
        ch_id = ch.get("id")
        if not ch_id or ch_id in hidden_channels:
            continue
        
        # Skip NSFW
        if ch.get("is_nsfw"):
            continue

        langs = channel_languages.get(ch_id, set())
        country = (ch.get("country") or "").upper()
        name = ch.get("name") or ""
        categories = ch.get("categories") or []

        is_punjabi = ("pan" in langs) or ("punjabi" in name.lower()) or ("punjab" in name.lower())
        is_india = (country == "IN")

        if not (is_punjabi or is_india):
            continue

        # Must have at least one stream
        streams = channel_streams.get(ch_id, [])
        if not streams:
            continue

        candidate_channels.append({
            "id": ch_id,
            "name": name,
            "country": country,
            "languages": list(langs),
            "categories": categories,
            "is_punjabi": is_punjabi,
            "streams": streams[:3], # Up to 3 links per PRD
            "logo": channel_logos.get(ch_id)
        })

    print(f"Total candidate channels found: {len(candidate_channels)} (Punjabi + India)")

    # Test candidate streams using thread pool
    print("Verifying streams...")
    verified_streams = {}
    urls_to_test = []
    for c in candidate_channels:
        for u in c["streams"]:
            urls_to_test.append(u)
    
    # Remove duplicates for testing
    unique_urls = list(dict.fromkeys(urls_to_test))
    print(f"Checking {len(unique_urls)} unique stream URLs...")

    with ThreadPoolExecutor(max_workers=16) as executor:
        future_to_url = {executor.submit(check_stream, u): u for u in unique_urls}
        for future in as_completed(future_to_url):
            u = future_to_url[future]
            try:
                verified_streams[u] = future.result()
            except Exception:
                verified_streams[u] = "unverified"

    custom_streams_map = overrides.get("custom_streams", {})

    # Assemble channels with tested streams
    final_channels = []
    for c in candidate_channels:
        if c["id"] in custom_streams_map:
            st_objs = custom_streams_map[c["id"]]
        else:
            st_objs = []
            for u in c["streams"]:
                st_status = verified_streams.get(u, "unverified")
                if st_status != "dead": # Filter out definitively dead links
                    st_objs.append({"url": u, "status": st_status})
            
            # If all links were dead, keep the first one as unverified rather than deleting the channel
            if not st_objs and c["streams"]:
                st_objs.append({"url": c["streams"][0], "status": "unverified"})

            # Sort streams: working first, then unverified
            st_objs.sort(key=lambda s: 0 if s["status"] == "working" else 1)

        aliases = aliases_map.get(c["id"], [])
        if not aliases:
            aliases = [c["name"]]
        elif c["name"] not in aliases:
            aliases.insert(0, c["name"])

        cat_title = c["categories"][0].capitalize() if c["categories"] else "General"

        final_channels.append({
            "id": c["id"],
            "name": c["name"],
            "logo": c["logo"],
            "category": cat_title,
            "languages": c["languages"],
            "is_punjabi": c["is_punjabi"],
            "streams": st_objs,
            "aliases": aliases
        })

    # Group into sections per PRD section 5.2 & 6.3
    # Punjabi first, Sports, Devotional, News, Music, Hindi, English, Others
    sections_map = {
        "punjabi": {"id": "punjabi", "title": "Punjabi", "channels": []},
        "sports": {"id": "sports", "title": "Sports", "channels": []},
        "devotional": {"id": "devotional", "title": "Devotional", "channels": []},
        "news": {"id": "news", "title": "News", "channels": []},
        "music": {"id": "music", "title": "Music", "channels": []},
        "hindi": {"id": "hindi", "title": "Hindi", "channels": []},
        "english": {"id": "english", "title": "English", "channels": []},
        "other": {"id": "other", "title": "Other Channels", "channels": []}
    }

    for ch in final_channels:
        cat_lower = ch["category"].lower()
        name_lower = ch["name"].lower()
        langs = ch["languages"]

        if ch["is_punjabi"]:
            sections_map["punjabi"]["channels"].append(ch)
        elif "sport" in cat_lower or "sport" in name_lower:
            sections_map["sports"]["channels"].append(ch)
        elif "religious" in cat_lower or "devotional" in cat_lower:
            sections_map["devotional"]["channels"].append(ch)
        elif "news" in cat_lower:
            sections_map["news"]["channels"].append(ch)
        elif "music" in cat_lower:
            sections_map["music"]["channels"].append(ch)
        elif "hin" in langs:
            sections_map["hindi"]["channels"].append(ch)
        elif "eng" in langs:
            sections_map["english"]["channels"].append(ch)
        else:
            sections_map["other"]["channels"].append(ch)

    # Sort channels inside sections: pinned first, then by channel_order, then working first
    for sec_id, sec_data in sections_map.items():
        order_list = channel_order_map.get(sec_id, [])

        def channel_sort_key(c):
            cid = c["id"]
            # 1. Pinned channels at the very top
            is_pinned = 0 if cid in pinned_channels else 1
            # 2. Defined manual order
            order_idx = order_list.index(cid) if cid in order_list else 9999
            # 3. Working streams before unverified
            has_working = 0 if any(s["status"] == "working" for s in c["streams"]) else 1
            return (is_pinned, order_idx, has_working, c["name"])

        sec_data["channels"].sort(key=channel_sort_key)

    # Filter out empty sections and order sections by section_order
    section_order = overrides.get("section_order", ["punjabi", "devotional", "news", "music", "hindi", "english", "other"])
    ordered_sections = []
    for s_id in section_order:
        if s_id in sections_map and sections_map[s_id]["channels"]:
            ordered_sections.append(sections_map[s_id])

    for s_id, s_data in sections_map.items():
        if s_id not in section_order and s_data["channels"]:
            ordered_sections.append(s_data)

    output = {
        "generated": datetime.now(timezone.utc).isoformat(),
        "default_channel": default_channel,
        "total_channels": sum(len(s["channels"]) for s in ordered_sections),
        "sections": ordered_sections
    }

    # Write output
    output_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), "channels.json")
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(output, f, indent=2, ensure_ascii=False)
    print(f"Generated {output_path} with {output['total_channels']} channels across {len(ordered_sections)} sections.")

    # Also copy to Android assets for offline fallback
    assets_dir = os.path.join(os.path.dirname(os.path.dirname(__file__)), "app", "src", "main", "assets")
    os.makedirs(assets_dir, exist_ok=True)
    asset_path = os.path.join(assets_dir, "channels.json")
    with open(asset_path, "w", encoding="utf-8") as f:
        json.dump(output, f, indent=2, ensure_ascii=False)
    print(f"Copied bundled fallback channels to {asset_path}")

    # Generate public/ directory for GitHub Pages
    public_dir = os.path.join(os.path.dirname(os.path.dirname(__file__)), "public")
    os.makedirs(public_dir, exist_ok=True)
    with open(os.path.join(public_dir, "channels.json"), "w", encoding="utf-8") as f:
        json.dump(output, f, indent=2, ensure_ascii=False)

    with open(os.path.join(public_dir, "overrides.json"), "w", encoding="utf-8") as f:
        json.dump(overrides, f, indent=2, ensure_ascii=False)

    sections_html = ""
    for s in ordered_sections:
        ch_cards = ""
        for ch in s["channels"][:12]:
            logo_img = f'<img src="{ch["logo"]}" onerror="this.style.display=\'none\'" />' if ch.get("logo") else ''
            is_working = any(st.get("status") == "working" for st in ch.get("streams", []))
            status_cls = "status-working" if is_working else "status-unverified"
            status_text = "Working" if is_working else "Unverified / Geo"
            ch_cards += f'''
            <div class="channel">
                {logo_img}
                <div>
                    <div class="channel-name">{ch["name"]}</div>
                    <div class="{status_cls}">● {status_text} ({len(ch["streams"])} streams)</div>
                </div>
            </div>
            '''
        more_text = f'<p style="color: #888; font-size: 14px; margin-top: 12px;">...and {len(s["channels"]) - 12} more in channels.json</p>' if len(s["channels"]) > 12 else ''
        sections_html += f'''
        <div class="card">
            <h3 style="margin: 0 0 12px 0; color: #FFF;">{s["title"]} ({len(s["channels"])} channels)</h3>
            <div class="grid">{ch_cards}</div>
            {more_text}
        </div>
        '''

    html_content = f'''<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Parents TV - Channel Data Feed</title>
    <style>
        body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0D0D0D; color: #FFFFFF; padding: 32px; margin: 0; }}
        h1 {{ color: #FFFFFF; margin-bottom: 8px; }}
        .meta {{ color: #B3B3B3; margin-bottom: 24px; font-size: 16px; }}
        .badge {{ background: #E53935; color: white; padding: 4px 10px; border-radius: 6px; font-weight: bold; font-size: 14px; display: inline-block; }}
        .links a {{ color: #42A5F5; text-decoration: none; margin-right: 20px; font-weight: 500; font-size: 18px; }}
        .links a:hover {{ text-decoration: underline; }}
        .card {{ background: #1E1E1E; border-radius: 12px; padding: 20px; margin-bottom: 20px; border: 1px solid #333; }}
        .grid {{ display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 16px; margin-top: 16px; }}
        .channel {{ background: #262626; padding: 14px; border-radius: 8px; display: flex; align-items: center; gap: 12px; }}
        .channel img {{ width: 48px; height: 48px; object-fit: contain; border-radius: 6px; background: #333; }}
        .channel-name {{ font-weight: 600; font-size: 16px; }}
        .status-working {{ color: #4CAF50; font-size: 13px; font-weight: 500; }}
        .status-unverified {{ color: #FFB74D; font-size: 13px; font-weight: 500; }}
    </style>
</head>
<body>
    <h1>Parents TV Data Feed</h1>
    <div class="meta">
        Generated: {output['generated']} | Total Channels: <strong>{output['total_channels']}</strong> | Default: <span class="badge">{output['default_channel']}</span>
    </div>
    <div class="links">
        <a href="channels.json" target="_blank">📥 View channels.json</a>
        <a href="overrides.json" target="_blank">⚙️ View overrides.json</a>
    </div>
    <h2 style="margin-top: 32px;">Channel Sections</h2>
    {sections_html}
</body>
</html>'''

    with open(os.path.join(public_dir, "index.html"), "w", encoding="utf-8") as f:
        f.write(html_content)
    print(f"Generated web dashboard at {os.path.join(public_dir, 'index.html')}")

if __name__ == "__main__":
    build()

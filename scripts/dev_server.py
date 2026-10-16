#!/usr/bin/env python3
"""
dev_server.py
Enhanced local development server for TV web simulator.
Serves static files from 'public/' and provides a transparent HLS CORS proxy at /proxy?url=...
Allows testing streams in the desktop browser simulator that lack CORS headers or reject foreign Origins.
"""

import http.server
import socketserver
import urllib.request
import urllib.parse
import os
import sys

PORT = 8080
PUBLIC_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "public")
USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Fire TV) AppleWebKit/537.36 (KHTML, like Gecko)"

class TVDevHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=PUBLIC_DIR, **kwargs)

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS, HEAD")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.end_headers()

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/proxy":
            query = urllib.parse.parse_qs(parsed.query)
            target_url = query.get("url", [None])[0]
            if not target_url:
                self.send_error(400, "Missing 'url' parameter")
                return

            try:
                headers = {
                    "User-Agent": USER_AGENT,
                    "Accept": "*/*"
                }
                # Forward range header if present
                if "Range" in self.headers:
                    headers["Range"] = self.headers["Range"]

                req = urllib.request.Request(target_url, headers=headers)
                with urllib.request.urlopen(req, timeout=10) as resp:
                    content_type = resp.headers.get("Content-Type", "application/octet-stream")
                    data = resp.read()

                    # If m3u8 playlist, rewrite relative URLs so chunks proxy properly
                    if "mpegurl" in content_type or target_url.endswith(".m3u8") or data.startswith(b"#EXTM3U"):
                        content_type = "application/vnd.apple.mpegurl"
                        text = data.decode("utf-8", errors="ignore")
                        lines = text.splitlines()
                        rewritten = []
                        base_url = target_url.rsplit("/", 1)[0] + "/"
                        for line in lines:
                            line_s = line.strip()
                            if line_s and not line_s.startswith("#"):
                                # Resolve absolute URL for stream chunk or variant
                                if line_s.startswith("http://") or line_s.startswith("https://"):
                                    abs_url = line_s
                                else:
                                    abs_url = urllib.parse.urljoin(base_url, line_s)
                                rewritten.append(f"/proxy?url={urllib.parse.quote(abs_url, safe='')}")
                            else:
                                rewritten.append(line)
                        data = "\n".join(rewritten).encode("utf-8")

                    self.send_response(resp.status)
                    self.send_header("Content-Type", content_type)
                    self.send_header("Content-Length", str(len(data)))
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.send_header("Access-Control-Allow-Headers", "*")
                    self.end_headers()
                    self.wfile.write(data)
            except Exception as e:
                self.send_response(502)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(f"Proxy Error: {e}".encode("utf-8"))
        else:
            super().do_GET()

def run():
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("127.0.0.1", PORT), TVDevHandler) as httpd:
        print(f"TV Simulator dev server running on http://127.0.0.1:{PORT}/ (with HLS CORS proxy enabled)")
        httpd.serve_forever()

if __name__ == "__main__":
    run()

"""Serves the launcher UI from the working tree and proxies data routes to the Thor.

Usage: python3 dev/devserver.py [port]
Requires `adb forward tcp:8800 tcp:<device server port>` and dev/.token (see dev/connect.sh).
"""
import http.client
import http.server
import os
import socketserver
import sys
from urllib.parse import urlsplit

ROOT = os.path.dirname(os.path.abspath(__file__))
WWW = os.path.join(ROOT, "..", "app", "assets", "www")
DEVICE = ("127.0.0.1", 8800)
TOKEN = open(os.path.join(ROOT, ".token")).read().strip()

TYPES = {".html": "text/html; charset=utf-8", ".js": "application/javascript", ".css": "text/css",
         ".svg": "image/svg+xml", ".png": "image/png", ".jpg": "image/jpeg", ".webp": "image/webp",
         ".json": "application/json", ".woff2": "font/woff2", ".mp3": "audio/mpeg", ".ogg": "audio/ogg"}


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        self.handle_any()

    def do_POST(self):
        self.handle_any()

    def handle_any(self):
        path = urlsplit(self.path).path
        if path.startswith("/app/"):
            return self.serve_file(path[5:])
        return self.proxy()

    def serve_file(self, rel):
        full = os.path.normpath(os.path.join(WWW, rel))
        if not full.startswith(os.path.normpath(WWW)) or not os.path.isfile(full):
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        data = open(full, "rb").read()
        self.send_response(200)
        self.send_header("Content-Type", TYPES.get(os.path.splitext(full)[1], "application/octet-stream"))
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(data)

    def proxy(self):
        body = None
        n = int(self.headers.get("Content-Length") or 0)
        if n:
            body = self.rfile.read(n)
        headers = {"X-Token": TOKEN}
        if self.headers.get("Range"):
            headers["Range"] = self.headers["Range"]
        if body is not None:
            headers["Content-Type"] = self.headers.get("Content-Type", "application/json")
        conn = http.client.HTTPConnection(*DEVICE, timeout=60)
        try:
            conn.request(self.command, self.path, body=body, headers=headers)
            resp = conn.getresponse()
            data = resp.read()
        except Exception as e:  # device unreachable
            data = str(e).encode()
            self.send_response(502)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        finally:
            conn.close()
        self.send_response(resp.status)
        for k in ("Content-Type", "Content-Range", "Accept-Ranges", "Cache-Control"):
            if resp.getheader(k):
                self.send_header(k, resp.getheader(k))
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    print(f"dev server on http://127.0.0.1:{port}/app/index.html", flush=True)
    Server(("127.0.0.1", port), Handler).serve_forever()

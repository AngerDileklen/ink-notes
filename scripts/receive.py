#!/usr/bin/env python3
"""
Receives data from the Ink Probe app on the tablet:
  POST /            text report              -> reports/report-<time>.txt
  POST /file?name=X a file (system jar, apk) -> reports/files/X
  POST /done        tells this script to exit
Stays up for 30 minutes at most. Only accepts files named with letters, digits, dot, dash, underscore.
"""
import http.server, socket, sys, time, os, re, urllib.parse

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "reports")
FILES = os.path.join(OUT, "files")
os.makedirs(FILES, exist_ok=True)
MAX_TEXT = 2_000_000
MAX_FILE = 300_000_000


class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        url = urllib.parse.urlparse(self.path)
        n = int(self.headers.get("Content-Length", 0))
        if url.path == "/done":
            self._ok(b"bye\n"); self.server.done = True; print("done signal received", flush=True); return
        if url.path == "/file":
            name = urllib.parse.parse_qs(url.query).get("name", [""])[0]
            if not re.fullmatch(r"[A-Za-z0-9._-]{1,80}", name) or n <= 0 or n > MAX_FILE:
                self.send_response(400); self.end_headers(); return
            dest = os.path.join(FILES, name)
            left = n
            with open(dest, "wb") as f:
                while left > 0:
                    chunk = self.rfile.read(min(1 << 16, left))
                    if not chunk: break
                    f.write(chunk); left -= len(chunk)
            if left:
                os.remove(dest); self.send_response(400); self.end_headers(); return
            self._ok(b"saved\n"); print(f"saved file {name} ({n} bytes)", flush=True); return
        if n <= 0 or n > MAX_TEXT:
            self.send_response(413); self.end_headers(); return
        body = self.rfile.read(n)
        path = os.path.join(OUT, time.strftime("report-%Y%m%d-%H%M%S.txt"))
        with open(path, "wb") as f:
            f.write(body)
        self._ok(b"saved\n"); print("saved report", path, flush=True)

    def _ok(self, b):
        self.send_response(200); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)

    def log_message(self, *a):
        pass


def lan_ip():
    # UDP connect() sends no packet, so it works even when the internet is unreachable.
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("192.168.1.1", 80)); ip = s.getsockname()[0]; s.close()
        if ip and not ip.startswith("127."): return ip
    except OSError:
        pass
    import subprocess
    for iface in ("en0", "en1"):
        out = subprocess.run(["ipconfig", "getifaddr", iface], capture_output=True, text=True).stdout.strip()
        if out: return out
    return "MAC-IP"


ip = lan_ip()
srv = http.server.ThreadingHTTPServer(("0.0.0.0", 8765), H)
srv.done = False
srv.timeout = 5
print(f"Type this into the app's address box:  http://{ip}:8765/", flush=True)
end = time.time() + 1800
while not srv.done and time.time() < end:
    srv.handle_request()
sys.exit(0 if srv.done else 1)

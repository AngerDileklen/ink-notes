#!/usr/bin/env python3
"""Waits for ONE report POSTed by the Ink Probe app, saves it, then exits. Stops after 10 minutes."""
import http.server, socket, sys, time, os
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "reports")
os.makedirs(OUT, exist_ok=True)
MAX = 2_000_000

class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        if n <= 0 or n > MAX:
            self.send_response(413); self.end_headers(); return
        body = self.rfile.read(n)
        path = os.path.join(OUT, time.strftime("report-%Y%m%d-%H%M%S.txt"))
        with open(path, "wb") as f: f.write(body)
        self.send_response(200); self.end_headers(); self.wfile.write(b"saved\n")
        print("saved", path, flush=True)
        self.server.done = True
    def log_message(self, *a): pass

s = socket.socket(); s.connect(("8.8.8.8", 80)); ip = s.getsockname()[0]; s.close()
srv = http.server.HTTPServer(("0.0.0.0", 8765), H); srv.done = False; srv.timeout = 5
print(f"Type this into the app's address box:  http://{ip}:8765/", flush=True)
end = time.time() + 600
while not srv.done and time.time() < end: srv.handle_request()
sys.exit(0 if srv.done else 1)

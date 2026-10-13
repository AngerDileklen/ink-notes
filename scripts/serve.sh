#!/bin/bash
# Serves dist/ on the local network so the tablet's browser can download the APK.
cd "$(dirname "$0")/../dist" || exit 1
IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null)
echo "On the tablet's browser open:  http://$IP:8000/ink-notes.apk"
echo "Stop with Ctrl-C when the download is done."
python3 -m http.server 8000 --bind 0.0.0.0

# eink-notes

Handwriting + PDF notes for the Penstar eNote (Android 11, pen-only e-ink). Step one is the **Ink Probe**:
a test bench that records how this tablet's pen, eraser end, side button and nine hardware keys behave,
and which way of drawing is fastest.

Toolchain lives on the external SSD ("ORICO SSD/android-dev"). Plug it in before building.

    bash scripts/build.sh      # builds dist/ink-probe.apk
    bash scripts/serve.sh      # serve the APK to the tablet's browser
    python3 scripts/receive.py # receive the report the app sends back

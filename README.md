# Ink Notes (eink-notes)

A Notability-style handwriting and PDF notes app for the Penstar eNote / Hanvon N10 (Android 11, pen-only e-ink).
Plain Android Views, no animation, built for slow-refresh panels. The device test bench ("Probe") is still inside, under Settings > Device tools.

## Using it

- **Zoom shortcut:** tap 3 times in a row with the pen at one spot to zoom in there; tap 3 times again to zoom out (Settings: 3 or 4 taps, 2x/3x/4x, "the spot I tap" or fixed squares). While zoomed, lift the pen near the right edge and the page slides over; the bar at the bottom pans and zooms.
- **Paper button** (top bar): blank, ruled, squares, dots, narrow, college, Cornell, handwriting practice, music, two/three columns, daily/weekly/monthly planners, engineering, hexagonal and isometric; spacing in mm; page size A4 / Letter / Legal / tablet. New pages are A4.
- **Tools:** pen (pressure) and calligraphy, marker (snaps to the text of PDF pages), eraser (whole or partial), lasso, shapes (hold the pen still to snap a drawn shape), text, tape, ruler, hand. Width and grey quick picks sit beside the active tool; long-press one to change it.
- **PDF:** import into a notebook, find text, outline, export with ink. **Audio:** record while writing; strokes link to the sound. **AI** (your own key in Settings > AI service): ask about a page, summaries, flashcards and quiz, transcripts.
- **Books tab:** read EPUB, PDF, FB2, TXT, HTML, CBZ comics and older Kindle (MOBI) files. Add books from the file picker or let the app list every ebook on the tablet. Tap a word for the offline English/Turkish dictionary, drag to highlight, AI for questions, summaries, translation and flashcards.
- **Library:** cards, folders, favorites, recent, Recently deleted (30 days), backup/restore as `.inkz`.

## Building

Toolchain lives on the external SSD ("ORICO SSD/android-dev"). Plug it in before building.

    source scripts/env.sh
    bash scripts/build.sh            # debug APK in app/build/outputs/apk/debug/
    gradle :app:testDebugUnitTest    # unit tests (notebook format, shapes, AI parsing)
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Debug builds accept commands over USB for testing, e.g.
`adb shell "am broadcast -a app.einknotes.DEBUG --es cmd tap_seq --ef x 500 --ef y 700 --ei n 3"`
(see `registerDebugControl` in `NotesActivity.kt`; the library has its own receiver, `app.einknotes.LIBRARY`).
Test only in a notebook called `scratch` (`am start -n app.einknotes/.notes.NotesActivity --es notebook scratch`).

The offline dictionary is built from free data sets (Princeton WordNet 3.0 and WikDict) with `python3 scripts/build_dict.py <folder>` into `app/src/main/assets/dict.db` (not kept in git; see the script header for the sources and licences).

`ROADMAP.md` lists what is done and what is next.

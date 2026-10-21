# Hanvon native ink integration (experimental)

The native path uses the independently packaged Hanvon Ink SDK. Ink Notes owns its
notebook format, tools, undo transactions and rendering policy. The SDK owns vendor
discovery, the native drawing area, coordinate conversion, brush configuration,
reported-width samples and preview handoff. No vendor binaries are bundled.

## Enable and use

On supported firmware, open Settings and choose **Use native pressure pen**. This
selects black pen ink and disables smoothing, shape snapping and the ruler, whose
output cannot match the native preview. The fixed-width pen uses the native pencil
style instead. Other brushes and unsupported configurations retain Android drawing.
The native status in Settings explains the current path.

Bottom and middle button defaults are temporary erase and select. Hold middle to
lasso, then hold it again while dragging inside the selection box to move the
selection. Selection actions remain available when the toolbar's selected tool is
Pen. The rear eraser also erases. The tested M10 top button supplies finger-type
events from the stylus source and temporarily pans; actual touchscreen contacts
remain ignored unless **Finger input pans** is enabled. Triple-tap **with the pen**
to zoom; panning a page already fitted to the screen cannot move it past its edges.

Firmware API availability is capability-based, not gated on the M10 model name.
Only the M10 has physical evidence for this integration so far. Source-aware input
classification does not establish identical physical button assignments on every
Hanvon device or firmware configuration.

## Retention, editing and compatibility

Android input first retains a provisional editable stroke. After the quiet period,
the SDK conservatively associates native samples with the input transaction. An
accepted result replaces that same stroke object's geometry and actual width
diameters, preserving undo identity. Missing or ambiguous packets retain Android
ink and report fallback. Native packet timing is not per-point sampling time.

Width-bearing strokes are stored as version-8 item type 6. They carry actual
diameters, not fabricated pressure. Copies, transformations, erasing, rendering,
PDF export and undo operate on that geometry. Documents containing only older
items still save as version 7. Before an existing version-1–7 file first becomes
version 8, its original bytes are preserved as `<notebook>.inkn.pre-v8.bak`.
Older app builds cannot read version 8. Use exported PDF for visual interchange;
restore the pre-v8 copy only if intentionally discarding subsequent edits.

The session suspends before unsupported tools, edits, viewport changes and overlays.
Both consumers use the SDK RetainedFrame helper. An edited retained frame is acknowledged before old native preview is cleared and
drawing can resume. Frame callbacks registered inside `onDraw` explicitly schedule
another frame, so an idle display cannot strand native preview. Ignored palms do
not invalidate a valid stroke handoff. Lifecycle loss closes the session; multiwindow
uses Android drawing. Native and legacy waveform operations have separate ownership.

## Independent build and test installation

Build with JDK 17 and Android SDK 35; Android minimum 28 and target 29 are retained.
The checked-in Maven artifact includes the AAR, sources and metadata; a sibling SDK
source checkout is unnecessary. See [vendor instructions](../vendor/README.md).

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleSandbox
```

`app/build/outputs/apk/sandbox/app-sandbox.apk` installs as **Ink Notes Native Test**,
package `app.einknotes.nativeink`, with its own private data. It can coexist with
the original app whose signing key is unavailable. Never uninstall the original
to bypass a certificate mismatch. Default/debug/release package identity remains
`app.einknotes` for the eventual upstream fork. Release APKs require the maintainer's
signing configuration; the local sandbox is debug-signed.

The emulator instrumentation refuses native-capable hardware and uses a disposable
fixture notebook. It checks idle frame completion, complete temporary-tool gestures,
undo/redo, ignored touch, zoomed pan, width transformation, bitmap/PDF rendering and
v8 persistence. These checks cannot establish physical e-ink behavior.

## Validation status

October 21, 2026: ordinary native editor writing passed the first M10 observation.
The first editing trial exposed stale native preview despite a successful document
erase. An idle-frame callback regression reproduced the failure on the emulator;
the corrected build passed it. On-device retest with SDK alpha06 confirmed that
both the bottom button and rear eraser now visibly erase the retained stroke.
Middle lasso/move, pen triple-tap zoom, top pan and resumed writing then passed physical retest. The rc1 emulator fixture also passed PDF import, annotation, export, rendered-pixel verification and notebook reopen. Physical PDF annotation and final candidate lifecycle acceptance remain pending.
This document does not claim a final 0.2 release or numerical pen-to-ink latency.

# Ink Notes roadmap

Goal: a Notability-class note-taking app for the Hanvon N10 e-ink tablet (pen-only, Android 11).
Source of truth for *what Notability does*: `clipbrain-ios/research/notability-teardown/REPORT.md`.
Rule: e-ink first. No animation, no colour dependence, few and small screen updates, everything reachable with the pen.

Status: DONE / NEXT / LATER / SKIP (with reason)

## Writing and drawing
| Notability feature | Plan here | Status |
|---|---|---|
| Pen, pressure width, smoothing | Quad-curve smoothing, pressure curve tuned to the Hanvon digitizer | DONE |
| Eraser: stroke eraser, pen eraser end | Path-aware stroke erase, eraser end + side button | DONE |
| Undo / redo | Command stack | DONE |
| Highlighter | Grey multiply stroke, wide flat nib; drawn as one path so overlaps never darken | DONE |
| Calligraphy nib (angle, flatness) | Width follows direction against the nib angle | DONE |
| Pencil (grain) | Fixed grey texture only; real grain ghosts on e-ink | LATER |
| Ink colours / brush packs (glitter, rainbow) | Greys only; colour brushes need a colour panel | SKIP |
| Partial eraser (split strokes) | Segment subtraction | LATER |
| Shape recognition (hold to snap) | Own multi-fitter detector from the teardown (line, ellipse, polygon, arrow, curve) | NEXT |
| Shape tool + editable shapes | Shape items with handles | NEXT |
| Ruler / straight edge | Edge overlay | LATER |
| Stroke stabilization setting | Per-tool smoothing strength | LATER |

## Objects
| Feature | Plan | Status |
|---|---|---|
| Selection (lasso, tap), move, resize, delete | Item model + transform commands, all undoable | DONE |
| Copy / cut / paste / duplicate, across pages | In-app clipboard of items | DONE |
| Text boxes (typed) | On-screen keyboard, simple fonts | LATER |
| Images | Pick from files, move/resize | LATER |
| Tape (hide text for study) | Grey mask items | LATER |
| Stickers | Small bundled set | SKIP (low value on e-ink) |

## Pages, documents, library
| Feature | Plan | Status |
|---|---|---|
| PDF import | PdfRenderer, per-page backgrounds | DONE |
| PDF export with ink | Flatten pages + strokes into a new PDF | NEXT |
| Paper templates (blank, ruled, grid, dots) | Drawn in code | DONE |
| More templates (Cornell, planner, music staff...) | Drawn in code | LATER |
| Page manager (thumbnails, reorder, delete, duplicate) | Thumbnail grid | NEXT |
| Library of notebooks, subjects/folders | Home screen, list + thumbnails | NEXT |
| Bookmarks, outline, jump to page | Page list | LATER |
| Backup / restore / share | Export a notebook bundle | LATER |
| Cloud sync, account, subscriptions | Not wanted | SKIP |

## Recognition and audio
| Feature | Plan | Status |
|---|---|---|
| Handwriting search | Needs a recogniser: investigate Hanvon's built-in MyScript service on the device | LATER |
| Handwriting to text | Same | LATER |
| Math recognition | Same | SKIP for now |
| OCR of images and scans | Not now | SKIP |
| Audio recording linked to strokes | MediaRecorder + per-stroke timestamps; check the device has a mic | LATER |
| AI study features (Learn, transcription) | Server driven in Notability, needs an account | SKIP |

## Tablet integration
| Feature | Plan | Status |
|---|---|---|
| Fast ink (AutoDU while writing, GC16 after) | EinkManager.setMode | DONE |
| Hardware buttons (zoom, scroll, pages) | Volume, page, zoom, arrow keys from the tablet's custom-key settings | DONE |
| Pen side button | Tool type 6 -> configurable tool | DONE |
| Zoom window (write in a magnified strip) | Strip view with auto-advance | LATER |
| Lock screen / sleep behaviour | Respect Hanvon power handling | LATER |

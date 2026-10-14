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
| Partial eraser (split strokes) | Segment subtraction; erase options: whole stroke or partial, three sizes; undoable | DONE |
| Shape recognition (hold to snap) | Own multi-fitter detector (line, arrow, ellipse/circle, rectangle/square, triangle, polygon); snap is a proposal, moving the pen cancels it | DONE |
| Shape tool + editable shapes | Drag out line, arrow, rectangle, ellipse, triangle; shapes select, move and resize like any item | DONE |
| Ruler / straight edge | Overlay with tick marks; drag the middle to move, an end to turn (15 degree steps); strokes that start beside the edge follow it | DONE |
| Stroke stabilization setting | Pen option Smooth: off / light / strong | DONE |

## Objects
| Feature | Plan | Status |
|---|---|---|
| Selection (lasso, tap), move, resize, delete | Item model + transform commands, all undoable | DONE |
| Copy / cut / paste / duplicate, across pages | In-app clipboard of items | DONE |
| Text boxes (typed) | Tap with the Text tool, type in a dialog, move/resize like any item, Edit button; size, ink, bold | DONE |
| Images | Pick from files, stored once by hash, shown in grey, move/resize, in PDF export | DONE |
| Tape (hide text for study) | Hatched grey patch; Tape tool drags one out, tap lifts or replaces it; undoable | DONE |
| Stickers | Small bundled set | SKIP (low value on e-ink) |

## Pages, documents, library
| Feature | Plan | Status |
|---|---|---|
| PDF import | PdfRenderer, per-page backgrounds | DONE |
| PDF export with ink | New PDF: original page as a 150 dpi picture, ink as vector | DONE |
| Paper templates (blank, ruled, grid, dots) | Drawn in code | DONE |
| More templates (narrow ruled, Cornell, music staff) | Drawn in code | DONE (planner LATER) |
| Page manager (thumbnails, reorder, delete, duplicate, blank page) | Thumbnail grid, tap the page counter | DONE |
| Library of notebooks | Home screen: thumbnails, new, rename, delete, PDF into its own notebook, folders (filter + Folder... per notebook) | DONE |
| Bookmarks, jump to page | Star in the Pages grid, next bookmark, go to number | DONE (outline LATER) |
| Backup / restore / share | Back up writes Download/Ink Notes/<title>.inkz (notebook + PDFs + pictures + audio); Restore backup... on the library | DONE |
| Cloud sync, account, subscriptions | Not wanted | SKIP |

## Recognition and audio
| Feature | Plan | Status |
|---|---|---|
| Handwriting search | (User declined the 65 MB model download on 2026-10-14; plan was TrOCR small handwritten, ONNX Runtime, line segmentation from strokes.) Hanvon's MyScript service is NOT usable (no binder; reads strokes from hvNote's own content provider; licence bound to their app). Needs an open on-device model bundled with the app (e.g. ONNX Runtime + a recogniser) | LATER (research) |
| Handwriting to text | Same | LATER |
| Math recognition | Same | SKIP for now |
| OCR of images and scans | Not now | SKIP |
| Audio recording linked to strokes | Rec button, items remember recording + time, foreground service; More > Audio recordings: play, -10/+10 s, Replay (items appear as the audio reaches them), tap a stroke with the Hand tool to jump there | DONE |
| AI study features (Learn, transcription) | Server driven in Notability, needs an account | SKIP |

## Tablet integration
| Feature | Plan | Status |
|---|---|---|
| Fast ink (AutoDU while writing, GC16 after) | EinkManager.setMode | DONE |
| Hardware buttons (zoom, scroll, pages) | Volume, page, zoom, arrow keys from the tablet's custom-key settings | DONE |
| Pen side button | Tool type 6 -> configurable tool | DONE |
| Zoom window (write in a magnified strip) | Strip view with auto-advance | LATER |
| Lock screen / sleep behaviour | Respect Hanvon power handling | LATER |

## Rework from Notability round 2 (2026-10-14)
Source: `clipbrain-ios/research/notability-teardown/REPORT-2-UX.md` (screens, tool numbers, templates, gap list).
| Phase | Scope | Status |
|---|---|---|
| 1 | Data safety: never save over an unreadable notebook, synced atomic save, side-effect-free library reads, single editor per notebook, receivers/handlers cleaned up | DONE (v0.11, tested on the tablet: damaged notebook shows a restore screen and is never overwritten) |
| 2 | E-ink UI kit: icon toolbar with width/grey wells, context bar, flat sheets instead of dialogs, paged Settings, library (favorites, recent, sort, Recently Deleted) | MOSTLY DONE (v0.12: icon toolbar + wells, sheets, Settings, paged library with Recently deleted; Pages panel still uses old dialogs) |
| 3 | Long column of pages that jumps a screen at a time; page X of N; Pointer tool | |
| 4 | Zoom window, cosine smoothing + pressure limiter, Notability widths and pen styles, pencil, eraser priority + auto-deselect, box select + arrange/flip, playback dimming and speeds, 500 ms snap | |
| 5 | PDF text via pdfium: find with hit highlights, highlight text lines, copy text, outline | |
| 6 | Paper sizes (Letter, A4, A5...), about 20 templates drawn in code with a picker, culling/caching, refresh policy | |
| 7 | AI with the user's key (OpenRouter, Gemini, OpenAI, Anthropic): summary, flashcards + quiz, audio transcript, ask about this page/PDF | |

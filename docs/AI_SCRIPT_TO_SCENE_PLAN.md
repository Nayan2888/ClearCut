# AI Script-to-Scene Workflow — Implementation Plan

Status: M0 initial remote audit started. This document is not a claim that implementation or tests have passed.

## Initial architecture evidence
- Android application, Kotlin + Jetpack Compose, single-activity navigation in `app/src/main/java/com/novacut/editor/MainActivity.kt`.
- Application ID and namespace: `com.novacut.editor`; minimum SDK 26, compile/target SDK 37; inspected build metadata reports version 3.82.0.
- README documents MVVM, Hilt, StateFlow, Room, DataStore and Media3.
- README describes the AI package as `com.novacut.editor.ai` for captions, scene detection, stabilization and auto-edit. Exact source classes and injection points still need source-tree inspection.
- Existing project interchange includes `.clearcut-edl.json`, CMX 3600 EDL, FCPXML and OTIO. Integration must reuse the current timeline/project representation and adapters.

## Milestone 0 — Complete source audit and baseline
- Inspect AI package, project/clip/track models, editor command/session APIs, Room persistence, media URI handling, and all interchange exporters/importers.
- Record current commit and CI status; run baseline build, unit tests and lint before feature edits.
- No existing features or public schemas should be changed without migration and regression tests.

## Milestone 1 — Typed script/scene domain
- Add stable scene IDs, script segment/narration, duration estimate, visual direction, prompt, negative prompt, attached media URI, generation settings, and workflow state.
- Keep scene ordering explicit. Support Hindi Unicode and manual scene editing without an AI provider.
- Unit-test empty/large scripts, punctuation, Unicode, stable IDs, ordering, and serialization.

## Milestone 2 — Provider abstraction
- Define a provider-neutral interface with explicit local and cloud implementations and configurable selection.
- Only advertise local/offline inference when a supported model/runtime is installed and a real inference smoke test succeeds.
- Never embed API secrets in source, logs, exports or crash reports. Bound requests/responses; handle timeout, cancellation, invalid structured output, offline state and rate limits.
- Validate AI output against a strict schema and provide a manual fallback.

## Milestone 3 — Compose workflow
- Script paste/import, scene generation, scene reorder, edit/regenerate prompt, and per-scene media picker next to the prompt.
- Show attachment name, preview, permission/readability status, replace/remove actions, and retry/error state.
- Preserve scene attachments when prompts are regenerated; never delete user media silently.
- Add UI/accessibility tests.

## Milestone 4 — Timeline and external export
- User explicitly reviews and approves before adding scene media and narration to the existing editor timeline.
- Use the existing editor command/session API so undo/redo, autosave and project persistence remain correct.
- Export through supported existing formats (portable edit-decision JSON, EDL, FCPXML and OTIO as supported); keep media relinking and fidelity warnings.
- Storyboard-only assets must not block normal video export. Missing media must be disclosed, not silently substituted.

## Milestone 5 — Verification
- Run unit tests, persistence/serialization tests, UI tests, lint, debug/QA build, and the official OTIO adapter round-trip gate where available.
- Compare CI with the baseline and record exact command outcomes. Do not mark a milestone PASS without evidence.

## Acceptance criteria
- Manual workflow remains usable when AI is unavailable.
- Local/cloud AI is configurable and failures are actionable.
- Every scene has its own editable prompt and independent media attachment.
- User approval is required before timeline mutation.
- Existing project persistence, timeline edit/undo, normal export, captions and AI tools remain regression-tested.

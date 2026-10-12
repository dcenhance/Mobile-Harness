# DCE fork of Mobile Harness

Fork of [`techjarves/Mobile-Harness`](https://github.com/techjarves/Mobile-Harness) (MIT, copyright
2026 Mobile Harness Contributors) maintained by DCE. Upstream revision this work started from:
`96b1da08d03af4f2d63970a3794c7fda068948c4` ("release: prepare Mobile Harness v1.0.7").

The fork is additive: upstream architecture, the application id `com.jarves.mh`, the license and the
upstream attribution stay untouched, so an installed copy remains updatable.

## 1. What upstream already has (verified in source, not from its README)

| Area | Where |
| --- | --- |
| Private Ubuntu/PRoot runtime, checksum-pinned bundles, resume, repair | `runtime/RuntimeInstaller.kt`, `RootfsRepair.kt`, `RuntimeCompatibility.kt` |
| Agent registry with capability flags instead of UI conditionals | `runtime/AgentDriver.kt` |
| Three coding agents: Claude Code, DeepSeek Harness, Antigravity CLI | `runtime/ClaudeRuntimeBridge.kt`, `DshRuntimeBridge.kt`, `AntigravityRuntimeBridge.kt` |
| Eight providers incl. OpenAI-compatible and Responses protocols | `model/Models.kt` (`ProviderKind`, `ProviderProtocol`) |
| Encrypted API-key vault | `data/ApiKeyVault.kt` |
| Foreground services for setup and execution, wake-lock handling | `runtime/RuntimeSetupService.kt`, `RuntimeExecutionService.kt` |
| Checkpoints, rendered diffs, per-file undo/keep | `runtime/WorkspaceCheckpoints.kt`, `ChangesTab` |
| Local web preview with project containment | `PreviewTab` |
| Android build dashboard: build/run, logcat, health checks, APK share/save, uninstall | `AndroidDashboardScreen`, `runtime/AndroidBuildSupport.kt` |
| GitHub sign-in, repository clone, project import/export | `network/GitHubClient.kt`, `MainViewModel.cloneGitHubRepository` |
| In-file find, editor undo/redo, line gutter | `FileViewerScreen` |
| Six complete UI languages (en, ar, es, hi, ru, zh) | `res/values*` |

## 2. What is missing, measured against DcentIDE

Evidence is source-level: the greps below return nothing in `app/src/main/java/com/jarves/mh`.

| Missing | Evidence | Status |
| --- | --- | --- |
| Find inside a conversation (literal, per occurrence, stepping) | no search over `ChatMessage`; the only find is in-file (`FileViewerScreen`) | **implemented here** |
| Folding of long replies with a stated line count | transcript items render the whole reply | **implemented here** |
| Per-message actions: copy, quote into the composer | chat messages had no per-message menu; text selection was the only way to take text along | **implemented here** |
| Project-wide text search (`total / listed / held back`, stable file-set id) | `grep -rnE 'readLines\|lineSequence\|useLines'` shows only installer/gradle property parsing, no content search | **implemented here** (§3.4) |
| The attached picture is visible before sending | attachment chips showed a name and size only; `grep -rn 'BitmapFactory\|ImageBitmap\|asImageBitmap\|inSampleSize'` → 0 | **implemented here** (§3.6) |
| Screen capture and image frames handed to the model (`see_screen`, vision parts) | `grep -rn 'MediaProjection\|createScreenCaptureIntent\|input_image\|image_url'` → 0 | **implemented here** (§3.7), via the workspace: the frame becomes an attachment the agent can read |
| Device actions available to the agent (list/install/uninstall/launch apps) | `getLaunchIntentForPackage` exists once, in the app's own install flow (`runtime/AndroidAppInstaller.kt`); no inventory query (`queryIntentActivities`/`getInstalledPackages` → 0) | open, see §4 |
| Interactive approvals outside Claude Code | `DshRuntimeBridge.respondToApproval` and `AntigravityRuntimeBridge.respondToApproval` are deliberate no-ops | upstream product decision, not a defect |
| Remote/LAN build server, project catalog (DCIX equivalent), device-folder import, battery-handoff receipts | no corresponding source | out of scope for this fork |

## 3. What this fork adds

### 3.1 Conversation find

- `ui/chat/ChatSearch.kt` — literal, case-insensitive, per-occurrence, transcript-ordered hits;
  1-based position, wrap-around stepping, reported truncation above `MAX_HITS = 2000`.
- `literalMatchRanges` matches with `String.regionMatches` instead of comparing case-folded copies,
  so offsets cannot shift when a case fold changes the character count.
- The searched text is the saved transcript: a reply that is still streaming is not searchable until
  it is appended — stated here rather than implied.
- UI: `ChatFindBar` in the workspace top bar (`Find in conversation`), status `n/total`,
  `No matches`, `N+ matches` on truncation, previous/next/close.
- Marking: `ui/SearchHighlight.kt` marks the **drawn** text. The reader's own messages are rendered
  verbatim, so the single active occurrence is marked there; a markdown reply drops markers such as
  `**`, so its occurrences are all marked and the active message gets the accent border + jump.

### 3.2 Long replies fold

- `ui/chat/ReplyFold.kt` — only an *older* assistant reply folds (`> 24` lines), never the newest,
  and the control states the message's real line count (`Show all · N lines`).
- Rendering uses a fixed-height `Box` + `clipToBounds` + `wrapContentHeight(unbounded = true)` so
  the reply keeps its real layout and only its top is shown (a `ConstrainedBox` would squeeze it
  instead of clipping it).

### 3.3 Message actions

- `ui/chat/ChatQuote.kt` — bounded quote (`6` lines, `400` chars) appended to the composer as an
  editable draft; quoting never dispatches a request.
- Per-message menu: copy message (clipboard + confirmation) and quote in reply.

### 3.4 Project-wide text search (1.0.7-dce.2)

- `runtime/WorkspaceTextSearch.kt` — literal, case-insensitive search over the saved project text:
  every match with file, 1-based line and column plus a bounded preview, and one reconcilable set of
  counters (`candidateFiles = scanned + binary + oversized + unreadable`), the held-back directories
  by name, a `truncated` flag when a ceiling stopped the scan, and a `fileSetId` (first 8 hex of
  sha256 over the sorted candidate paths) so two searches can be told apart as *different file sets*
  instead of "different result counts".
- Visibility follows the file browser: no symlinks, nothing outside the project root, no `.claude*`
  runtime metadata; generated/private trees (`.git`, `.gradle`, `.kotlin`, `.idea`, `.cxx`,
  `.externalNativeBuild`, `build`, `node_modules`) are never entered and are reported as skipped.
  Binary is decided by extension or by a NUL byte in the read head — a listing classifies no content,
  only a search does.
- `text/LiteralMatch.kt` is now the single literal matcher behind both the conversation find and this
  search.
- UI: `Find in files` from the Files tab (`FindInFilesScreen`), with the honest status line
  ("N matches in M files · scanned X of Y files · result capped · N folders skipped"); tapping a hit
  opens it in the file viewer at that line and column.
- Tests: `app/src/test/java/com/jarves/mh/runtime/WorkspaceTextSearchTest.kt` — 16 cases over a real
  temporary tree (columns, case folding, literal metacharacters, binary by name and by content,
  held-back directories, runtime metadata, a symlink pointing outside the root, size and match and
  file ceilings, id stability, blank query, missing root, preview bounds, reconciling counters).

### 3.5 Build fix
- `app/src/main/cpp/CMakeLists.txt`: the `SHELL:-include ${SHM_ROOT}/shm.h` option is now quoted.
  Unquoted, the shell splits a project path containing a space and the native build dies with
  `clang: error: no such file or directory: 'Projects/MobileHarness/...'`.

### 3.6 Attachment thumbnails (1.0.7-dce.4)

The app could already attach a photo (`chat_take_photo`, `image/*` picker) and hand the agent its
path, but the chip showed only a filename and a size: the person could not see which picture, in
which orientation, was on its way to the agent.

- `ui/attach/AttachmentPreview.kt` — pure decisions: which declared types and extensions are
  pictures this build can decode (a generic `application/octet-stream` falls back to the extension,
  a declared `image/heic` does **not** get guessed), the `MAX_PREVIEW_BYTES` bound, and the
  power-of-two `inSampleSize` ladder (`sampleSizeFor`) that keeps a decoded photo at or above the
  512 px chip edge instead of below it.
- `ui/attach/AttachmentThumb.kt` — `rememberAttachmentThumbnail` decodes on `Dispatchers.IO` and
  re-decodes only when path, size or type change; `decodeAttachmentThumbnail` re-applies the
  workspace containment rule (`canonicalFile` inside the root, regular file, non-zero and within the
  bound) and returns null on any failure.
- UI: `AttachmentChip` shows the thumbnail for previewable pictures and keeps the icon otherwise; a
  picture that is too large says `24.0 MB · no preview` next to its size instead of showing nothing.
- The workspace root reaches the chips through one new `attachmentRootPath` parameter
  (`PocketDevApp` → `WorkspaceScreen` → `ChatTab` → `MessageBubble` → `AttachmentChip`), resolved
  once per active project by `MainViewModel.attachmentRootPath()`.
- 12 JVM tests cover the type decisions, the refusal reasons, the downscale ladder (portrait and
  landscape, exact multiples, unknown dimensions, non-positive maximum) and the cache key.

### 3.7 Screen capture from the chat menu (1.0.7-dce.5)

DcentIDE's device surface has `see_screen`: a foreground service of type `mediaProjection`, started
only after the system screen-cast consent, writing frames into app storage so a model can look at
them. Mobile Harness had no way to capture at all (`MediaProjection`/`createScreenCaptureIntent`/`screenshot`
→ 0 files). This release ports the same idea in the shape this app already uses for pictures:

- `runtime/ScreenFrameFiles.kt` — pure rules: the frame name (`screenshot-yyyyMMdd-HHmmss.png`, with
  `-2`, `-3` … so two captures in the same second do not overwrite each other), the
  workspace-relative attachment path with both segments reduced to one plain component, and the
  plausibility test a written frame must pass (non-empty, within 24 MB, real PNG signature).
- `runtime/ScreenCaptureService.kt` — a foreground service declared with
  `foregroundServiceType="mediaProjection"`, one frame per invocation: consent token → projection →
  `ImageReader` → PNG written into the open chat's attachment folder → header re-read → projection and
  display released → service stops. A 12 s timeout reports `NO_FRAME` (a screen that is off yields no
  fresh content) instead of hanging, and every failure path publishes its own reason.
- `runtime/ScreenCaptureResult.kt` — the outcome travels back to the chat surface inside the process,
  so a frame becomes a normal `ChatAttachment` (thumbnail, removal, agent reference) with no separate
  delivery path.
- UI: the composer's attach menu gains **Screenshot** next to Take photo; the screen-cast consent stays
  the system dialog's decision, and a dismissed or unsupported one is reported.
- Manifest: `FOREGROUND_SERVICE_MEDIA_PROJECTION` plus the service; the compiled manifest of the built
  lane was checked with `aapt2 dump xmltree` (`foregroundServiceType=0x20`).
- 9 JVM tests cover naming, same-second dedupe, recognised frame names, the attachment path, the
  refusal of any chat/file name that is not one plain segment, and the plausibility bounds.

## 4. Deliberately not done here

- **Project-wide text search**: it needs a bounded walker (file/byte ceilings, binary and sensitive
  path classification, a stable file-set id) plus a results surface that opens a file at a hit. That
  is a second feature of comparable size and is not squeezed into this change.
- **Screen capture / vision**: needs a `mediaProjection` service, consent flow and an image part in
  the request payload. It cannot be verified on this host, and an unverified capture surface would
  be a claim without evidence.
- **Agent-facing device actions**: install/uninstall/launch already exist as app UI; exposing them to
  the agent needs a consent policy per tool and a real device to prove it.
- **Rebranding / new application id**: it would break update continuity for an installed copy.

## 5. Verification

- `app/src/test/java/com/jarves/mh/ui/chat/`: `ChatSearchTest`, `ReplyFoldTest`, `ChatQuoteTest`
  execute the real implementation over real `ChatMessage` transcripts (counting, case folding,
  regex metacharacters as literals, non-overlapping matches, wrap-around, truncation, offsets after
  multi-byte characters).
- `app/src/test/java/com/jarves/mh/ui/SearchHighlightTest.kt` asserts span placement on the drawn
  text, including the case where the rendered text differs from the source.
- Both lanes are built and published by DEXCORE. The published bytes are verified against the
  descriptor (sha256, size, package, version, signer) — a local build is never the released artifact.
- No device run is claimed for this change. The reading controls are host-verified (unit tests +
  real builds); on-phone behaviour stays an open item until the owner checks it.

Measured on this host:

| Check | Result |
| --- | --- |
| `:app:testOnlineDebugUnitTest` | 129 cases, 0 failures, 0 skipped (26 of them new) |
| `:app:assembleOnlineDebug` | `app-online-debug.apk`, 106,586,222 bytes, `com.jarves.mh` 1.0.7-dce.1 (9) |
| `:app:offlineDebug` (built by the DEXCORE deploy) | `app-offline-debug.apk`, 865 MB |
| `:app:assembleOnlineRelease` | builds; `app-online-release-unsigned.apk`, 89,146,440 bytes |
| native runtime audit (DEXCORE) | `static_ok`, 7 native entries, 0 errors, 0 warnings |
| witness in the built debug APK | 22 references to the new chat-control classes, `Find in conversation` present in `resources.arsc` |

## 6. Release state

Published to the **normal (public) DEXCORE archive** as `Mobile Harness (DCE)` —
https://dcenhancements.com/archive/projects/apks/MobileHarness/ — by the owner's decision, which
supersedes the earlier owner-only placement. The public lane is therefore the one that carries the
injected archive update client; the earlier internal snapshots (`1.0.7-dce.1`, `1.0.7-dce.2`) remain
as history in the internal archive and are no longer the delivery path.

| Lane | Artifact | Bytes | SHA-256 | Signer |
| --- | --- | --- | --- | --- |
| debug | `debug/MobileHarness(1.0.7-dce.5).apk` | 106,898,481 | see §6.1 | debug lab cert `ab327339…` |
| release | `release/MobileHarness(1.0.7-dce.5).apk` | 89,392,744 | see §6.1 | debug lab cert `ab327339…` |

Both lanes were built by DEXCORE's own `dexcore deploy website` run (which builds `assembleDebug`
and `assembleRelease`, i.e. both flavors, and passes the native runtime audit with `static_ok`), then
written into the canonical public archive with the signed update descriptors (`update.json` at the
root and per lane, ECDSA P-256) and mirrored into the served website tree. Because the public archive
is served without a login gate, the served bytes are verified **over HTTPS**: the versioned lanes,
both `latest.apk` aliases and the root alias were downloaded again and hashed against the descriptor.

The release lane is signed with the **Android debug lab certificate**, not a Play/production key:
upstream signs with its own `MH_UPLOAD_*` keystore, which this fork does not hold, and an unsigned
lane cannot be published at all.

### 6.1 Measured release evidence (1.0.7-dce.5)

Every value below was read back from `https://dcenhancements.com/archive/projects/apks/MobileHarness/`
after the publish — the public archive is not login-gated, so this is served-bytes evidence, not a
local file listing.

| Lane | Artifact | Bytes | SHA-256 | Signer (descriptor + APK) |
| --- | --- | --- | --- | --- |
| debug | `debug/MobileHarness(1.0.7-dce.5).apk` | 106,898,481 | `ab566cd87d3b941b7de532c7bdfe573cf6fe2377f7b406750f768d028bee8cb8` | `ab32733965c6871b367fd5168df180b99b87408812365a79280f386a2673173d` |
| release | `release/MobileHarness(1.0.7-dce.5).apk` | 89,392,744 | `6fcb3c9091fc9bc2bd95579888d8933de8df44c99e7ba55e5bfbaf06e26d21d2` | `ab32733965c6871b367fd5168df180b99b87408812365a79280f386a2673173d` |

- Both lane descriptors (`debug/update.json`, `release/update.json`, ECDSA P-256) carry
  `versionName 1.0.7-dce.5`, `versionCode 13`, `packageName com.jarves.mh` and the SHA-256 of the
  bytes actually served; each one matches its lane, and the root `update.json` points at the release
  bytes. Each descriptor carries `notices` for all 21 languages.
- Aliases resolve to the same bytes: `debug/latest.apk` = debug, `release/latest.apk` = release,
  root `latest.apk` = release, flat `MobileHarness.apk` = release. (The flat alias answered `404` for
  about a minute while the mirror was still copying and returned the release bytes on re-fetch.)
- The served release APK reports `package: com.jarves.mh versionCode=13 versionName=1.0.7-dce.5`.
- The **published** debug APK's compiled manifest carries
  `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION` and `com.jarves.mh.runtime.ScreenCaptureService`
  with `foregroundServiceType=0x20` (mediaProjection), read with
  `aapt2 dump xmltree --file AndroidManifest.xml`; its DEX contains `ScreenCaptureService`,
  `ScreenFrameFiles` and `ScreenCaptureResult`, plus the injected `dexcoreupdate` client.
- The version page (`.../v/13/`) and the app page render the categorized changelog with translated
  section labels (`Hinzugefügt`, `Añadido`, `Добавлено`, `जोड़ा`, `أُضيف`).
- JVM tests for this build: **166, 0 failures, 0 skipped** (157 before, 9 new in
  `ScreenFrameFilesTest`).

### Honest limits of the earlier internal placement (still true)

- The internal archive (`/_internal/**`) sits behind the login gate and returns
  `302 → /_internal/auth/login` for every path before any file lookup, so those APK bytes were
  verified on disk, not over HTTPS. That placement is history now.

### What had to be fixed to get there

1. **Play-baseline hardening corrupted the Gradle script.** `_ensure_16kb_packaging`
   (`scripts/play_baseline.py`) used `\s*` between `useLegacyPackaging` and `true`, which also
   matches the line break, so the rewrite glued the following statement onto
   `useLegacyPackaging = false` (`falseandroidResources.noCompress += "zst"`) and the Kotlin DSL no
   longer compiled. Fixed to horizontal whitespace only, with a regression test in
   `scripts/tests/test_native_extraction_policy.py`. Independently, this app needs extracted native
   files (the PRoot loader and PRoot binary are executed from `nativeLibraryDir`), so
   `app/build.gradle.kts` carries the documented `dexcore:play-baseline allow-legacy-packaging`
   exemption instead of the forced `false`.
2. **An internal archive target recorded public URLs.** `_webdeploy_prune_latest_only_archive`
   (`scripts/lib/website_deploy.sh`) wrote app-entry URLs with a hardcoded
   `/archive/projects/apks/…` prefix while `apps.json` declares `root: /_internal/archive/projects`,
   so the identity audit refused with `APK URL must be a same-origin archive path`. The pass now
   takes the archive root (default unchanged, so the public archive is byte-identical — proven
   against a fixture with the previous program) and `scripts/tests/test_prune_archive_urls.py`
   covers it.
3. **The internal mirror aborted on a stray symlink.** The served tree held
   `projects/manga_reader/manga_reader` as an absolute symlink; `_scan_tree` refuses symlinks in the
   destination, so every internal mirror aborted after the lane build. It is now a hardlink like the
   archive's own aliases.
4. **The internal index is rendered from a curated list.** An app appears on the internal page only
   when it is registered in `render_internal_archive.CURATED_SOURCES`; the deploy's lane layout
   (`debug/`, `release/`, `v/<code>/`) is not picked up by the renderer. MobileHarness is registered
   with its online debug/release outputs, and `render-internal` rebuilt the catalog. The same pass now
   reads the sibling `output-metadata.json` of a Gradle output, so the internal page names the real
   version and package instead of "not recorded" — without parsing the artifact.
5. Files that existed only in the served tree (manga_reader ×4, NPUDream ×1) were hardlinked into the
   canonical tree first, so the mirror's delete pass had nothing to prune.

### Honest limits

- The internal archive sits behind the existing login gate: `/_internal/**` returns
  `302 → /_internal/auth/login` for every path before any file lookup, so the served APK bytes were
  verified on disk, not over HTTPS.
- The rendered internal index dropped pre-existing `SplatVisionFresh` because that app's APK files
  are missing in *both* trees while its `SHA256SUMS` still lists them; the old catalog advertised
  two files that no longer exist. This is a pre-existing gap the re-render surfaced, not a
  consequence of this release.
- Device behaviour (install, runtime setup, the ported reading controls on a phone) is still an
  open owner check.



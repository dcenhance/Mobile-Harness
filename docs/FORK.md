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
| Native readiness measured instead of assumed (page size, real ELF alignment) | DcentIDE reads `Os.sysconf(_SC_PAGESIZE)`; here `16384`/`getPageSize`/`_SC_PAGESIZE` → 0 hits while `RuntimeCompatibility` only checked the ABI | **implemented here** (§3.8) |
| Device actions available to the agent (list/install/uninstall/launch apps) | `getLaunchIntentForPackage` exists once, in the app's own install flow (`runtime/AndroidAppInstaller.kt`); no inventory query (`queryIntentActivities`/`getInstalledPackages`/`getInstalledApplications` → 0, no `<queries>` block) | **open** — needs a tool bridge for the PRoot CLIs (loopback endpoint + shell wrapper in the runtime PATH), read-only first |
| Editor comfort: syntax highlighting, word wrap | `keywords`/`tokenize`/`wordWrap` → 0 in the file editor (chat code blocks are highlighted by `ui/MarkdownText.kt`) | **highlighting implemented here** (§3.10); word wrap deliberately not: the editor pairs a 48 dp line-number gutter with a horizontal scroll and a 720 dp minimum width, so wrapping would desynchronise the gutter |
| Standing project rules and a working style the user can edit, with a visible prompt | `CLAUDE.md`/`AGENTS.md` → 0 hits and no prompt preview; the installer wrote only `.claude/settings.json` | **implemented here** (§3.9) |
| What a run costs / how full the context is | no agent usage parsing (`total_cost`, `input_tokens`, `cache_read`, `num_turns` → 0 outside the local API gateway), no session usage state | **open** |
| Native agent session continuation | both bridges re-send the transcript into the prompt (`ClaudeRuntimeBridge.buildContextPrompt`, `DshRuntimeBridge.buildContextPrompt`); `--resume`/`session_id` → 0 hits, so an app restart cannot pick up an existing agent session | **open** — per-CLI knowledge required |
| Import a project folder from the phone (SAF tree) | `ACTION_OPEN_DOCUMENT_TREE`/`OpenDocumentTree` → 0; only ZIP import exists (`importZipLauncher`) | **open**, small |
| Project backup/restore and chat export | `backupProject`/`restoreProject`/`exportChat` → 0; agent edits are covered by `WorkspaceCheckpoints` (94 refs) and a project ZIP export exists | **open**, small |
| Interactive approvals outside Claude Code | `DshRuntimeBridge.respondToApproval` and `AntigravityRuntimeBridge.respondToApproval` are deliberate no-ops | upstream product decision, not a defect |
| Remote/LAN build server, project catalog (DCIX equivalent), battery-handoff receipts | no corresponding source | out of scope for this fork |

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

### 3.8 Native readiness is measured, not assumed (1.0.7-dce.6)

DcentIDE's device work carries a hard-won rule — read the page size the kernel reports and check the
real native files instead of trusting a 4 KiB default or a successful build. This fork had the same
blind spot: `runtime/RuntimeCompatibility.kt` knew only `supportsArm64Runtime(abis, osArch)`, and
nothing in the app ever looked at the page size (`16384`, `getPageSize`, `_SC_PAGESIZE` → 0 hits),
although the project's own NDK comment warns that a wrong page size makes the native runtime crash.

- `runtime/NativeLoadAlignment.kt` — reads what the loader reads: the ELF header, then the LOAD program
  headers in one bounded window (max 128 entries, max 16 KiB table, never the file body). Per file it
  reports the minimum segment alignment and whether every segment satisfies the kernel's rule
  `(vaddr - offset) % align == 0`, and turns that into one verdict per refusing rule: `READY`,
  `BELOW_DEVICE_PAGE`, `PAGE_SIZE_UNKNOWN`, `MISALIGNED_SEGMENT`, `NOT_ELF`, `UNREADABLE`. An
  unknowable page size never becomes a pass for a 4 KiB-aligned file.
- `runtime/NativeReadiness.kt` — the Android half: the page size the kernel actually reports
  (`Os.sysconf`, 0 when unknown), the libraries in `nativeLibraryDir`, and a bounded, deterministic
  sample of 24 extracted runtime binaries, so the check costs the same on a large rootfs.
- UI: both device surfaces (the setup prompt's spec list and the setup wizard's device step) gained two
  rows — the reported page size and the native-file count (`n files ready to load`, or
  `k of n not aligned for this page size`). The check runs off the composable thread and shows
  "Checking…" until it has a real result, so nothing claims readiness before measuring it.
- 16 JVM tests, of which the alignment cases run against **real ELF files**: two shared objects built
  with this project's own NDK clang, one linked with `-Wl,-z,max-page-size=4096` and one with `16384`.
  The rest cover a truncated program-header table, a forged header count, a big-endian ELF, a segment
  whose address disagrees with its offset, an ELF without LOAD segments, an empty file, a text file and
  a 64 MiB sparse file that must be inspected from its headers alone.

### 3.9 Standing project guidance, visible and editable (1.0.7-dce.7)

The app composed the prompt that goes to the agent (request plus an `<attached_files>` block) but the
user could neither add standing guidance nor see what the agent actually receives.

- `runtime/PromptOverlay.kt` — `AgentGuidance(instructions, personality)` with per-field caps
  (4000/2000 chars, a shortened field is marked), blank-line collapse, `compose(request, guidance)`
  (rules first, then working style, then the request verbatim; unchanged with empty guidance) and
  `workspaceDocument(guidance)` for the mirror file.
- The **same** `compose` call feeds the send path (`MainViewModel.startPrompt`) and the dialog's
  preview, so what is previewed is what is sent; the preview uses the last real request as its example.
- Stored per project (`AppPreferences.saveProjectGuidance`), mirrored to
  `.mobileharness/project-guidance.md` in the workspace (app-owned path, deleted again when the text is
  cleared), and reachable from the chat top bar.
- 13 JVM tests pin the composition (order, empty case, request last), the caps, the collapse, the mirror
  document and that preview and send are the same call.

### 3.10 Editor colouring without touching the text (1.0.7-dce.8)

The file editor and the file view showed code as plain text (`keywords`/`tokenize` → 0 hits in the tree;
only chat code blocks were highlighted, by `ui/MarkdownText.kt`).

- `text/CodeHighlight.kt` — a linear token pass per language family (Kotlin/Java/JS/TS/Python, JSON, XML,
  CSS, shell, YAML, Markdown) producing ordered, non-overlapping `HighlightSpan`s for keywords, built-in
  types, strings, comments, numbers, annotations and keys. Strings and comments are consumed as a whole,
  so a keyword inside one is never marked twice; an unterminated string ends at the line (or the file);
  files above 200k characters stay plain rather than colouring a huge file on a phone.
- `ui/CodeHighlightTransformation.kt` — a `VisualTransformation` that returns the *same characters* with
  span styles and `OffsetMapping.Identity`, so the editor's 48 dp line-number gutter keeps its alignment
  and every caret offset stays valid (the risk DcentIDE documents for editor gutters).
- The read-only file view colours per line: the spans and line starts are computed once per file, then
  sliced per line (`spansForLine`), so the renderer stays linear instead of re-scanning the file.
- Colours come from the editor's existing dark palette (GitHub-dark-like) with no amber/gold and the
  app's own accent for annotations.
- 26 JVM tests: 21 for the token pass (per-language recognition, a keyword inside a string/comment,
  escaped quotes, triple-quoted strings, unterminated strings, JSON keys vs values, YAML keys, XML tags,
  CSS at-rules, Markdown fences, the size bound, line starts and per-line slicing, and an
  ordered/non-overlapping invariant over a mixed corpus) and 5 for the transformation (text unchanged,
  identity offset mapping, colours attached per kind, plain text and empty input).

### 3.11 Read-only device facts for the agents (1.0.7-dce.9)

DcentIDE could act on the device because it *is* the device UI: the same process knew the build, the
storage and the app list. In Mobile Harness the agents are command-line programs inside the app's own
rootfs, so they cannot ask Android anything — measured before this change, `Build.VERSION`, `StatFs` and
`queryIntentActivities` had **zero** call sites reachable from the agent path. The bridge is the answer,
and it is deliberately read-only:

- `runtime/DeviceQueryServer.kt` — a small HTTP server on `127.0.0.1` with an ephemeral port, a per-run
  32-hex token in the `X-MH-Token` header and a loopback peer check, so another app on the phone cannot
  use it as a device-inventory oracle. Exactly two routes exist: `/device/info` and `/apps?offset=&limit=`
  (limit clamped to 300). There is no write, install, launch or file route.
- `runtime/AgentDeviceBridge.kt` — starts the server, writes a per-run token file (owner-readable only)
  and a `mh-device` Python wrapper into the rootfs's `usr/bin`, so the command is on `PATH` inside the
  runtime and uses only the standard library (no `curl` dependency). Both files are removed again when
  the bridge stops. The facts come from `Build`, `StatFs`, `ActivityManager` and — for page size — the
  same ELF measurement as §3.8.
- The app list uses a `<queries>` entry for `MAIN`/`LAUNCHER` only, so the app never requests
  `QUERY_ALL_PACKAGES` and never sees apps it has no launcher entry for.
- The prompt carries a one-line `<device_access>` hint **only while the bridge runs**, and the workspace
  guidance document gains a matching section; with the switch off, the command does not exist and no
  agent is told about it.

`DeviceQueryServerTest` drives the real socket over the loopback interface (status codes, token gate,
paging, clamp, `next_offset`, shutdown), and `AgentDeviceBridgeTest` pins the token shape, the wrapper's
read-only surface and the prompt hint. A phone run is **not** part of this evidence.

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
| debug | `debug/MobileHarness(1.0.7-dce.8).apk` | 107,023,361 | `8a8bd68cec4ee3a1eeb513a66292964bc85b1911f864c8494de9f4a14dd6c0fc` | debug lab cert `ab327339…` |
| release | `release/MobileHarness(1.0.7-dce.8).apk` | 89,490,016 | `3386b7d9c460b86635e1b9a99d8c4a22e9182b8ea61317bbcbedc3f55fd55146` | debug lab cert `ab327339…` |

Both lanes were built by DEXCORE's own `dexcore deploy website` run (which builds `assembleDebug`
and `assembleRelease`, i.e. both flavors, and passes the native runtime audit with `static_ok`), then
written into the canonical public archive with the signed update descriptors (`update.json` at the
root and per lane, ECDSA P-256) and mirrored into the served website tree. Because the public archive
is served without a login gate, the served bytes are verified **over HTTPS**: the versioned lanes,
both `latest.apk` aliases and the root alias were downloaded again and hashed against the descriptor.

The release lane is signed with the **Android debug lab certificate**, not a Play/production key:
upstream signs with its own `MH_UPLOAD_*` keystore, which this fork does not hold, and an unsigned
lane cannot be published at all.

### 6.1 Measured release evidence (1.0.7-dce.9)

Every value below was read back from `https://dcenhancements.com/archive/projects/apks/MobileHarness/`
after the publish — the public archive is not login-gated, so this is served-bytes evidence, not a
local file listing.

| Lane | Artifact | Bytes | SHA-256 |
| --- | --- | --- | --- |
| debug | `debug/MobileHarness(1.0.7-dce.9).apk` | 107,075,341 | `d476da0e84fc93660cc05a13c69f92645ab0d928cdedd841b4d4bbcdf4e7d902` |
| release | `release/MobileHarness(1.0.7-dce.9).apk` | 89,528,228 | `deb1639fc71ffe9a12156e326abf5a44cd14de9ee78bdf411d02f9ba827b4e90` |

- Both lane descriptors carry `versionName 1.0.7-dce.9`, `versionCode 17`, `packageName com.jarves.mh`,
  the SHA-256 of the served bytes, the Android debug lab certificate
  (`ab32733965c6871b367fd5168df180b99b87408812365a79280f386a2673173d`, not a Play key) and `notices` for
  all 21 languages; the root `update.json` points at the release bytes, and `release/latest.apk`, the
  root `latest.apk` and the flat `MobileHarness.apk` all serve the release bytes.
- JVM tests for this build: **238, 0 failures, 0 skipped** (221 before, 17 new — 10 in
  `DeviceQueryServerTest` over a real loopback socket, 4 in `AgentDeviceBridgeTest`, 3 in the prompt
  overlay).
- **Measured, not fixed here:** the flat alias `MobileHarness.apk` answered *intermittently* for a few
  minutes after the publish — plain requests alternated between `200` with the correct bytes and a
  `404` with a small JSON body (`{"ok": false, "error": "not_found"}`) from a dynamic backend, while the
  lane URLs and the versioned files were stable throughout. Once settled it served the release bytes
  exactly (hashed above). Worth a look in the website/archive layer; it never affected any URL the
  archive page or the updater advertises.

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



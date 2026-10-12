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
| Project-wide text search (`total / listed / held back`, stable file-set id) | `grep -rnE 'readLines\|lineSequence\|useLines'` shows only installer/gradle property parsing, no content search | open, see §4 |
| Screen capture and image frames handed to the model (`see_screen`, vision parts) | `grep -rn 'MediaProjection\|createScreenCaptureIntent\|input_image\|image_url'` → 0 | open, see §4 |
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

Published to the **internal** (owner-only, login-gated) DEXCORE archive as
`Mobile Harness (DCE)` — https://dcenhancements.com/_internal/archive/projects/MobileHarness/ — because
the APK embeds third-party agent payloads and the upstream README asks for a redistribution review.

| Lane | Artifact | Bytes | SHA-256 | Signer |
| --- | --- | --- | --- | --- |
| debug | `debug/MobileHarness(1.0.7-dce.2).apk` | 106,672,054 | `947baa42…` | debug lab cert `ab327339…` |
| release | `release/MobileHarness(1.0.7-dce.2).apk` | 89,223,496 | `15d41f30…` | debug lab cert `ab327339…` |

The previous `1.0.7-dce.1` lanes stay published as history (the internal page keeps a snapshot
history of two builds).

Both lanes were built by DEXCORE's own `dexcore deploy website` run (which builds `assembleDebug`
and `assembleRelease`, i.e. both flavors, and passes the native runtime audit with `static_ok`), then
written into the canonical internal archive with the signed update descriptors
(`update.json` at the root and per lane, ECDSA P-256) and mirrored into the served website tree.
The served bytes were re-hashed in place and match the descriptor; nothing else in the internal
archive was lost (both trees now hold 37,186 files and no symlinks).

The release lane is signed with the **Android debug lab certificate**, not a Play/production key:
upstream signs with its own `MH_UPLOAD_*` keystore, which this fork does not hold, and an unsigned
lane cannot be published at all.

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



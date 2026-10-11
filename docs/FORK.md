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

### 3.4 Build fix

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

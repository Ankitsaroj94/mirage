# Mirage → Mac ⇄ Android (Samsung tablet) — plan

> On approval, step 0 is to copy this file to the repo as `plan.md`.

## Context

Mirage today is Mac→Mac only: the sending Mac spawns `MirageDisplayHelper` to create a
`CGVirtualDisplay` matched 1:1 to the receiving Mac's panel, captures it with
ScreenCaptureKit, hardware-encodes HEVC/H.264 (VideoToolbox, low-latency RC, no B-frames),
and streams over one TCP `PeerLink` (16-byte LE header, JSON control / packed binary hot
path, frame-ack backpressure, AIMD bitrate, optional X25519+HMAC+AES-GCM). Measured ~19 ms
p50 end-to-end. No audio.

Goals (user's Samsung tablet, **Android 16 / One UI 8.5**):

1. **Mac → Android**: the tablet becomes a real extended display of the Mac (same model as Mac→Mac).
2. **Android → Mac**: the Mac becomes an **extended display for Android** (a second Android desktop shown on the Mac, controlled with the Mac's mouse/keyboard).
3. **Audio**: Mac audio plays on **Mac speakers + tablet speakers, in sync** (macOS 14.2+).
4. **Latency/performance** as close to Mac→Mac as the hardware allows.
5. **Auto screen scaling** for the tablet (right UI size, crisp, rotation-aware).

Design rule kept from the Mac version: **no resampling by default** — stream at the panel's exact pixel size, scale only when the user asks for "more space".

---

## Architecture overview

```
Mac (host)                                   Samsung tablet (receiver)
 CGVirtualDisplay (helper, sized by planner) ─┐
 ScreenCaptureKit → VideoToolbox HEVC ── TCP ─┼─► MediaCodec low-latency → SurfaceView (HWC overlay)
 Core Audio process tap (muted) ─┬─ UDP ──────┼─► jitter buffer → AudioTrack LOW_LATENCY (scheduled)
                                 └─► delayed local playout (Mac speakers, same playout clock)
 CGEvent injector ◄──────────── input (TCP) ──┴── touch / S Pen / keyboard / mouse

Android (host, via Shizuku = shell uid)      Mac (receiver — existing ClientSession, unchanged path)
 trusted VirtualDisplay (desktop mode) ──┐
   Surface = MediaCodec encoder input    ├ TCP ► VideoRenderer (AVSampleBufferDisplayLayer) full screen
 InputManager.injectInputEvent(displayId) ◄──── InputForwarder (existing)
```

The **wire protocol stays the contract**; Android is a second implementation of both roles.

---

## Phase 0 — Spikes on the real tablet (de-risk first, ~2–3 days)

| Spike | Question | Pass criterion |
|---|---|---|
| S0a | Android decode latency | MediaCodec HEVC 2560×1600 with `KEY_LOW_LATENCY` → `releaseOutputBuffer(render=true)` queue-to-render < 8 ms p50 |
| S0b | Android extended desktop | From a Shizuku UserService (shell uid), create a trusted `VirtualDisplay` + enable Android 16 desktop windowing on external displays (`enable_freeform_support`, `force_desktop_mode_on_external_displays`, "desktop experience" dev option). Does One UI 8.5 show a taskbar/launcher on it, or does DeX intercept? Does a mouse pointer render on that display? |
| S0c | Mac audio tap | `CATapDescription` global stereo tap excluding Mirage, `muteBehavior = .mutedWhenTapped`, replay to default output with N ms delay; confirm volume keys still work and no glitches |

S0b decides the Android-host UX: **desktop mode works → use it; otherwise → Mirage's own launcher Activity on the virtual display** (app grid + simple taskbar, apps started with `ActivityOptions.setLaunchDisplayId`).

---

## Phase 1 — Protocol v2 + Mac-side refactors

### 1.1 Spec + shared test vectors (new)
- `spec/PROTOCOL.md`: header, message types, JSON schemas, `InputEvent` 32-byte layout, handshake, nonce layout, keycodes. Note: Swift `JSONEncoder` encodes `Data` as **base64** — Kotlin must match.
- `spec/vectors.json`: golden bytes for headers, InputEvents, HKDF/HMAC/GCM with fixed keys. Consumed by `Tests/Harness` **and** Android unit tests → interop guaranteed without both devices.

### 1.2 `Sources/Mirage/Core/Protocol.swift`
- `ClientHello.currentVersion = 2`; new fields **optional** so v1 JSON still decodes:
  `platform` ("macos"/"android"), `physicalDPI`, `scalePreference`, `supportsAudio`, `audioUDPPort`, `orientation`.
- New `MessageType`s:
  - `reconfigure = 0x12` client→host JSON `PanelDescription` (rotation / scale change)
  - `audioConfig = 0x70` host→client JSON (sampleRate 48000, channels 2, format s16le, packetMs 5, udpPort, playoutDelayMs)
  - `clockSync = 0x42` / extend pong payload with responder clock → offset estimate for A/V sync + cross-device latency stats.
- `HostSession.startPipeline`: accept `protocolVersion` 1…2 (Mac v1 peers keep working).

### 1.3 Display geometry planner (new `Sources/Mirage/Host/DisplayGeometry.swift`)
Pure function, unit-tested:
`plan(panelPx, physicalDPI, preference, orientation) -> (virtualPx, virtualPt, streamPx)`
- **Default "Crisp"**: `virtualPt = panelPx / 2`, `virtualPx = streamPx = panelPx` (HiDPI 2×, zero resampling). Tab S10/S11 11" 2560×1600 → 1280×800 pt (~137 pt/in, like a MacBook); 12.4" → 1400×876; Ultra 14.6" → 1480×924.
- **"More space" / "Most space"**: virtualPt ×1.15 / ×1.3; `virtualPx = 2×virtualPt`; `streamPx = panelPx` (SCK `scalesToFit` downsample on the Mac GPU = same trade-off as macOS scaled modes, saves bandwidth).
- **"Larger text"**: virtualPt ×0.85.
- Even-dimension rounding (existing rule from `ClientSession.currentPanel`).
- Mac clients keep exact current behaviour (`streamPx == virtualPx == panelPx`).

### 1.4 Pipeline changes
- `DisplayCapture.Configuration` gets `streamWidth/Height`; `scalesToFit = stream != virtual`.
- `HostSession`: handle `.reconfigure` → tear down helper+capture+encoder, keep `PeerLink`, rebuild (helper-per-display model already makes this clean); client's `VideoRenderer.apply(parameterSets:)` already resets on new params.
- `Discovery.swift`: TXT adds `os`, `dpi`, `audio`; `DiscoveredDisplay` gets `platform`. Add **manual "Connect to IP…"** (`NWEndpoint.hostPort`) as fallback when Samsung/mesh Wi-Fi blocks mDNS.
- `UI/ControlPanel.swift`: tablet icon + scale preference picker per device (remembered by name), audio options, 120 Hz option when panel reports it.
- `HostSettings`: `scalePreference`, `audioMode` (`.off | .tabletOnly | .both`), `audioSyncOffsetMs`.

---

## Phase 2 — Android app, receiver role (Mac → tablet)

New Gradle project `android/` (Kotlin 2.x, Compose, compileSdk 36, **minSdk 33** — native `XDH` X25519; primary target Android 16). Package `in.ankitsaroj.mirage`.

```
android/app/src/main/java/com/voxturr/mirage/
  core/      Wire.kt (header, types, InputEvent), PeerLink.kt, SecureChannel.kt,
             Discovery.kt (NsdManager register/discover + TXT), ClockSync.kt, Json.kt
  receiver/  ReceiverActivity.kt, VideoDecoder.kt, InputCapture.kt, KeyMap.kt,
             AudioReceiver.kt, ReceiverService.kt (foreground, connectedDevice)
  host/      (Phase 4)
  ui/        Compose screens: Home (Be a display / Send my screen), Settings, Stats overlay
```

**Network (`PeerLink.kt`)** — byte-identical framing to `Core/PeerLink.swift`: dedicated reader thread, `Socket.tcpNoDelay = true`, `trafficClass` DSCP CS4/AF41 (video), large receive buffer, ping/pong on the I/O thread, `frameAck` after decoder queue (same semantics as `ClientSession`). `SecureChannel.kt`: X25519 (`KeyAgreement("XDH")`), HMAC-SHA256, HKDF (manual, ~15 lines), AES/GCM/NoPadding, identical nonce layout.

**Low-latency video (`VideoDecoder.kt`)**
- `MediaCodec` HEVC/H.264, async callbacks on a `HandlerThread` at `THREAD_PRIORITY_URGENT_DISPLAY`; `KEY_LOW_LATENCY=1` (verify `FEATURE_LowLatency`), `KEY_PRIORITY=0`, `KEY_OPERATING_RATE` high.
- csd from `VideoParameterSets` (prefix start codes); frames: rewrite 4-byte AVCC lengths → `00 00 00 01` **in place** (same size, zero copy).
- Render immediately `releaseOutputBuffer(idx, true)`; drop stale outputs if >1 pending (keep newest — same philosophy as `FramePump`).
- `SurfaceView` (not TextureView) → HWC overlay, no GPU composition. Request 120 Hz mode via `preferredDisplayModeId` + `Surface.setFrameRate`.
- `PerformanceHintManager` session for decode thread; `WifiManager` **`WIFI_MODE_FULL_LOW_LATENCY`** lock (kills Wi-Fi power-save — single biggest Android latency win); `FLAG_KEEP_SCREEN_ON`.
- Keyframe request on decoder error / missing params (mirrors `VideoRenderer.onNeedsKeyframe`).

**Auto scaling on the tablet side**
- Report `Display.getMode()` physical px (not app window), `DisplayMetrics.xdpi`, rotation. Immersive fullscreen (`WindowInsetsController.hide(systemBars)`, `layoutInDisplayCutoutMode = ALWAYS`) so the window == panel and the frame maps 1:1.
- On rotation (`onConfigurationChanged`, activity handles orientation itself) → send `reconfigure`; Mac rebuilds a portrait/landscape virtual display (~1 s blink). Optional "lock orientation".

**Input (`InputCapture.kt`, `KeyMap.kt`)** → existing `InputEvent` (normalised coords — no geometry knowledge needed):
- Touch "direct" mode: tap = click, drag = drag, long-press = right-click, two-finger pan = precise scroll, double-tap = clickCount 2.
- S Pen: hover → `mouseMove`, tip = left, side button = right.
- Book Cover / BT keyboard: Android `KEYCODE_*` → mac virtual keycodes table; modifiers → `CGEventFlags` bits (Ctrl/Alt/Meta→Cmd configurable).
- Physical mouse/touchpad via generic motion events (hover, buttons, `AXIS_VSCROLL/HSCROLL`).

Mac side needs no change for input beyond what exists (`InputInjector`).

---

## Phase 3 — Synced audio (Mac → Mac speakers + tablet)

**Capture (new `Sources/Mirage/Host/Audio/SystemAudioTap.swift`)** — macOS 14.2+:
`CATapDescription(stereoGlobalTapButExcludeProcesses: [own AudioObjectID])`, `muteBehavior = .mutedWhenTapped`, `AudioHardwareCreateProcessTap` → private aggregate device → IOProc → lock-free ring buffer, 48 kHz Float32 → s16le. Requires `NSAudioCaptureUsageDescription` in `Resources/Info.plist`. Fallback on older macOS: SCK `capturesAudio` with mode limited to "tablet only".

**Transport (new `Host/Audio/AudioSender.swift`)** — separate **UDP** flow (`NWConnection` udp, `serviceClass = .interactiveVoice` → WMM voice queue, highest priority) so a video keyframe can never head-of-line-block audio. Raw PCM 48 kHz stereo 16-bit (1.5 Mbit/s — trivial on LAN, zero codec delay), 5 ms packets: `seq | hostSampleTime | pcm`, AES-GCM with the session key when pairing is on (nonce direction byte 2 to avoid reuse with TCP). Negotiated via `audioConfig` on the TCP link.

**Sync model** — one playout clock owned by the Mac:
- Every sample gets `playAt = captureHostTime + D` (D default 60 ms, user slider ±).
- **Mac speakers** (`Host/Audio/LocalPlayout.swift`): AUHAL on the default output; schedules from ring buffer at `playAt − deviceOutputLatency` (from `kAudioDevicePropertyLatency` + safety offset).
- **Tablet** (`AudioReceiver.kt`): `ClockSync` maps host time → `System.nanoTime()` (NTP-style over the TCP ping, min-RTT filtered); jitter buffer; `AudioTrack` `PERFORMANCE_MODE_LOW_LATENCY`, `AudioTrack.getTimestamp()` to know when a frame hits the speaker; correct drift by ±0.1–0.5 % resampling (never skip — audible). Packet loss → short crossfade/repeat (PLC).
- Fallback if AudioTrack latency is too high on One UI: Oboe/AAudio via NDK (only if measured necessary).
- UI: "Play Mac sound on: Mac + tablet (synced) / Tablet only / Mac only" and a sync-offset fine-tune slider.

---

## Phase 4 — Android host role (Mac as extended display for Android)

Needs shell-level privilege (only system/shell code can host other apps on a second display and inject input). **Primary: the Mac starts it over adb**, scrcpy-style. Mirage on the Mac (`Host/AdbLauncher.swift`) finds `adb` and runs `app_process` with the installed APK as classpath, so `ServerMain` runs as the shell uid on the tablet. It connects back to the Mac's listener over Wi-Fi (not tunnelled through adb). That needs only USB debugging, which is already on, or a one-time wireless-adb pairing; nothing extra is installed. **Optional: Shizuku**, which runs the same server in a Shizuku UserService so it can be started from the tablet alone.

- **Discovery**: Android browses `_mirage._tcp` (NsdManager); Mac runs "Be a display" exactly as today. Mac `ClientSession` sends `ClientHello` with its panel → Android host.
- **Virtual display** (`host/privileged/DisplayService.kt`): `DisplayManager.createVirtualDisplay` sized to the Mac panel px, `densityDpi = 160 × backingScale` (Mac 2× → 320 dpi → dp width == Mac points, desktop-sized UI), flags `PUBLIC | OWN_CONTENT_ONLY | TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | SUPPORTS_TOUCH | DESTROY_CONTENT_ON_REMOVAL`, and **Surface = the encoder's input surface** (zero-copy GPU → encoder).
- **Desktop**: per S0b — Android 16 desktop windowing on that display, or Mirage launcher Activity launched on `displayId`.
- **Encoder** (`host/VideoEncoderAndroid.kt`): MediaCodec HEVC surface input, `KEY_LATENCY=1`, `KEY_PRIORITY=0`, `KEY_MAX_B_FRAMES=0`, long `KEY_I_FRAME_INTERVAL`, `KEY_REPEAT_PREVIOUS_FRAME_AFTER` off, BT.709 tags matching the Mac encoder; `PARAMETER_KEY_REQUEST_SYNC_FRAME` on `requestKeyframe`; `PARAMETER_KEY_VIDEO_BITRATE` for AIMD. Output Annex-B → AVCC + `VideoParameterSets` JSON so **the Mac `VideoRenderer` is reused unchanged**.
- **Backpressure**: port `FramePump` logic (max 3 unacked frames, drop-newest, AIMD) 1:1.
- **Input from Mac** (`host/privileged/InputService.kt`): `InputManager.injectInputEvent` (hidden API, shell uid) with `displayId` set: mouse → `SOURCE_MOUSE` hover/button/`ACTION_SCROLL`; keys → mac keycode → Android `KeyEvent` + meta (Cmd→META/CTRL configurable).
- **Optional audio Android→Mac**: shell-uid `AudioRecord` on `REMOTE_SUBMIX` (scrcpy approach) → same UDP audio path → Mac player (reuse `LocalPlayout`).
- Mac side: `ClientSession`/`VideoRenderer`/`InputForwarder` reused; only UI text ("Android device") and accepting `platform == android` in `HostHelloAck`.

---

## Phase 5 — Polish & extras
- 120 Hz end to end (CGVirtualDisplay 120 Hz + SCK 1/120 + tablet 120 Hz) as an opt-in.
- **Wired mode**: USB-C cable + Android USB tethering (CDC-NCM, native on macOS) → same TCP/UDP, sub-ms link, no Wi-Fi jitter.
- Stats overlay on Android (fps, bitrate, decode ms, RTT, cross-device glass latency via ClockSync, audio buffer depth).
- QR-code pairing (encodes IP:port + pairing code) for when mDNS is blocked.
- README sections for Android install (sideloaded APK), Shizuku setup, permissions table.
- `Makefile`: `make android`, `make android-install` (`./gradlew installRelease`), `make test` also runs `./gradlew test`.

---

## Critical files

Modify (Mac): `Sources/Mirage/Core/Protocol.swift`, `Core/PeerLink.swift` (clock sync in pong), `Core/Discovery.swift`, `Host/HostSession.swift`, `Host/DisplayCapture.swift`, `Host/HostSettings.swift`, `Client/ClientSession.swift` (v2 hello fields), `UI/ControlPanel.swift`, `Resources/Info.plist`, `Makefile`, `README.md`, `Tests/Harness/main.swift`.
New (Mac): `Host/DisplayGeometry.swift`, `Host/Audio/{SystemAudioTap,AudioSender,LocalPlayout}.swift`, `Core/ClockSync.swift`, `spec/PROTOCOL.md`, `spec/vectors.json`, `Tests/AudioHarness`.
New (Android): `android/` as laid out above.
Reused unchanged: `MirageDisplayHelper`, `MirageBridge`, `VideoEncoder`, `FramePump` (logic ported to Kotlin), `VideoRenderer`, `InputForwarder`, `InputInjector`, `SecureChannel` (spec'd to Kotlin).

---

## Latency budget & targets (Wi-Fi 6, same 5 GHz/6 GHz AP)

| Path | Target p50 | Notes |
|---|---|---|
| Mac → tablet video | ≤ 30 ms (≤ 20 ms wired) | SCK+VT ≈ 10 ms, Wi-Fi 2–5 ms, MediaCodec low-latency 4–8 ms, HWC scan-out |
| Tablet touch → Mac cursor | ≤ 15 ms | TCP small packets, injected on network queue |
| Audio Mac+tablet skew | ≤ 5 ms | D ≈ 60 ms total playout delay (tunable) |
| Android → Mac video | ≤ 35 ms | encoder surface input, Mac decode as today |

---

## Verification

1. `make test` — existing harnesses + new: protocol v1/v2 compat, `spec/vectors.json` round-trip, `DisplayGeometry` table (Tab S10/S11/Ultra sizes, rotation), audio ring-buffer/scheduler test with fake clock.
2. `cd android && ./gradlew test` — Wire/SecureChannel/AVCC-rewrite/KeyMap against `spec/vectors.json`; **cross-impl loopback**: a JVM test talks to a Swift `Tests/Harness` peer over localhost (encrypted + wrong-code paths).
3. On device:
   - Mac→tablet: display appears in System Settings › Displays at 1280×800 (11") HiDPI; text crisp; rotate tablet → portrait display; overlay latency ≤ 30 ms; touch/S Pen/keyboard work.
   - Audio: play a click track (`afplay`) — record both speakers with a phone mic, confirm skew ≤ 5 ms; volume keys work; disconnect restores normal Mac audio.
   - Android→Mac: Shizuku running → "Send my screen" on tablet → Mac full screen shows Android desktop; mouse/keyboard drive it; apps open on it.
   - Mac→Mac regression: old v1 build ↔ new build still connects; `Tests/LatencyHarness` still ~19 ms.
4. `adb logcat -s Mirage` and `log stream --predicate 'subsystem == "com.voxturr.mirage"'` for diagnostics.

## Execution — 6 parallel subagents, then on-device test

Step 0 (me, before fan-out): copy plan to `plan.md`, write `spec/PROTOCOL.md` + `spec/vectors.json` (the frozen contract every agent codes against), check `adb devices` shows the tablet.

Each agent works in its own git worktree (`isolation: "worktree"`), owns its file set and must leave its own tests passing:

| # | Agent | Owns | Tests it must pass |
|---|---|---|---|
| 1 | Mac protocol + geometry | `Core/Protocol.swift`, `Core/PeerLink.swift`, `Core/ClockSync.swift`, `Core/Discovery.swift`, `Host/DisplayGeometry.swift`, `Host/HostSession.swift`, `Host/DisplayCapture.swift`, `Client/ClientSession.swift`, `Tests/Harness` | `make test`, v1↔v2 compatibility, vectors |
| 2 | Mac audio | `Host/Audio/*`, `Info.plist`, `Tests/AudioHarness` | the audio harness, which uses a fake clock and needs no permissions |
| 3 | Mac UI + build | `UI/ControlPanel.swift`, `HostSettings.swift`, `Makefile`, `README.md` | `make` builds |
| 4 | Android core + scaffold | `android/` Gradle setup, `core/*` (Wire, PeerLink, SecureChannel, Discovery, ClockSync) | `./gradlew test` against the vectors |
| 5 | Android receiver | `receiver/*`, `ui/*` | unit tests for the AVCC rewrite, KeyMap and touch gestures |
| 6 | Android host (Shizuku) | `host/*`, `host/privileged/*` | unit tests for the Annex-B→AVCC conversion, FramePump port and input mapping |

Agents 5 and 6 code against agent 4's interfaces, which are defined in `spec/` up front, so they don't block each other.

Integration (me): merge the worktrees, then run `make test` and `./gradlew test`. Build and launch the Mac app, run `./gradlew installDebug` to the connected Samsung tab, and run the Phase 0 spikes plus the Verification §3 checks on the device, using `adb logcat`, `screencap` and the stats overlay. I fix failures and re-test until it passes, then log the work with `tt`.

## Risks
- **Private/hidden APIs**: `CGVirtualDisplay` (already), Android `injectInputEvent`/trusted virtual display (shell-only, may shift between One UI releases) — isolated behind `host/privileged/`.
- **One UI DeX** may conflict with Android desktop windowing on virtual displays → S0b decides; launcher fallback.
- **mDNS** on Samsung/mesh Wi-Fi → manual IP + QR fallback.
- **Process tap** needs macOS 14.2+ and a "System Audio Recording" permission prompt.

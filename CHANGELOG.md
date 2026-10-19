# Changelog

All notable changes to Mirage. Dates are when the version was cut.

## 2.0 — Android tablets (2026-10-19)

Mirage now works with Android tablets, in both directions, and can play the Mac's sound on the
tablet in sync with the Mac's own speakers. Built and tested on a Samsung Galaxy Tab S9 FE
(SM-X510, Android 16 / One UI 8.5).

### New

- **Android app** `in.ankitsaroj.mirage` (`android/`, Kotlin + Jetpack Compose, Android 13+).
  - **Be a display:** the tablet becomes a real extended display of the Mac (a System Settings ›
    Displays entry, not a mirror). It decodes with hardware HEVC in low-latency mode onto a
    `SurfaceView` overlay, holds a low-latency Wi-Fi lock, and runs full screen and immersive.
  - **Input from the tablet:** touch (tap, drag, long-press or two-finger tap = right click,
    two-finger scroll), S Pen hover and buttons, Book Cover and Bluetooth keyboards (full
    Android → macOS keycode map), mice and touchpads.
  - **Rotation:** turning the tablet rebuilds the Mac's virtual display in the new orientation.
  - **Stats overlay** behind the back gesture: codec, fps, bitrate, decode time, round trip,
    audio buffer and sync error.
- **Mac as a display for Android** *(experimental)*. Now verified end to end: Tab S9 FE →
  M1 Pro at 3456×2234, 60 fps HEVC, with apps opening as windows on it. A small server inside the tablet app runs
  with shell rights, started from the Mac over adb or from the tablet with Shizuku. It creates a
  trusted virtual display that One UI fills with its full DeX desktop, encodes it straight from
  the GPU surface, and injects the Mac's mouse and keyboard. The Mac side is the existing
  receiver, unchanged.
- **Synced sound, Mac → tablet** (macOS 14.2+). The Mac's system audio is captured with a Core
  Audio process tap and sent as 48 kHz PCM over UDP in Wi-Fi's voice queue. The Mac holds its
  own speakers back by the same delay, so both play together.
  - Modes: Mac + tablet in sync, tablet only, or this Mac only.
  - Measured on the test network: the tablet plays within ±1 ms of its target on the shared
    clock.
- **Adaptive audio delay.** The tablet measures its packets' p99 arrival time plus its speaker
  latency and asks for more delay when it needs it. Both speakers move together.
- **Auto screen scaling for tablets.**
  - *Crisp* (default) maps the panel 2× like a Retina MacBook, with no resampling: 2304×1440 →
    looks like 1152×720.
  - *Larger text*, *More space* and *Most space* are scaled modes, like macOS's own.
  - The choice is made per device, on the Mac or the tablet.
- **Tablets plugged in over USB appear automatically** (or paired for wireless debugging).
  The Mac watches `adb devices`, so a connected tablet with Mirage shows up as *Connected via
  USB* with no Bonjour needed. **Connect** starts *Be a display* on the tablet over adb, reads
  its Wi-Fi address and connects directly; if the two share no network, it tunnels through
  `adb forward` instead.
- **"Show its desktop here"** under *Be a display*: one click starts the tablet's
  second-desktop server over adb, aimed at this Mac. Stop, or quitting Mirage, removes it.
- **`--connect adb[:serial]`** and **`--show-android adb[:serial]`** launch arguments for
  scripts.
- The tablet's receiver listens on a **fixed port, 47800**, falling back to any free port, so
  its address stays the same between sessions.
- **Connect by address** on the Mac for networks that block Bonjour multicast. Both receivers
  show their address and port.
- **`--connect host:port` launch argument**: `open Mirage.app --args --connect 192.168.1.17:38407`.
- **Optional 120 Hz** for 120 Hz panels.
- **Host settings are saved** across launches, including each tablet's screen size.

### Protocol v2

The full spec is in [`spec/PROTOCOL.md`](spec/PROTOCOL.md).
[`spec/vectors.json`](spec/vectors.json) holds byte-exact golden values generated from the Swift
code. Both the Swift and the Kotlin test suites check against them.

- New fields in `ClientHello` (platform, DPI, scale preference, audio port, orientation). They
  are all optional, so v1 Macs keep working in both directions.
- New messages: `reconfigure`, `audioConfig`, `audioStop`. `pong` now carries the responder's
  clock, which drives audio sync and cross-device latency.
- Unknown message types are skipped instead of dropping the link.
- Audio sequence numbers never restart within a connection. They are the AES-GCM nonce, so a
  restart would have reused nonces.

### Fixed

- **Encrypted links died after about 1 second.** With a pairing code set, heartbeat pings were
  sealed by the sender but handled before decryption by the receiver. The receiver's nonce
  counter fell out of step and every later message failed to decrypt. Every message is now
  opened before it is dispatched. A soak test keeps an encrypted link alive through heartbeats.
- **Tablet screen stayed black.** The Exynos HEVC decoder accepted an unbounded operating rate
  at `configure()`, then failed `start()` with `NO_MEMORY`. Mirage now asks for a bounded rate
  and falls back to none if that fails.
- **No sound on the tablet, then sound breaking up.** Four separate causes:
  - The `AudioTrack` came up with a 160 ms buffer (One UI didn't grant the fast path). It is now
    trimmed to two mixer bursts, which cut speaker latency from 175 ms to about 50 ms.
  - When the whole path was slower than the playout delay, the player dropped the newest packet
    over and over and played nothing. It now plays late rather than not at all.
  - Clock sync trusted the first ping, which waits behind the first video keyframe and was about
    85 ms off. A much tighter sample now replaces the estimate outright.
  - A fixed 60 ms delay couldn't cover real Wi-Fi jitter. The default is now 100 ms, and the
    delay adapts.
- **The Android host server crashed when it built the display.** It created its shell Context
  lazily on the encoder thread, and `Looper.prepareMainLooper()` throws on a thread that
  already has a Looper. The Context is now built on the server's main thread at startup.
- **The Mac panel opened scrolled to the bottom.** AppKit scrolled the first focusable control
  (a slider) into view, hiding the device list.
- **Screen Recording when launched from a terminal.** The README now explains that starting
  `Mirage.app/Contents/MacOS/Mirage` directly attributes the permission to the terminal.

### Changed

- The default audio playout delay is 100 ms; the slider runs from 40 to 250 ms.
- The Mac UI says "device" where it used to say "other Mac". Tablets show an iPad icon and an
  *Android* badge.

### Tests

- `make test` adds `ProtocolV2Harness`, `GeometryHarness` and `AudioHarness`, and runs the
  Android unit tests (144) when the Android SDK is present.
- The Mac → Mac latency harness is unchanged at ~18 ms median.

## 1.1 — Latency (2026-08-10)

- End-to-end latency cut from ~1900 ms to ~19 ms median (27 ms p95) at 2880×1800, 60 fps:
  - turned off `TCP_NOPUSH`, which corked the socket;
  - moved capture → encode → send and receive → decode off the main actor;
  - added frame acknowledgements, so at most three frames are ever in flight.
- `serviceClass = .interactiveVideo`, low-latency VideoToolbox rate control, no B-frames, and
  keyframes on request.

## 1.0 — First release (2026-08-10)

- A second Mac becomes a real extended display over Wi-Fi: `CGVirtualDisplay` sized 1:1 to the
  receiving panel, captured with ScreenCaptureKit, encoded with hardware HEVC or H.264.
- A per-connection display helper process, because CoreGraphics configures only one virtual
  display per process.
- Bonjour discovery, keyboard and trackpad forwarding, and an optional pairing code (X25519 +
  AES-GCM).

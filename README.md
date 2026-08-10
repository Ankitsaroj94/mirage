<div align="center">

<img src="docs/icon.png" width="128" alt="Mirage icon">

# Mirage

**Use a second Mac as a real extended display for your main Mac, over Wi-Fi.**

Not a mirror and not a remote-desktop window — an actual entry in System Settings › Displays
that you drag windows onto, with its own space and menu bar.

</div>

---

## What it looks like

| Sending Mac — "Send my screen" | Receiving Mac — "Be a display" |
|---|---|
| <img src="docs/sender.png" width="420" alt="Sender pane"> | <img src="docs/receiver.png" width="420" alt="Receiver pane"> |

## How it works

```
   Main Mac (sending)                             Second Mac (receiving)
   ┌────────────────────────────┐                 ┌──────────────────────────┐
   │ MirageDisplayHelper        │                 │                          │
   │   └─ CGVirtualDisplay      │                 │                          │
   │        1440×900 pt         │                 │                          │
   │        2880×1800 px  ──────┼─── TCP ────────►│  AVSampleBufferDisplay   │
   │      ScreenCaptureKit      │   Bonjour       │  Layer, full screen      │
   │      VideoToolbox HEVC     │   TCP_NODELAY   │  hardware HEVC decode    │
   │◄──────────────────────── input ─────────────┤  2880×1800 px, 1:1       │
   └────────────────────────────┘                 └──────────────────────────┘
```

The sending Mac creates a virtual display sized to the receiving Mac's exact panel, captures
it, encodes it in hardware, and streams it. The receiving Mac decodes it into a borderless
full-screen window.

Built and tested on an **M1 Pro 16" (macOS 26)** sending to a **2017 Intel MacBook Pro**.

---

## Install

Grab `Mirage.dmg` from [Releases](../../releases), or build it yourself (below). The disk
image is universal — the same file installs on Apple Silicon and Intel.

**On both Macs:**

1. Drag `Mirage.app` into `Applications`.
2. Clear the quarantine flag:

   ```bash
   xattr -dr com.apple.quarantine /Applications/Mirage.app
   ```

<details>
<summary><b>Why is step 2 needed?</b></summary>

macOS tags anything arriving from another machine with `com.apple.quarantine`. On first
launch, Gatekeeper checks quarantined apps for a **notarization ticket** — proof the binary
was uploaded to Apple, scanned, and approved. Code signing alone isn't enough; notarization
needs a paid *Developer ID Application* certificate.

Mirage is signed but not notarized, so `xattr -dr` removes the tag and the check never runs.

Prefer not to use Terminal? Same result via the GUI:
- **macOS 13/14:** right-click the app → Open → Open
- **macOS 15+:** double-click, let it get blocked, then System Settings → Privacy & Security
  → **Open Anyway**. Apple removed the right-click bypass in macOS 15.

Be aware this skips Apple's malware scan for this app — reasonable for something you built
from source, not for a random download.
</details>

---

## Use

**On the Mac you want to use as the extra screen:** choose **Be a display** → **Make this Mac
available**. No permissions needed on this side.

**On your main Mac:** choose **Send my screen** → grant **Screen Recording** → **quit and
reopen Mirage** (macOS only re-reads that permission at launch) → pick the other Mac →
**Connect**.

A new display appears in System Settings › Displays. Arrange it there so it sits where the
second Mac physically is.

| Key | While connected |
|---|---|
| `esc` | Disconnect and give the second Mac its screen back |
| `s` | Toggle frame rate / bitrate / latency overlay |

**Permissions summary**

| Mac | Permission | Needed for |
|---|---|---|
| Sending | Screen Recording | Capturing the virtual display. Required. |
| Sending | Accessibility | Only if you enable "Accept keyboard and trackpad from the other Mac" |
| Sending | Local Network | Finding the other Mac (macOS 15+) |
| Receiving | *none* | — |

---

## Build from source

Needs Xcode 15 or newer on the machine you build on.

```bash
git clone https://github.com/Ankitsaroj94/mirage.git
cd mirage

make                 # build a native .app into build/
make run             # build and launch
make dmg             # universal .dmg for both Macs -> build/Mirage.dmg
make test            # protocol, codec and virtual-display checks
make icon            # regenerate the app icon
make clean
```

Build once on the Apple Silicon Mac and use `make dmg` for both machines — the 2017 MacBook
Pro tops out at macOS 13, whose Xcode is too old to compile this.

To copy straight to the other Mac over ssh, skipping the disk image entirely:

```bash
make install-remote HOST=you@othermac
```

By default the build signs with whatever *Apple Development* identity is in your keychain, so
the Screen Recording grant survives rebuilds. Override or force ad-hoc with:

```bash
make dmg SIGN_ID="Developer ID Application: Your Name (TEAMID)"
make dmg SIGN_ID=-
```

---

## Why the text is sharp

Sharpness here is almost entirely about *not resampling*, and the pipeline is built around
that one idea:

1. The receiving Mac reports its exact framebuffer — e.g. `2880×1800 px` shown as `1440×900 pt`.
2. The sending Mac builds a virtual display with precisely those numbers.
3. Capture runs at exactly `2880×1800`, encode at `2880×1800`, and the receiver renders into a
   `2880×1800` framebuffer.

Nothing scales at any stage, so glyph edges land on the pixels they were rendered on.

Two details in the private API do most of the remaining work, and both are easy to get wrong:

- **The point/pixel split is backwards from intuition.** `maxPixelsWide/High` take *pixels*;
  the mode list takes *points*; `hiDPI = 1` makes macOS synthesise the 2× pairing. Passing
  pixel dimensions in the mode — the natural reading — yields a 1×-only display and
  permanently soft text.
- **macOS attaches the display in a 1× mode and stays there.** You have to explicitly switch
  to the synthesised Retina mode with `CGConfigureDisplayWithDisplayMode`. Skipping this is the
  single most common reason home-grown versions of this look blurry.

Beyond geometry: Rec.709/sRGB is tagged identically end to end so no colour transform runs on
playback, and *Keep text sharp under load* caps the encoder's quantiser so a busy moment costs
frame rate rather than legibility.

## Why there's a helper process

**CoreGraphics only lets a process configure one `CGVirtualDisplay`.** The first one created
works; every one after it attaches to the arrangement but reports an empty mode list — no
matter the geometry, the EDID identity, or whether the earlier one was torn down first.
Verified on macOS 26.5 (see `Tests/DisplayHarness`).

Done in-process, this would have worked perfectly on the first connection and silently
produced a non-Retina display on every one after. So each connection spawns
`MirageDisplayHelper`, which owns exactly one display and does nothing else. Quitting it
removes the display cleanly, and the next connection gets a fresh process. Its stdin is a pipe
from the app, so if the app dies the helper sees EOF and takes the display down with it — no
orphaned phantom monitors.

## Latency

- `TCP_NODELAY`, plus `serviceClass = .interactiveVideo` so Wi-Fi puts the stream in the video
  access category ahead of bulk traffic.
- VideoToolbox low-latency rate control with B-frames disabled, so no frame waits on a future
  frame.
- Long GOP with keyframes on request rather than on a timer — bitrate goes to pixels that
  changed.
- Frames are presented with `kCMSampleAttachmentKey_DisplayImmediately`, bypassing the display
  layer's presentation queue.
- Backpressure: if the socket hasn't drained, the next frame is dropped rather than encoded,
  keeping the picture current instead of letting it fall behind and stay behind. Sustained
  congestion walks the bitrate down (AIMD); it creeps back up when the link clears.

ScreenCaptureKit only delivers a frame when the screen actually changes, so a static page of
text costs almost nothing — and legitimately reads as `0 fps` in the overlay.

## Security

Leave the pairing code off on a trusted network and the stream is plain TCP — lowest overhead.
Turn it on and the link does an X25519 key exchange authenticated by an HMAC over the code,
then AES-GCM per message with the header as associated data. Ephemeral keys mean a captured
session stays unreadable even if the code later leaks; a wrong code fails the HMAC without
either side revealing it.

This is deliberately *not* TLS. Network.framework cannot do TLS-PSK — no PSK ciphersuite
appears in `tls_ciphersuite_t`, and TLS 1.3 external PSK is rejected by `NWListener` (all
variants tested). Doing it at the application layer keeps it testable.

---

## Tests

`make test` needs no permissions and takes a few seconds:

| Harness | Checks |
|---|---|
| `Tests/Harness` | framing, packed structs, 400 KB messages, encrypted and wrong-code paths over loopback |
| `Tests/CodecHarness` | 2880×1800 encode → wire bytes → decode, PSNR verified, hardware decode confirmed |
| `Tests/DisplayHarness` | the helper creates a true Retina display at three geometries and removes it on exit |

## Layout

```
Sources/MirageBridge/          ObjC shim over the private CGVirtualDisplay classes
Sources/MirageDisplayHelper/   owns one virtual display, one process
Sources/Mirage/
  Core/      wire protocol, TCP link, Bonjour discovery, encryption
  Host/      virtual display, ScreenCaptureKit, VideoToolbox, input injection
  Client/    decode, present, input forwarding
  UI/        control panel, full-screen receiver window
Tools/make-icon.swift          the app icon, drawn in code
```

## Troubleshooting

**The other Mac never shows up in the list.** Check that both are on the same network, then
run `dns-sd -B _mirage._tcp local.` on the sending Mac. If nothing appears — and especially if
`dns-sd -B _companion-link._tcp local.` also shows no other Macs — your router is filtering
multicast between clients. Plenty of mesh and guest networks do this. Normal traffic still
flows; only discovery breaks.

**Text looks soft.** The app warns when the virtual display didn't take a Retina mode.
Disconnect and reconnect; if it persists, check the receiving Mac isn't running a non-integer
scaled resolution.

**Connected but nothing on screen.** Press `s` on the receiving Mac for live stats. Detailed
logs: `log stream --predicate 'subsystem == "com.voxturr.mirage"'`.

## Known limits

- `CGVirtualDisplay` is a private API. Apple could change it in any macOS release; the app
  reports that clearly rather than failing silently, and the shim is isolated in one file.
- Not sandboxable and not App Store distributable, for the same reason.
- The 2017 MacBook Pro tops out at macOS 13, which is why the deployment target is 13.0 and the
  receiver uses `AVSampleBufferDisplayLayer` rather than the newer renderer.
- No audio — this is a display, not a full remote desktop.
- *Direct Wi-Fi* (AWDL peer-to-peer) works without a router but is markedly slower than both
  Macs on the same 5 GHz network. Prefer infrastructure Wi-Fi.

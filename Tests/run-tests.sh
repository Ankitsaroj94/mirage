#!/bin/bash
#
# Runs the three harnesses. None of them need Screen Recording permission.
#
#   1. protocol  — framing, packed structs, encrypted + wrong-code paths over loopback
#   2. codec     — 2880x1800 encode -> wire bytes -> decode, PSNR checked
#   3. display   — the helper process actually creates a Retina virtual display
#
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
FAILED=0

step() { printf "\n\033[1m── %s ──\033[0m\n" "$1"; }
check() { if [ "$1" -ne 0 ]; then echo "  FAILED"; FAILED=1; fi; }

CORE="Sources/Mirage/Core/Protocol.swift Sources/Mirage/Core/PeerLink.swift Sources/Mirage/Core/SecureChannel.swift Sources/Mirage/Support/Log.swift"

step "1/3  protocol"
swiftc -O $CORE Tests/Harness/main.swift -o "$TMP/linktest" 2>&1 | grep -E "error:" && FAILED=1
"$TMP/linktest";              check $?
"$TMP/linktest" --psk;        check $?
"$TMP/linktest" --psk --wrong-code; check $?

step "2/3  codec"
swiftc -O Sources/Mirage/Core/Protocol.swift Sources/Mirage/Support/Log.swift \
          Sources/Mirage/Host/VideoEncoder.swift Sources/Mirage/Client/VideoRenderer.swift \
          Tests/CodecHarness/main.swift -o "$TMP/codectest" 2>&1 | grep -E "error:" && FAILED=1
"$TMP/codectest"; check $?

step "3/3  virtual display (via helper process)"
swift build -c release >/dev/null 2>&1
BIN=$(swift build -c release --show-bin-path)
MIRAGE_HELPER="$BIN/MirageDisplayHelper" swiftc -O \
  Sources/Mirage/Support/Log.swift Tests/DisplayHarness/main.swift -o "$TMP/displaytest" 2>&1 | grep -E "error:" && FAILED=1
MIRAGE_HELPER="$BIN/MirageDisplayHelper" "$TMP/displaytest"; check $?

if [ "$FAILED" -eq 0 ]; then
  printf "\n\033[32mALL TESTS PASSED\033[0m\n"
else
  printf "\n\033[31mSOME TESTS FAILED\033[0m\n"
fi
exit $FAILED

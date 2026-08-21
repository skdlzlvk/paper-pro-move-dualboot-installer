# Paper Home source

Paper Home is a clean-room, E Ink-first Android HOME and note integration layer.
It provides:

- a white, animation-minimal launcher;
- installed-app and Wi-Fi views;
- a multilingual recommended-app catalog that opens installed apps or their
  official Play Store/Aurora pages, plus the official KOReader release page;
- E Ink refresh controls;
- five selectable E Ink lock-screen styles: faded current page, minimally
  covered reading page, clean, clock, and classic standby;
- a global top-edge swipe panel for direct reading-light adjustment;
- English, Korean, and Simplified Chinese strings;
- a permission-gated first-boot receiver for applying the installer-selected
  Android system locale through Android's persistent configuration API;
- a notebook library and templates; and
- an immediate-ink control path used by the device-side display bridge.

This directory is not a standalone Gradle project. It targets a matching AOSP
Launcher3 platform build and privileged system permissions. The AOSP Launcher3
base, Android SDK/platform files, signing keys, APKs, and device system image are
not included.

## Lock-screen privacy and wake behavior

The default lock style uses the display bridge's already-composited in-memory
panel frame, adds a translucent white veil and a vector lock badge, and submits
that result directly to the E Ink panel. It does not create a screenshot or
persist reader content. Reading and Clock retain the same in-memory frame;
Clean and Classic replace it with a generated page. If the retained page has
real chromatic content and automatic color is enabled, its color is preserved.
Wake still waits for the first live post-wake Android frame before performing
one complete restore, so a stale lock frame is not presented as an unlocked
application.

## Android update client

`UpdateActivity` and `OtaUpdateClient` implement owner-approved signed release
checking, HTTPS download, exact-size/SHA-256 verification, and atomic `.ready`
staging. The checked-in endpoint and trust-key resources are empty, so public
source builds fail closed until a release overlay supplies a production public
key and feed URL. This client never writes a partition or enables an install
reboot by itself.

For a standalone API 36 build against an existing AOSP tree:

```sh
PAPER_HOME_AOSP=/path/to/android-16-source \
  ./build-api36.sh /tmp/paper-home-build
```

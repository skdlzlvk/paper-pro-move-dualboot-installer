# Paper Home source

Paper Home is a clean-room, E Ink-first Android HOME and note integration layer.
It provides:

- a white, animation-minimal launcher;
- installed-app and Wi-Fi views;
- E Ink refresh controls;
- English and Korean strings;
- a notebook library and templates; and
- an immediate-ink control path used by the device-side display bridge.

This directory is not a standalone Gradle project. It targets a matching AOSP
Launcher3 platform build and privileged system permissions. The AOSP Launcher3
base, Android SDK/platform files, signing keys, APKs, and device system image are
not included.

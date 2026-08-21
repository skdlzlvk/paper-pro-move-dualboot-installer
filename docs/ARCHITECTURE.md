# Architecture

This document describes the Android runtime that is published in this
repository — the code that runs on the tablet once the Android slot boots.

The Windows installer that puts it there is a separate, unpublished product and
is deliberately out of scope here.

## Boot layout

The tablet keeps stock reMarkable OS in its known-good boot path and uses the
other A/B root slot for Android. An early selector lets the user choose stock or
Android before either user interface starts, and a reboot always returns to
stock.

```text
Power on
   |
   v
Early touch selector           <- src/boot
   |----------------------|
   v                      v
Stock reMarkable OS       Android slot
                          |
                          v
                 host integration runtime      <- src/runtime
                          |
             +------------+------------+
             |            |            |
             v            v            v
          Android       input relay   E Ink bridge
             |            ^ src/input   ^ src/display
             v
        Paper Home / apps                      <- src/paper-home
```

## Runtime components

### `rm-boot-selector` — `src/boot`

A Qt application shown before either operating system's user interface starts.
It reads the current slot state, offers stock or Android, and defaults back to
stock so that an unattended power-on never lands somewhere unexpected.

### `rm-android-init` — `src/runtime`

Runs as PID 1 in the Android slot. It creates the mount and process environment
used to launch local Android, prepares the Android data volume, configures
diagnostic USB networking, and coordinates healthy-boot and fallback state.

### `rm-touch-relay` — `src/input`

Reads the physical Elan touch device, filters palm-like contacts, and forwards
touch events through an Android-facing uinput device. It publishes a tiny
contact-state marker only when a forwarded non-palm finger goes down or up;
motion reports never write files.

The Marker digitiser and the finger digitiser use separate input device
configurations so that pen pressure and palm rejection stay independent of
touch.

### `rm-native-controls` — `src/runtime`

Keeps device-owned controls outside Android's PID namespace. It coordinates the
front light, lock state, Marker state, safe long-idle power-off, refresh
requests, and stock-boot requests. Android asks for these through small control
files rather than being given direct hardware access.

### `rm-epd-bridge` — `src/display`

The display bridge submits Android frames and the immediate pen overlay through
the device's existing E Ink library. Its clean-room source and the exact
MIT-licensed Oxide interface header used to compile it are published here. The
device-owned `libqsgepaper.so` remains on the user's tablet and is resolved
there at runtime; it is not redistributed by this project.

Behaviour worth knowing about:

- During finger interaction the bridge temporarily selects an 18 ms fast
  monochrome path. After 520 ms without touch it repaints the retained frame
  using the user-selected profile, so a moment of scrolling does not leave the
  screen in a permanently low-quality mode.
- Display profiles map to the panel's own screen modes (pen, mono, animate,
  grayscale, content, full) rather than inventing a private waveform set.
- A text-contrast curve can darken thin antialiased glyphs without touching
  images, because E Ink quantisation tends to wash out light grey text.
- A reader policy watches for a foreground reading app by package identity
  only — never by reading window contents — and issues a full-panel refresh
  every N pages to clear accumulated ghosting.

### Paper Home — `src/paper-home`

An animation-minimal launcher with Wi-Fi controls, an app library, E Ink display
controls and a clean-room note interface. Paper Home writes small control files
that the host integration layer consumes.

A privileged 18 dp top-edge gesture target opens a static reading-light shade
above any app; its quantized slider updates both Android's brightness setting
and the host request file, so the existing drift-recovery loop remains the
hardware authority.

## Kernel

`src/kernel` holds the configuration for the Android slot's kernel: Linux
6.12.49 built from reMarkable's own published source tarball with configuration
changes only (binder, loop, netfilter). The written GPL-2.0 corresponding-source
offer, the exact tarball hash and the build recipe are in
[KERNEL-SOURCE.md](KERNEL-SOURCE.md).

No kernel C source is modified by this project. If you want to reproduce the
image, you take reMarkable's tarball and this configuration.

## Data boundary

Stock and Android application data are separate. This project does not import
or redistribute the stock note database or the stock note application, and
nothing in this repository reads stock user content.

## What is not here

The Windows host tooling, the on-device install and removal scripts, the
partition writer, the Android root filesystem build recipe, the update guard and
the packaging tools are not part of this repository. They are a separately
authored product; see [LEGAL-NOTICE.md](LEGAL-NOTICE.md).

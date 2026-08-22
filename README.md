# Paper Move — Android 16 for the reMarkable Paper Pro Move

[![Sponsor this project](https://img.shields.io/badge/Sponsor-GitHub%20Sponsors-EA4AAA?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/skdlzlvk)

A local, E Ink-optimized **Android 16** that runs on a reMarkable Paper Pro Move
while **stock reMarkable OS stays installed and remains the default boot
option**.

Android runs on the device itself. This is not VNC, screen mirroring or a remote
desktop.

![Paper Home running locally on the Paper Pro Move](media/paper-home.jpg)

## What is in this repository

**The Android runtime — the code that runs on the tablet.** It is GPL-3.0, and
it is here so that anyone can read it, audit it, build on it, or port it.

| Path | What it is |
| --- | --- |
| `src/display/` | Native E Ink bridge: screen modes, dirty tiles, pen overlay, text contrast, reader page refresh |
| `src/paper-home/` | Clean-room E Ink launcher, note app, Wi-Fi and display settings |
| `src/runtime/` | Android host init (PID 1) and device-owned controls: front light, lock, Marker, stock-boot request |
| `src/input/` | Elan touch relay with palm rejection, Marker and finger input configurations |
| `src/boot/` | Early boot selector shown before either OS starts |
| `src/kernel/` | Kernel configuration for the Android slot (GPL-2.0 source offer) |
| `docs/` | Architecture, compatibility, kernel source offer, legal boundary |

Start with **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**, or read
[`src/display/rm-epd-bridge.cpp`](src/display/rm-epd-bridge.cpp) if you only
care about the E Ink part.

## What is not in this repository

**The Windows installer is not here, and is not open source.** The host
tooling, the on-device install and removal scripts, the partition backup and
restore writer, the root filesystem build recipe and the packaging tools are a
separately authored proprietary product.

That is a deliberate split, so it is worth stating plainly rather than letting
you find out by clicking:

- the integration layer that runs on your tablet is open and GPL-3.0: the E Ink
  display bridge, the launcher and note app, the host integration runtime, the
  touch and Marker relay, the boot selector and the kernel configuration;
- the Android base underneath it is **not** built here. It is reDroid's
  published Android 16 arm64-only image, altered by exactly one same-length
  line in `init.rc` with the input and output hashes pinned in the build tool.
  There is no AOSP source tree in this repository, and no device vendor image
  is involved at all: graphics go through reDroid's own gralloc and hwcomposer
  with the E Ink bridge behind them, not a vendor HAL;
- the part that writes to your tablet's partitions is maintained, tested and
  distributed by one person, and is not published.

Calling the whole tablet side "auditable" was too strong, and that wording was
corrected on 2026-08-22 after an AOSP developer pointed it out publicly. The
integration layer is auditable. The Android base is a pinned third-party image,
and the honest claim is reproducibility, not authorship.

This repository alone will not install anything. There is no payload here — no
Android root filesystem, no kernel binary, no installer.

## How it works

The tablet has two system partitions. Stock reMarkable OS 3.27.3.0 stays on
`root_a` and remains the default: Android is installed onto the inactive
`root_b`, and every reboot returns to stock unless you explicitly pick Android
from the app menu. Both bootloaders, the partition table and your documents are
never touched.

The installer adds one thing to the stock partition: a systemd unit,
`/etc/systemd/system/paper-xovi.service`, and the symlink that enables it. After
the encrypted home volume is mounted it runs Xovi's own `start` script, which is
what puts the Android entry in the app menu. Without it the entry disappears at
the next reboot and you would need a PC to get back into Android.

The unit deliberately runs *after* the stock UI rather than before it. reMarkable
has its own boot watchdog that reboots the tablet when the stock UI is late, and
three of those hand the active slot to the other partition — so nothing here is
allowed to delay stock. No stock file is replaced or patched, the boot order is
unchanged, nothing depends on the unit, and removal deletes it.

Removal restores a complete, hash-verified 4 GiB backup of `root_b` that is
required before anything is installed, and leaves the tablet as plain stock.

## Status

One complete cycle has been rehearsed on the developer's own tablet
(2026-08-22): install, Android cold boot with touch, Marker, navigation bar,
Wi-Fi and reader page refresh, return to stock with the on-screen button, and
removal with a hash-verified partition restore.

Official USB recovery is not theoretical either: a boot loop bricked the
developer's own Move on 2026-08-20 and reMarkable's official recovery brought it
back with the bootloader undamaged. Recovery clears Developer Mode and returns
the tablet to an older stock version.

> [!CAUTION]
> Only install on a device you are prepared to restore yourself with
> reMarkable's official recovery tool.

What is **not** established: repeated stock OTA cycles, multi-day battery
(Android standby is worse than stock — treat it as something you boot into to
read), verification on a second host PC, an independent review of the
partition-writing code, and reading-app DRM behaviour. Details in
[docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

## Getting the installer

**US$15, and the build you download keeps working.** There is no licence key,
no activation and no phone-home, so cancelling cannot touch a tablet you have
already set up: <https://github.com/sponsors/skdlzlvk>

It is a *monthly* tier for one boring reason. GitHub will only attach the
private download repository to a monthly tier, so that is the only way you get
access the moment you sponsor instead of waiting for me to invite you by hand.
Sponsor, download, cancel — that is a one-time US$15 and nothing recurring.
Come back and sponsor again whenever there is an update you actually want.

Staying subscribed is worth it only if you would rather not think about it:
every stock firmware release can break the install path, and keeping up with
that is the ongoing work this funds.

Sponsorship does not affect anyone's rights to the GPL-3.0 code in this
repository, and the GPL-2.0 kernel source offer in
[docs/KERNEL-SOURCE.md](docs/KERNEL-SOURCE.md) is honoured for anyone who
receives a binary, sponsor or not.

## Licensing

The Android runtime here is **GPL-3.0** ([LICENSE](LICENSE)).

The Android slot's kernel is Linux 6.12.49 built from reMarkable's own published
source tarball with configuration changes only. Offer, hash and recipe:
[docs/KERNEL-SOURCE.md](docs/KERNEL-SOURCE.md).

The full boundary — what is published, what is not, and why the two licenses do
not conflict — is in [docs/LEGAL-NOTICE.md](docs/LEGAL-NOTICE.md).

Third-party components: [THIRD_PARTY-NOTICES.md](THIRD_PARTY-NOTICES.md).

## Acknowledgements

Thanks to Brinly Taylor for reverse-engineering guidance and documentation about
the reMarkable E Ink display pipeline. The implementation here is independently
written; private notes, dumps and proprietary binaries are not redistributed.

---

This is an independent community project. It is not affiliated with, sponsored
by, or endorsed by reMarkable. Product names are used only to identify
compatibility.

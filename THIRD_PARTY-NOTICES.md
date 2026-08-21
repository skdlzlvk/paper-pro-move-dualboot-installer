# Third-party notices

This repository intentionally excludes third-party binaries and proprietary
device payloads.

## Oxide E Ink interface declaration

`src/display/third_party/oxide/epframebuffer.h` is copied from the
[Oxide project](https://github.com/Eeems-Org/oxide) at commit
`bf975bf7614f5ca23796d96b10d262715c5283a1`. Oxide is MIT licensed; the exact
license text is preserved beside the header. The interface declaration models
the tablet-owned E Ink library but does not include that library.

## Linux kernel (root B, GPL-2.0-only)

The clean-install stage includes a Linux 6.12.49 kernel image and module tree
built from reMarkable's published source tarball
`linux-imx-rel-5.7-wd-3.27.2.1-f21cbcc9ed9a.tar.gz` (repository
`reMarkable/linux-imx-rm`, branch `rmpp_6.12.49_v3.27.x`, SHA-256
`ad8ab17a897bb35ecec877f6b3e7e766a534ce25e46766991f7ec92b276e583f`) with the
project configuration in
`src/kernel/chiappa-6.12.49-redroid-netfilter.config` (binder and binderfs
built in, loop and netfilter modules). A file-by-file comparison on 2026-08-22
found no source difference from the published tarball — the kernel differs by
configuration only. The kernel is licensed under the GNU General Public License
version 2 only; `docs/KERNEL-SOURCE.md` is the written offer of corresponding
source and carries that evidence and the exact build recipe.

## Android Open Source Project

`src/paper-home` is an independently developed integration layer that targets
the AOSP Launcher3 package and build environment. A matching AOSP source tree is
not included here. AOSP components obtained separately remain subject to their
own licenses, including the Apache License 2.0 where applicable.

## Product and service names

reMarkable, Android, Google Play, Kindle, Libby, RIDI, and other names may be
trademarks of their respective owners. They are referenced only for
compatibility, testing, and identification.

## Device-side libraries

The prototype uses libraries already present on the user's device to drive the
E Ink hardware. Those proprietary binaries are not distributed by this
repository. The compatible MIT-licensed interface declaration used to build
against them is included with attribution above.

## Optional stock UI launcher integration

The installer can acquire the official `rm-xovi-extensions` and `rm-appload`
release archives directly from their upstream GitHub release pages. They are
not copied from a private tablet backup. Both projects are distributed under
GPL-3.0-only. Their exact release tags, commits, byte sizes, URLs, and SHA-256
digests are pinned in `third-party/boot-launch-dependencies.lock.json`.

Xovi remains tethered by design: this project does not create a persistent
`xochitl.service` preload override. After a normal restart the tablet returns
to stock behavior unless the user explicitly starts Xovi again. This follows
the upstream warning against automatic boot-time injection.

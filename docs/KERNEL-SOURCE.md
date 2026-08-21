# Project kernel: corresponding source (GPL-2.0-only)

The clean-install stage carries a Linux kernel image for root B
(`android-kernel.fitImage`, installed as
`/boot/fitImage.ahab-6.12.49+git+f21cbcc9ed9a-redroid-netfilter` and selected
through root B's own `/boot/fitImage.ahab` symlink) plus its module tree
(`android-kernel-modules.tar.gz`) and `loop.ko`. Stock reMarkable OS 3.27.3.0
ships no binder and no loop module, so Android cannot start on the stock
kernel (physical finding #7, 2026-08-21).

The kernel is licensed GPL-2.0-only, so whoever distributes that binary must
offer its corresponding source. This document is that offer.

## Published source

reMarkable publishes the Paper Pro Move kernel as a source tarball tracked with
git LFS — not as a commit history. `f21cbcc9ed9a` in the image name is part of
reMarkable's tarball name, not a commit id in the public repository.

| Item | Value |
| --- | --- |
| Repository | `https://github.com/reMarkable/linux-imx-rm` |
| Branch | `rmpp_6.12.49_v3.27.x` |
| Commit | `9d137d6c6b1f92d9d756ac4c442943933a9d7f1b` ("reMarkable Paper Pro / Pure 3.27.2.1", 2026-05-26) |
| File | `linux-imx-rel-5.7-wd-3.27.2.1-f21cbcc9ed9a.tar.gz` (git LFS) |
| Size | 259,818,958 bytes |
| SHA-256 | `ad8ab17a897bb35ecec877f6b3e7e766a534ce25e46766991f7ec92b276e583f` |
| Base version | Linux 6.12.49 |

## Verification (2026-08-22)

The published tarball was downloaded fresh, extracted, and compared file by
file against the tree the shipped kernel was built from:

```
differing files: 0 | only in published source: 0 | only in build tree: 15176
RESULT=SOURCE_IDENTICAL
```

The 15,176 build-tree-only entries are build output (`*.o`, `*.cmd`,
`include/generated`, `vmlinux`, module objects). **No source file was added,
removed or modified**: this project's kernel differs from reMarkable's
published source by configuration only.

## Project configuration

Base configuration is reMarkable's own `arch/arm64/configs/chiappa_defconfig`
(402 lines) from the same tarball. The complete `.config` used for the shipped
image is published here:

[`src/kernel/chiappa-6.12.49-redroid-netfilter.config`](../src/kernel/chiappa-6.12.49-redroid-netfilter.config)
(SHA-256 `1218656daa6969a636f5392492754d6f0169a55c6b28170f5ba40163f361792c`)

The options this project adds on top of the stock configuration are the ones
Android needs and stock reMarkable OS does not build:

- `CONFIG_ANDROID_BINDER_IPC=y`, `CONFIG_ANDROID_BINDERFS=y` — binder, built in
- `CONFIG_BLK_DEV_LOOP=m` — loop devices for the Android image mounts
- `CONFIG_NETFILTER_XTABLES=m` and the xt match/target modules Android networking expects
- `CONFIG_LOCALVERSION="+git+f21cbcc9ed9a"` (kept from stock so the module path matches)

## Build and package

Toolchain: the official Chiappa SDK
(`remarkable-production-image-5.7.119-chiappa-public-x86_64-toolchain.sh`).
reMarkable's own README in the repository above documents the recipe; for this
device it is:

```shell
tar xzf linux-imx-rel-5.7-wd-3.27.2.1-f21cbcc9ed9a.tar.gz
cd linux-imx-rel-5.7-wd-3.27.2.1-f21cbcc9ed9a
. /opt/codex/chiappa/<version>/environment-setup-cortexa55-remarkable-linux
make chiappa_defconfig          # then apply the options listed above
make -j"$(nproc)"
make INSTALL_MOD_STRIP=1 INSTALL_MOD_PATH=./output modules_install
mkimage -f chiappa.its fitImage
```

`chiappa.its` ships inside the same tarball. The resulting `fitImage` is the
staged `android-kernel.fitImage`
(SHA-256 `d30b654295a0e3c68442188d2e5b30a9c0406f0e46e2cdc899a6e548c87b0092`) and
`output/lib/modules` is `android-kernel-modules.tar.gz`.

Anyone receiving the binary may obtain the identical source from the
repository, branch and file named above, or request it from the maintainer.

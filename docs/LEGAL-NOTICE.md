# Project and distribution notice

This document describes the project boundary. It is not legal advice.

## What this repository publishes

This repository contains the **Android runtime** for the reMarkable Paper Pro
Move: the display bridge, the launcher and note application, the host
integration runtime, the input relay, the early boot selector, the kernel
configuration, and the documentation needed to read and audit them.

It is licensed under **GPL-3.0** ([LICENSE](../LICENSE)), so anyone may study,
modify and redistribute it under those terms.

## What this repository does not publish

The Windows one-click installer is a **separately authored proprietary
product** and is not in this repository. That includes the Windows host
tooling, the on-device install and removal scripts, the partition backup and
restore writer, the Android root filesystem build recipe, the update guard, and
the packaging and release tools.

The installer is a separate program. It does not include, link against, or
relicense the GPL-3.0 runtime published here; it operates on it as a separate
process, the way an archiver operates on a file. Publishing the runtime under
GPL-3.0 therefore does not place the installer under GPL-3.0.

Revisions previously published under GPL-3.0 remain GPL-3.0. Nothing here
attempts to withdraw rights from a copy someone already holds.

## Explicit exclusions

This project does not distribute or sell:

- reMarkable OS, or a modified reMarkable OS image;
- reMarkable's proprietary note application, cloud code, or user database;
- device vendor firmware or proprietary device libraries;
- Google Mobile Services or third-party application packages;
- a premodified tablet; or
- user credentials, backups, or personal content.

The installer operates on the owner's own device and uses only inputs the user
is legally entitled to use. Any third-party software must be obtained
separately through its own authorized channel.

## Kernel source

The Android slot runs a Linux 6.12.49 kernel built from reMarkable's published
source with configuration changes only. The written GPL-2.0
corresponding-source offer, the tarball hash and the build recipe are in
[KERNEL-SOURCE.md](KERNEL-SOURCE.md). The offer is honoured for anyone who
receives a binary, whether or not they sponsor the project.

## Sponsorship

US$15 is treated as a one-time purchase of the maintained prebuilt Windows
installer for the Paper Pro Move. It includes verified release metadata and
future compatibility fixes required by stock reMarkable OS updates for that
device.

The checkout currently uses a monthly GitHub Sponsors tier only because GitHub
can automatically attach a private download repository to a recurring tier.
The purchaser may cancel after the first month. Cancelling does not end
eligibility for covered Paper Pro Move compatibility updates and does not
disable a package already downloaded.

GitHub itself removes private-repository access after cancellation, so update
delivery is being moved to a separate entitlement system that records the
original purchase. Continued monthly sponsorship is optional support for
research, testing hardware, recovery tooling, documentation and additional
device work.

Sponsorship is not an exclusive software license, ownership of the project, a
guaranteed release date, or a guarantee against device failure. It does not
remove or limit anyone's rights under GPL-3.0 or GPL-2.0 for the components
covered by those licenses.

## Independence

This is an independent community project and is not affiliated with, sponsored
by, or endorsed by reMarkable. Compatibility references do not imply
endorsement.

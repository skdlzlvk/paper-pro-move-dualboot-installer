# Security policy

## Supported versions

The installer is in beta and its evidence comes from a single developer tablet,
so no version carries a support guarantee yet. This repository holds the Android
runtime source; report issues against the current revision of `main`.

## Reporting a vulnerability

Do not publish credentials, device-identifying data, a complete backup, or an
exploit containing user data in a public issue.

Use GitHub's private vulnerability reporting feature when it is available for
this repository. Otherwise, open a minimal issue asking for a private contact
path without including the sensitive details.

## High-risk areas

- boot/root partition target selection;
- backup and recovery validation;
- OTA slot handling;
- privileged Android services;
- USB/SSH/ADB exposure;
- update/download integrity; and
- installer rollback after interruption.

No release should accept an unsigned payload or select a partition by a
hard-coded block-device number alone.

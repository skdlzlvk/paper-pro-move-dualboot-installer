# Contributing

This project can make a device unbootable if unsafe assumptions reach a release.
Contributions are welcome, but recovery and provenance take priority over speed.

## Before opening a pull request

1. Do not add reMarkable OS files, proprietary applications, vendor firmware,
   Android system images, GMS, APKs, signing keys, device backups, or user data.
2. Do not add commands that write a partition without an explicit target check,
   battery check, unmounted-target check, backup check, and hash verification.
3. Make every install/remove/recover operation resumable or safely repeatable.
4. Keep stock OS recovery available.
5. Document the exact hardware and software version used for testing.
6. Include a rollback procedure for any state-changing change.

## Issue reports

Include:

- device model and stock OS version;
- current boot choice;
- the exact command or UI action;
- expected and observed behavior;
- sanitized logs; and
- whether stock OS still boots.

Never post SSH passwords, Wi-Fi credentials, account tokens, serial numbers,
personal documents, or full device backups.

## Code style

- C/C++: keep warnings enabled and avoid implicit target selection.
- Shell: use `set -eu`, quote expansions, and check every destructive target.
- PowerShell: use strict mode and `-LiteralPath` for filesystem operations.
- Android: avoid animations and continuously invalidated surfaces on E Ink.

## Licensing

By submitting a contribution, you agree that your contribution is licensed
under GPL-3.0 unless the file or directory explicitly documents another
compatible license.

# Safety model

Dual-boot installation touches boot-critical storage. The public installer is
not ready until all controls below are implemented and tested.

## Required preconditions

- exact Paper Pro Move model match;
- supported stock OS version;
- known active stock slot and boot partition;
- inactive target confirmed by at least two independent identifiers;
- target not mounted;
- battery above the release threshold and external power recommended;
- enough free host storage for a full backup;
- stable USB connection; and
- verified recovery assets.

## Backup requirements

A backup is not considered valid until:

- its byte size matches the expected partition size;
- SHA-256 is recorded;
- the compressed archive passes an integrity test;
- critical files can be read from a read-only verification mount; and
- the recovery workflow can locate the backup without the original installer
  session.

## Write requirements

- never derive a target solely from `/dev/mmcblk*` numbering;
- require an explicit supported partition label/slot relationship;
- journal the intended source hash and target before writing;
- refuse to continue after an unexpected disconnect;
- verify the written target by hash or block comparison; and
- preserve a stock-boot fallback until Android reaches a known healthy state.

## OTA requirements

The current guard blocks an update that would overwrite the Android slot. A
public release must instead provide a controlled workflow:

1. back up Android and its boot integration;
2. restore the inactive slot to stock;
3. allow the stock updater to complete;
4. validate the new stock slot layout;
5. restore Android to the newly inactive slot;
6. reinstall the guard/fallback; and
7. boot-test stock and Android.

Two consecutive real-device cycles are required before public beta.

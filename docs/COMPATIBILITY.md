# Device and stock OS compatibility

The Android runtime in this repository targets exactly one device and one stock
OS version:

| Device | Device-tree identity | Stock OS |
| --- | --- | --- |
| reMarkable Paper Pro Move | `reMarkable Chiappa` | `3.27.3.0` |

Everything else fails closed, including beta firmware that looks similar.
Hardware identity, partition layout and stock version are checked before
anything is written, and a version is added only after its exact device state,
partition layout, read-only filesystem checks, backup path, boot return and
recovery evidence have been reviewed on real hardware.

Support for another reMarkable model needs its own hardware policy and its own
physical test evidence. The Paper Pro is the next target; it has not been
tested.

## Evidence

Evidence comes from a single development Paper Pro Move. One complete cycle has
been rehearsed on it: install, Android cold boot with touch, Marker, navigation,
Wi-Fi and reader page refresh, return to stock, and removal with a hash-verified
partition restore that left the tablet as plain stock.

Not established yet:

- repeated stock OTA cycles with Android installed;
- multi-day battery figures — Android standby is worse than stock;
- an independent review of the partition-writing code;
- verification on a second host PC;
- reading-app account, DRM and long-session behaviour.

Only install on a tablet you are prepared to restore yourself with reMarkable's
official recovery tool.

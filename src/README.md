# Source snapshot

This directory contains only files copied through the public allowlist.

## Included

- `runtime/` — independently developed Android launch and device-control logic
- `input/` — touch relay and Android input device configuration
- `ota/` — experimental stock updater guard and status validation
- `paper-home/` — clean-room E Ink launcher/note integration layer

## Not included yet

- device/vendor libraries or firmware;
- Android system images;
- binary builds;
- E Ink bridge and early selector sources that still depend on the unpublished
  build-header boundary;
- production partition writer; and
- test keys or application packages.

The snapshot documents working prototype logic but is not an end-user release.

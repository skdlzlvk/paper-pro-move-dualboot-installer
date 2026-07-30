# Release status

Last public status update: 2026-07-30

## Validated on the development device

- local Android 12 / ARM64 boot;
- stock OS remains selectable;
- healthy-boot commit and stock fallback;
- touch, palm rejection, and Marker state;
- Paper Home low-latency native ink;
- system Wi-Fi and internet validation;
- front light, power button, lock, and wake;
- manual E Ink refresh and speed/quality modes;
- fast monochrome interaction and Gallery 3 color settling;
- English and Korean boot/standby/Paper Home interfaces;
- Play Store, Aurora, RIDI, and Series startup;
- Kindle and Libby cold-start without a fatal error; and
- an OTA guard that prevents immediate inactive-slot overwrite.

## Partially validated

- battery optimizations are active, but the public measurement table is not
  complete;
- Kindle and Libby open, but account/DRM/download/offline workflows are not
  verified;
- OTA overwrite prevention works, but update/backup/restore automation does not;
- source code is being separated, but not every build dependency is public.

## Not released

- one-click install;
- one-click uninstall;
- emergency recovery;
- Android system image or binary payload;
- end-user beta package; and
- compatibility support for other reMarkable models.

## Public source snapshot

Included now:

- local Android init/runtime source;
- touch relay source;
- host control and OTA-guard scripts;
- Paper Home custom integration source;
- Windows read-only preflight; and
- architecture and safety documentation.

Withheld pending cleanup:

- E Ink bridge and early boot selector build boundary;
- device/vendor binaries and system images;
- production installer partition writer;
- signed public release payload; and
- automated OTA restore implementation.

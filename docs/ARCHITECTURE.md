# Architecture

## Boot layout

The tested prototype keeps stock reMarkable OS in its known-good boot path and
uses the other A/B root slot for Android. An early selector lets the user choose
stock or Android before either user interface starts.

```text
Power on
   |
   v
Early touch selector
   |----------------------|
   v                      v
Stock reMarkable OS       Android slot
                          |
                          v
                 host integration runtime
                          |
             +------------+------------+
             |            |            |
             v            v            v
          Android       input relay   E Ink bridge
             |                         |
             v                         v
        Paper Home / apps          device display
```

## Runtime components

### `rm-android-init`

Creates the mount and process environment used to launch local Android, prepares
the Android data volume, configures diagnostic USB networking, and coordinates
healthy-boot/fallback state.

### `rm-touch-relay`

Reads the physical Elan touch device, filters palm-like contacts, and forwards
touch events through an Android-facing uinput device.

### `rm-native-controls`

Keeps device-owned controls outside Android's PID namespace. It coordinates the
front light, lock state, Marker state, safe long-idle power-off, refresh
requests, and stock-boot requests.

### Paper Home

Provides an animation-minimal launcher, Wi-Fi controls, app library, E Ink
display controls, and a clean-room note interface. Paper Home writes small
control files consumed by the host integration layer.

### Display bridge (not yet published)

The display bridge submits Android frames and the immediate pen overlay through
the device's existing E Ink library. Its current source boundary requires a
header that is not part of this repository. A public replacement interface is a
release gate.

## Data boundary

Stock and Android application data are separate. The project does not import or
redistribute the stock note database or stock note application.

## Installer design

The planned Windows installer will:

1. identify the exact device and OS version;
2. read the active stock slot and boot partition;
3. require sufficient battery and stable USB;
4. create and verify a recoverable backup;
5. verify signed, hashed open-source payloads;
6. install only to the verified inactive target;
7. install a healthy-boot fallback;
8. validate stock and Android boot paths; and
9. provide uninstall and emergency recovery using the verified backup.

Every state-changing step must be resumable and must record a machine-readable
journal.

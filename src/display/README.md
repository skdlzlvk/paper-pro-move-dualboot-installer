# Native E Ink display bridge

`rm-epd-bridge` is the project-authored Android frame, E Ink update and Marker
overlay bridge used by the Paper Pro Move prototype. It links at runtime to the
`libqsgepaper` library already present on the owner's tablet; that proprietary
device library is not included here.

The interface declaration under `third_party/oxide` comes from the MIT-licensed
Oxide project at commit `bf975bf7614f5ca23796d96b10d262715c5283a1`. The local
copy is byte-identical to that source revision. See its bundled license and
`THIRD_PARTY-NOTICES.md`.

Build this component and the boot selector with `../build-native.sh` and the
Paper Pro Move SDK. The build does not require copying `libqsgepaper` into this
repository; qmake links against the SDK sysroot and the binary resolves the
device-owned library only on the tablet.

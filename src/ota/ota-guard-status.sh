#!/bin/sh
set -eu

state_file=/home/root/native-android/ota-guard-v63.state
lpgpr=/sys/devices/platform/lpgpr
units="update-engine.service swupdate.service swupdate.socket systemd-sysupdate.service systemd-sysupdate.timer"
quiet=0
test "${1:-}" = --quiet && quiet=1

root_view=
android_view=
ok=1

cleanup()
{
    if test -n "$android_view" && grep -qs " $android_view " /proc/mounts; then
        umount "$android_view" || true
    fi
    if test -n "$root_view" && grep -qs " $root_view " /proc/mounts; then
        umount "$root_view" || true
    fi
    test -z "$android_view" || rmdir "$android_view" 2>/dev/null || true
    test -z "$root_view" || rmdir "$root_view" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

report()
{
    test "$quiet" = 1 || printf '%s\n' "$*"
}

bad()
{
    ok=0
    report "FAIL $*"
}

slot_node()
{
    readlink -f "/dev/disk/by-partlabel/root_$1"
}

stock_slot="$(cat "$lpgpr/root_part")"
boot_part="$(cat /sys/bus/mmc/devices/mmc0:0001/boot_part)"
case "$stock_slot:$boot_part" in
    a:1)
        android_slot=b
        ;;
    b:2)
        android_slot=a
        ;;
    *)
        bad "unexpected stock slot/boot partition: $stock_slot/$boot_part"
        android_slot=
        ;;
esac

test -r "$state_file" || bad "guard state file is missing"
if test -r "$state_file"; then
    recorded_android_slot="$(sed -n 's/^protected_android_slot=//p' "$state_file")"
    test "$recorded_android_slot" = "$android_slot" ||
        bad "recorded Android slot does not match the inactive slot"
fi

for unit in $units; do
    live_target="$(readlink "/etc/systemd/system/$unit" 2>/dev/null || true)"
    test "$live_target" = /dev/null || bad "live mask is missing: $unit"
    if systemctl is-active --quiet "$unit"; then
        bad "unit is active: $unit"
    fi
done

root_view="$(mktemp -d /home/root/native-android/.ota-status-root.XXXXXX)"
mount --bind / "$root_view"
for unit in $units; do
    lower_target="$(readlink "$root_view/etc/systemd/system/$unit" 2>/dev/null || true)"
    test "$lower_target" = /dev/null || bad "persistent lower-root mask is missing: $unit"
done
umount "$root_view"
rmdir "$root_view"
root_view=

if test -n "$android_slot"; then
    android_node="$(slot_node "$android_slot")"
    stock_node="$(slot_node "$stock_slot")"
    test "$android_node" != "$stock_node" ||
        bad "stock and Android slots resolve to the same device"
    if grep -qs "^$android_node " /proc/mounts; then
        bad "Android slot is already mounted: $android_node"
    else
        android_view="$(mktemp -d /home/root/native-android/.ota-status-android.XXXXXX)"
        if mount -o ro,noload "$android_node" "$android_view"; then
            test -x "$android_view/usr/sbin/rm-android-init" ||
                bad "Android init is missing from inactive slot"
            test "$(readlink "$android_view/sbin/init" 2>/dev/null || true)" = \
                /usr/sbin/rm-android-init ||
                bad "inactive slot does not identify as the Android environment"
            umount "$android_view"
        else
            bad "could not mount inactive Android slot read-only"
        fi
        rmdir "$android_view"
        android_view=
    fi
fi

if test "$ok" = 1; then
    report "OTA_GUARD_V63_OK stock=$stock_slot android=$android_slot"
    trap - EXIT INT TERM
    exit 0
fi

report "OTA_GUARD_V63_UNSAFE"
exit 1

#!/bin/sh
set -eu

guard_version=63
state_dir=/home/root/native-android
state_file="$state_dir/ota-guard-v63.state"
lpgpr=/sys/devices/platform/lpgpr
units="update-engine.service swupdate.service swupdate.socket systemd-sysupdate.service systemd-sysupdate.timer"

root_view=
android_view=
root_changed_rw=0

cleanup()
{
    if test -n "$android_view" && grep -qs " $android_view " /proc/mounts; then
        umount "$android_view" || true
    fi
    if test -n "$root_view" && grep -qs " $root_view " /proc/mounts; then
        umount "$root_view" || true
    fi
    if test "$root_changed_rw" = 1; then
        sync || true
        mount -o remount,ro / || true
    fi
    test -z "$android_view" || rmdir "$android_view" 2>/dev/null || true
    test -z "$root_view" || rmdir "$root_view" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

fail()
{
    printf 'OTA_GUARD_ERROR %s\n' "$*" >&2
    exit 1
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
        fail "unexpected stock slot/boot partition: $stock_slot/$boot_part"
        ;;
esac

stock_node="$(slot_node "$stock_slot")"
android_node="$(slot_node "$android_slot")"
test -b "$stock_node" || fail "stock block device is missing: $stock_node"
test -b "$android_node" || fail "Android block device is missing: $android_node"
test "$stock_node" != "$android_node" || fail "stock and Android slots resolve to the same device"

capacity="$(cat /sys/class/power_supply/max77818_battery/capacity)"
test "$capacity" -ge 30 || fail "battery must be at least 30 percent"

if systemctl is-active --quiet update-engine.service ||
   systemctl is-active --quiet swupdate.service ||
   systemctl is-active --quiet systemd-sysupdate.service; then
    fail "an update service is active; do not interrupt an update in progress"
fi

mkdir -p "$state_dir"
android_view="$(mktemp -d "$state_dir/.ota-android-check.XXXXXX")"
grep -qs "^$android_node " /proc/mounts &&
    fail "Android target is already mounted: $android_node"
mount -o ro,noload "$android_node" "$android_view"
test -x "$android_view/usr/sbin/rm-android-init" ||
    fail "inactive slot does not contain the expected Android init"
test "$(readlink "$android_view/sbin/init")" = /usr/sbin/rm-android-init ||
    fail "inactive slot is not the Paper Move Android environment"
umount "$android_view"
rmdir "$android_view"
android_view=

for unit in $units; do
    live_path="/etc/systemd/system/$unit"
    if test -e "$live_path" || test -L "$live_path"; then
        test "$(readlink "$live_path" 2>/dev/null || true)" = /dev/null ||
            fail "refusing to replace an existing live override: $live_path"
    fi
done

systemctl stop swupdate.socket systemd-sysupdate.timer 2>/dev/null || true

root_view="$(mktemp -d "$state_dir/.ota-root.XXXXXX")"
if grep -E -q '^[^ ]+ / [^ ]+ ro,' /proc/mounts; then
    mount -o remount,rw /
    root_changed_rw=1
fi
grep -E -q '^[^ ]+ / [^ ]+ rw,' /proc/mounts ||
    fail "root filesystem did not become writable"

mount --bind / "$root_view"
test -d "$root_view/etc/systemd/system" ||
    fail "lower rootfs /etc is not visible through the bind mount"

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_dir="$state_dir/ota-guard-v63-backup-$stamp"
mkdir "$backup_dir"
printf 'guard_version=%s\nstock_slot=%s\nandroid_slot=%s\n' \
    "$guard_version" "$stock_slot" "$android_slot" >"$backup_dir/layout"

for unit in $units; do
    lower_path="$root_view/etc/systemd/system/$unit"
    if test -e "$lower_path" || test -L "$lower_path"; then
        target="$(readlink "$lower_path" 2>/dev/null || true)"
        if test "$target" != /dev/null; then
            cp -a "$lower_path" "$backup_dir/$unit"
        fi
    fi
done

for unit in $units; do
    lower_path="$root_view/etc/systemd/system/$unit"
    rm -f "$lower_path"
    ln -s /dev/null "$lower_path"
done
sync

umount "$root_view"
rmdir "$root_view"
root_view=
if test "$root_changed_rw" = 1; then
    mount -o remount,ro /
    root_changed_rw=0
fi

# Mirror the permanent lower-root masks into the current volatile /etc view.
for unit in $units; do
    live_path="/etc/systemd/system/$unit"
    rm -f "$live_path"
    ln -s /dev/null "$live_path"
done
systemctl daemon-reload
systemctl stop update-engine.service swupdate.service swupdate.socket \
    systemd-sysupdate.service systemd-sysupdate.timer 2>/dev/null || true

for unit in $units; do
    test "$(readlink "/etc/systemd/system/$unit")" = /dev/null ||
        fail "live mask verification failed: $unit"
    systemctl is-active --quiet "$unit" &&
        fail "masked unit is still active: $unit"
done

img_version="$(sed -n 's/^IMG_VERSION=//p' /etc/os-release | sed -n '1p')"
state_tmp="$state_file.tmp.$$"
{
    printf 'ota_guard_version=%s\n' "$guard_version"
    printf 'installed_at_utc=%s\n' "$stamp"
    printf 'stock_slot_at_install=%s\n' "$stock_slot"
    printf 'protected_android_slot=%s\n' "$android_slot"
    printf 'stock_device=%s\n' "$stock_node"
    printf 'android_device=%s\n' "$android_node"
    printf 'stock_img_version=%s\n' "$img_version"
    printf 'backup_dir=%s\n' "$backup_dir"
} >"$state_tmp"
chmod 0600 "$state_tmp"
mv "$state_tmp" "$state_file"
sync

trap - EXIT INT TERM
printf 'OTA_GUARD_V63_INSTALLED stock=%s android=%s backup=%s\n' \
    "$stock_slot" "$android_slot" "$backup_dir"

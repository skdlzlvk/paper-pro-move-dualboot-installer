#!/bin/sh
#
# Host-side controls that must remain outside Android's PID namespace.
# Networking is deliberately absent: Android owns wlan0, scanning,
# authentication, saved networks, DHCP, and DNS.

set -u

app_dir=/android-data/data/com.android.launcher3/files
stock_request="$app_dir/paper-stock-request"
stock_committed="$app_dir/paper-stock-boot-committed"
stock_failed="$app_dir/paper-stock-boot-failed"
stock_host_marker=/run/paper-stock-orderly-requested
frontlight_request="$app_dir/paper-frontlight-level"
screen_state_file="$app_dir/paper-screen-state"
refresh_request="$app_dir/paper-refresh-request"
auto_poweroff_request="$app_dir/paper-auto-poweroff-minutes"
safe_poweroff_request="$app_dir/paper-safe-poweroff-request"
frontlight_brightness=/sys/class/backlight/rm_frontlight/brightness
frontlight_max=/sys/class/backlight/rm_frontlight/max_brightness
frontlight_current=/sys/class/backlight/rm_frontlight/full_scale_current
frontlight_current_max=/sys/class/backlight/rm_frontlight/max_full_scale_current
charger_online=/sys/class/power_supply/max77818-charger/online
device_battery=/sys/class/power_supply/max77818_battery/capacity
hall_device=/dev/input/event1
marker_battery=/sys/class/power_supply/nfc-marker-battery/capacity
marker_power_uevent=/sys/class/power_supply/nfc-marker-battery/uevent
wifi_diag_request=/native-wifi-diag-once
log_file=/android-data/local/tmp/paper-native-controls.log
desired_frontlight=
last_screen_state=
last_applied_frontlight=
frontlight_missing_logged=0
screen_off_since=0
last_hall_state=
last_marker_docked=
last_marker_battery=
auto_poweroff_attempted=0
poll_tick=0

log()
{
    printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S')" "$*" >>"$log_file"
}

select_stock_boot()
{
    marker_tmp="$stock_host_marker.tmp.$$"
    rm -f "$stock_host_marker" "$marker_tmp"
    if ! printf '0\n' >/sys/devices/platform/lpgpr/roota_errcnt; then
        log "stock OS request refused: cannot clear A-slot error count"
        return 1
    fi
    # The one-shot Android boot leaves u-boot's B-slot counter at 2. Stock
    # boots A regardless, but the host backup/restore gates only accept 0|1
    # and refused the next uninstall (physical finding #32, 2026-08-22).
    # Returning to stock after a working Android session is the moment the
    # counter's failure history is known to be clean.
    if ! printf '0\n' >/sys/devices/platform/lpgpr/rootb_errcnt; then
        log "stock OS request: B-slot error count could not be cleared"
    fi
    if ! /usr/bin/mmc bootpart enable 1 0 /dev/mmcblk0boot0; then
        log "stock OS request refused: cannot select boot partition 1"
        return 1
    fi
    if [ "$(cat /sys/bus/mmc/devices/mmc0:0001/boot_part 2>/dev/null)" != 1 ]; then
        log "stock OS request refused: boot partition verification failed"
        return 1
    fi
    sync
    if ! printf '%s\n' "$(date +%s)" >"$marker_tmp" ||
       ! chmod 0600 "$marker_tmp" ||
       ! mv -f "$marker_tmp" "$stock_host_marker"; then
        rm -f "$marker_tmp" "$stock_host_marker"
        log "stock OS request refused: host orderly marker failed"
        return 1
    fi
    sync
    log "stock OS boot committed (roota_errcnt=0 boot_part=1)"
    return 0
}

apply_frontlight()
{
    level=$1
    max="$(cat "$frontlight_max" 2>/dev/null || printf '2047')"
    value=$((level * max / 255))
    # Brightness and LED current are separate on Paper Pro Move. A cold boot
    # can leave the current at 0/9, making a non-zero brightness ineffective.
    if [ -w "$frontlight_current" ] &&
       [ -r "$frontlight_current_max" ]; then
        if [ "$level" -gt 0 ]; then
            current="$(cat "$frontlight_current_max" 2>/dev/null || printf '9')"
        else
            current=0
        fi
        printf '%s\n' "$current" >"$frontlight_current" ||
            log "frontlight current write failed current=$current"
    fi
    if printf '%s\n' "$value" >"$frontlight_brightness"; then
        last_applied_frontlight=$level
        log "frontlight Android=$level hardware=$value/$max current=${current:-unavailable}"
    else
        log "frontlight write failed Android=$level hardware=$value/$max"
    fi
}

read_frontlight_request()
{
    raw="$(cat "$frontlight_request" 2>/dev/null || true)"
    case "$raw" in
        ''|*[!0-9]*)
            return
            ;;
    esac

    if [ "$raw" -gt 255 ]; then
        raw=255
    fi
    desired_frontlight=$raw
}

read_screen_state()
{
    state="$(cat "$screen_state_file" 2>/dev/null || printf 'on')"
    case "$state" in
        off)
            printf 'off'
            ;;
        *)
            printf 'on'
            ;;
    esac
}

sync_frontlight()
{
    read_frontlight_request
    screen_state="$(read_screen_state)"

    if [ ! -w "$frontlight_brightness" ] ||
       [ ! -r "$frontlight_max" ]; then
        if [ "$frontlight_missing_logged" -eq 0 ]; then
            log "frontlight sysfs is unavailable; request retained"
            frontlight_missing_logged=1
        fi
        return
    fi
    if [ "$frontlight_missing_logged" -ne 0 ]; then
        log "frontlight sysfs became available"
        frontlight_missing_logged=0
    fi

    if [ "$screen_state" = off ]; then
        if [ "$last_screen_state" != off ] ||
           [ "$last_applied_frontlight" != 0 ]; then
            apply_frontlight 0
            log "frontlight suspended for Android screen-off"
        fi
    else
        hardware_brightness="$(cat "$frontlight_brightness" 2>/dev/null || printf '%s' -1)"
        hardware_max="$(cat "$frontlight_max" 2>/dev/null || printf '2047')"
        expected_brightness=0
        if [ -n "$desired_frontlight" ]; then
            expected_brightness=$((desired_frontlight * hardware_max / 255))
        fi
        hardware_current="$(cat "$frontlight_current" 2>/dev/null || printf '%s' -1)"
        expected_current=0
        if [ -n "$desired_frontlight" ] && [ "$desired_frontlight" -gt 0 ]; then
            expected_current="$(cat "$frontlight_current_max" 2>/dev/null || printf '9')"
        fi
        if [ -n "$desired_frontlight" ] &&
           { [ "$last_screen_state" != on ] ||
             [ "$last_applied_frontlight" != "$desired_frontlight" ] ||
             [ "$hardware_brightness" != "$expected_brightness" ] ||
             [ "$hardware_current" != "$expected_current" ]; }; then
            apply_frontlight "$desired_frontlight"
            log "frontlight restored for Android screen-on expected=${expected_brightness}/${expected_current} observed=${hardware_brightness}/${hardware_current}"
        fi
    fi
    last_screen_state=$screen_state
}

switch_state()
{
    switch_name=$1
    /usr/bin/evtest --query "$hall_device" EV_SW "$switch_name" \
            >/dev/null 2>&1
    status=$?
    # evtest --query intentionally returns 10 when the state bit is set and
    # 0 when it is clear. Exit 1 is an actual query error.
    case "$status" in
        10)
            printf '1'
            ;;
        0)
            printf '0'
            ;;
        *)
            printf 'unknown'
            ;;
    esac
}

marker_rfkill_soft()
{
    for rfkill in /sys/class/rfkill/rfkill*; do
        [ -r "$rfkill/name" ] || continue
        [ "$(cat "$rfkill/name" 2>/dev/null)" = ctn730 ] || continue
        printf '%s/soft' "$rfkill"
        return 0
    done
    return 1
}

sync_marker_radio()
{
    docked=$1
    soft_path="$(marker_rfkill_soft 2>/dev/null || true)"
    [ -n "$soft_path" ] && [ -r "$soft_path" ] && [ -w "$soft_path" ] ||
        return

    soft="$(cat "$soft_path" 2>/dev/null || printf 'unknown')"
    case "$docked:$soft" in
        1:1)
            # Stock marker-manager unblocks CTN730 as soon as the magnetic
            # dock switch closes. Native Android previously left it soft
            # blocked, so the attached Marker never exposed its serial,
            # battery or pairing data until a later manual dock cycle.
            if printf '0\n' >"$soft_path"; then
                log "marker NFC unblocked for dock initialization"
                [ -w "$marker_power_uevent" ] &&
                    printf 'change\n' >"$marker_power_uevent"
            else
                log "marker NFC unblock failed"
            fi
            ;;
        0:0)
            # NFC is only needed while the Marker is magnetically attached.
            # Re-block it after undock to retain the stock idle-power policy.
            if printf '1\n' >"$soft_path"; then
                log "marker NFC blocked after undock"
            else
                log "marker NFC block failed"
            fi
            ;;
    esac
}

sync_hall_state()
{
    if [ ! -r "$hall_device" ] || [ ! -x /usr/bin/evtest ]; then
        return
    fi

    lid="$(switch_state SW_LID)"
    case "$lid" in
    1)
        hall_state=closed
        ;;
    0)
        hall_state=open
        ;;
    *)
        hall_state=unknown
        ;;
    esac
    if [ "$hall_state" != unknown ] &&
       [ "$hall_state" != "$last_hall_state" ]; then
        log "hall cover state=$hall_state"
        # The host cannot inject into Android after pivot_root without
        # entering its PID and mount namespaces. v55 retried a broken chroot
        # every second and left the panel state different from Android's real
        # interactive state. Keep the folio switch diagnostic-only until a
        # namespace-aware Android policy bridge is implemented.
        last_hall_state=$hall_state
    fi

    marker_docked="$(switch_state SW_PEN_INSERTED)"
    if [ "$marker_docked" != unknown ] &&
       [ "$marker_docked" != "$last_marker_docked" ]; then
        last_marker_docked=$marker_docked
        log "marker docked=$marker_docked"
    fi
    if [ "$marker_docked" != unknown ]; then
        sync_marker_radio "$marker_docked"
    fi
}

sync_marker_battery()
{
    value="$(cat "$marker_battery" 2>/dev/null || true)"
    case "$value" in
        ''|*[!0-9]*)
            return
            ;;
    esac
    if [ "$value" != "$last_marker_battery" ]; then
        last_marker_battery=$value
        log "marker battery=$value%"
    fi
}

read_auto_poweroff_minutes()
{
    value="$(cat "$auto_poweroff_request" 2>/dev/null || printf '30')"
    case "$value" in
        ''|*[!0-9]*)
            value=30
            ;;
    esac
    if [ "$value" -gt 180 ]; then
        value=180
    fi
    printf '%s' "$value"
}

safe_android_poweroff()
{
    reason=$1
    if [ "$auto_poweroff_attempted" -ne 0 ]; then
        return
    fi
    auto_poweroff_attempted=1

    log "safe Android power-off requested reason=$reason"
    if ! printf '%s\n' "$reason" >"$safe_poweroff_request"; then
        log "safe Android power-off request write failed"
        auto_poweroff_attempted=0
        return
    fi
    sync
}

sync_power_state()
{
    screen_state="$(read_screen_state)"
    now="$(date +%s)"
    if [ "$screen_state" = off ]; then
        if [ "$(cat "$charger_online" 2>/dev/null || printf '0')" = 1 ]; then
            # Charging/USB diagnostics stay fully reachable. Start a fresh
            # unplugged timer if the cable is removed while the screen is off.
            screen_off_since=$now
            auto_poweroff_attempted=0
            return
        fi
        if [ "$screen_off_since" -eq 0 ]; then
            screen_off_since=$now
            log "Android screen is off; safe power-off timer armed"
            return
        fi

        minutes="$(read_auto_poweroff_minutes)"
        timeout_seconds=$((minutes * 60))
        battery="$(cat "$device_battery" 2>/dev/null || printf '100')"
        case "$battery" in
            ''|*[!0-9]*)
                battery=100
                ;;
        esac
        reason="idle-${minutes}m"
        if [ "$battery" -le 10 ]; then
            timeout_seconds=120
            reason="low-battery-${battery}pct"
        elif [ "$minutes" -eq 0 ]; then
            return
        fi
        if [ $((now - screen_off_since)) -ge "$timeout_seconds" ]; then
            safe_android_poweroff "$reason"
        fi
    else
        if [ "$screen_off_since" -ne 0 ]; then
            log "Android screen is on; safe power-off timer cancelled"
        fi
        screen_off_since=0
        auto_poweroff_attempted=0
    fi
}

log "Paper Home native controls started"
while :; do
    sync_frontlight
    poll_tick=$((poll_tick + 1))
    if [ $((poll_tick % 4)) -eq 0 ]; then
        sync_hall_state
    fi
    if [ $((poll_tick % 240)) -eq 1 ]; then
        sync_marker_battery
    fi
    sync_power_state
    if [ -e "$wifi_diag_request" ]; then
        rm -f "$wifi_diag_request"
        log "native Wi-Fi diagnostic window started (90 seconds)"
        sleep 90
        log "native Wi-Fi diagnostic window completed; returning to stock"
        sync
        /usr/bin/busybox reboot -f
        sleep 5
        log "host reboot syscall returned unexpectedly"
    fi
    if [ -e "$stock_request" ]; then
        rm -f "$stock_request"
        rm -f "$stock_committed" "$stock_failed"
        log "stock OS reboot requested"
        if select_stock_boot; then
            committed_tmp="$stock_committed.tmp.$$"
            if printf '%s\n' "$(date +%s)" >"$committed_tmp" &&
               chmod 0600 "$committed_tmp" &&
               mv -f "$committed_tmp" "$stock_committed"; then
                sync
                log "stock OS boot committed; awaiting orderly Android reboot"
            else
                rm -f "$committed_tmp"
                printf '%s\n' commit-ack-failed >"$stock_failed" || true
                log "stock OS boot committed but acknowledgement failed"
            fi
        else
            printf '%s\n' boot-selection-failed >"$stock_failed" || true
        fi
    fi
    # These controls are human-scale state changes, not animation input.
    # Polling four times per second kept the host CPU and storage path active
    # while a static E-ink page was displayed. One second preserves responsive
    # light/screen controls and cuts routine control wakeups by 75%.
    sleep 1
done

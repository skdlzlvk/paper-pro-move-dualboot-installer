#!/vendor/bin/sh

# Run after sys.boot_completed=1. The future installer commit engine writes
# exactly one of en-US, ko-KR, or zh-CN to this file in the Android data image.
seed=/data/local/paper-android-locale
test -s "$seed" || exit 0

locale="$(tr -d '\r\n' <"$seed")"
case "$locale" in
    en-US|ko-KR|zh-CN) ;;
    *)
        log -t paper-locale-seed "Rejected unsupported Android locale seed"
        exit 2
        ;;
esac

result="$(am broadcast -W --user 0 \
    -n com.android.launcher3/.paper.LocaleSeedReceiver \
    -a com.android.launcher3.paper.action.SET_INITIAL_LOCALE \
    --es locale "$locale" 2>&1 || true)"
case "$result" in
    *"result=-1"*)
        rm -f "$seed"
        log -t paper-locale-seed "Applied Android locale $locale"
        ;;
    *)
        log -t paper-locale-seed "Locale seed retained after failure: $result"
        exit 1
        ;;
esac

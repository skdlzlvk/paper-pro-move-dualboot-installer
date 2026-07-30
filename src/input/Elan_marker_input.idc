# reMarkable Paper Pro Move built-in Elan marker
#
# Without this override Android guesses POINTER because the device exposes
# BTN_TOOL_PEN together with absolute axes.  POINTER becomes mode 4 and
# SOURCE_MOUSE|SOURCE_STYLUS (0x6002), which produces a mouse cursor.  The
# marker is physically integrated with display 0, so force the same direct
# coordinate mapping used by the stock Qt tablet path.
device.internal = 1
touch.deviceType = touchScreen
touch.orientationAware = 1

# Android's PHYSICAL default normalizes the stock 0..4096 pressure range.
touch.pressure.calibration = physical

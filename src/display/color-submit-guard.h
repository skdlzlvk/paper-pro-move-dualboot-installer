#ifndef PAPER_MOVE_COLOR_SUBMIT_GUARD_H
#define PAPER_MOVE_COLOR_SUBMIT_GUARD_H

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace paper_move {

// Exact stat identity, not a hash: detect replacement and same-value rewrites
// of a one-shot control while an older request is being prepared or displayed.
struct ColorControlStamp {
    bool readable = true;
    bool exists = false;
    std::uint64_t device = 0, inode = 0, size = 0;
    std::int64_t modifiedSeconds = 0, modifiedNanoseconds = 0;
    std::int64_t changedSeconds = 0, changedNanoseconds = 0;

    bool operator==(const ColorControlStamp &other) const
    {
        return readable == other.readable && exists == other.exists &&
            device == other.device && inode == other.inode && size == other.size &&
            modifiedSeconds == other.modifiedSeconds &&
            modifiedNanoseconds == other.modifiedNanoseconds &&
            changedSeconds == other.changedSeconds &&
            changedNanoseconds == other.changedNanoseconds;
    }
};

struct ColorSubmitState {
    std::uint64_t frames = 0, penReports = 0, penPresence = 0;
    bool touchActive = false, typingActive = false;
    bool penInRange = false, penPending = false;
    bool screenOff = false, refreshPending = false, stopped = false;
    bool colorEnabled = true, inverted = false, noteActive = false, readerActive = false;
    int colorMode = 0;
    std::array<ColorControlStamp, 5> controls{};
};

inline bool colorSubmitIdle(const ColorSubmitState &state)
{
    if (state.touchActive || state.typingActive || state.penInRange ||
        state.penPending || state.screenOff || state.refreshPending ||
        state.stopped || !state.colorEnabled)
        return false;
    for (const auto &control : state.controls)
        if (!control.readable)
            return false;
    return true;
}

inline bool colorSubmitControlsStillCurrent(const ColorSubmitState &expected,
                                            const ColorSubmitState &current)
{
    return colorSubmitIdle(expected) && colorSubmitIdle(current) &&
        current.penReports == expected.penReports &&
        current.penPresence == expected.penPresence &&
        current.colorMode == expected.colorMode &&
        current.inverted == expected.inverted &&
        current.noteActive == expected.noteActive &&
        current.readerActive == expected.readerActive &&
        current.controls == expected.controls;
}

inline bool colorSubmitStillCurrent(const ColorSubmitState &expected,
                                    const ColorSubmitState &current)
{
    return current.frames == expected.frames &&
        colorSubmitControlsStillCurrent(expected, current);
}

template <class Frame>
struct ColorSubmitSnapshot {
    ColorSubmitState state;
    Frame frame;
};

inline bool colorFrameBytesEqual(const void *expected, std::size_t expectedBytes,
                                const void *current, std::size_t currentBytes)
{
    return expected && current && expectedBytes != 0 &&
        expectedBytes == currentBytes &&
        std::memcmp(expected, current, expectedBytes) == 0;
}

// HWC may repost identical pixels every VSync. A generation change alone must
// not starve color preparation, but accepting it requires exact retained RGB
// equality, not a sparse sample/hash. Capture owns immutable frame storage and
// releases the mailbox lock before comparison. A fresh capture after comparison
// must still name that generation and satisfy every input/control/pen guard.
template <class Capture, class SameContent>
bool colorSubmitContentStillCurrent(const ColorSubmitState &expected,
                                    Capture capture, SameContent sameContent)
{
    const auto snapshot = capture();
    if (!colorSubmitControlsStillCurrent(expected, snapshot.state) ||
        snapshot.state.frames < expected.frames)
        return false;
    if (snapshot.state.frames == expected.frames)
        return true;
    if (!sameContent(snapshot.frame))
        return false;
    const auto finalSnapshot = capture();
    return finalSnapshot.state.frames == snapshot.state.frames &&
        colorSubmitControlsStillCurrent(expected, finalSnapshot.state);
}

struct ColorControlErrorLogState {
    bool unavailable = false, hasReported = false;
    std::int64_t lastReportMilliseconds = 0;
};

inline bool reportColorControlError(ColorControlErrorLogState &state, int error,
                                    std::int64_t nowMilliseconds)
{
    state.unavailable = error != 0;
    if (!state.unavailable)
        return false;
    // Report the first failure immediately. Repeated failures (including rapid
    // recovery/failure flapping) are limited to one report per control per 30s.
    if (state.hasReported && nowMilliseconds - state.lastReportMilliseconds < 30000)
        return false;
    state.hasReported = true;
    state.lastReportMilliseconds = nowMilliseconds;
    return true;
}

inline bool composeColorPenOverlay(bool releaseNoteOverlay, bool overlayNoteActive,
                                   bool currentNoteActive)
{
    return !releaseNoteOverlay && overlayNoteActive && currentNoteActive;
}

inline bool sameColorControlRequest(int expectedMode, const ColorControlStamp &expected,
                                    int currentMode, const ColorControlStamp &current)
{
    return expected.readable && current.readable &&
        expectedMode == currentMode && expected == current;
}

enum class ColorPreparation { Unavailable, NoColor, Duplicate, Ready };
enum class ColorSubmitResult { Deferred, NoColor, Duplicate, Submitted };

// Preparation may only touch scratch storage. No pending request, overlay or
// framebuffer is consumed until the final check accepts the prepared frame.
// The check-to-commit interval is deliberately short, not claimed atomic with
// external input or filesystem writers; commit stays on the panel owner thread.
template <class Prepare, class StillCurrent, class Commit>
ColorSubmitResult guardedColorSubmit(Prepare prepare, StillCurrent stillCurrent,
                                     Commit commit)
{
    if (!stillCurrent())
        return ColorSubmitResult::Deferred;
    const ColorPreparation prepared = prepare();
    if (!stillCurrent())
        return ColorSubmitResult::Deferred;
    if (prepared == ColorPreparation::Unavailable)
        return ColorSubmitResult::Deferred;
    if (prepared == ColorPreparation::NoColor)
        return ColorSubmitResult::NoColor;
    if (prepared == ColorPreparation::Duplicate)
        return ColorSubmitResult::Duplicate;
    commit();
    return ColorSubmitResult::Submitted;
}

} // namespace paper_move
#endif

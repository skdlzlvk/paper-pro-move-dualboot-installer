#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define DEFAULT_INPUT "/dev/input/event3"
#define DEFAULT_LOG "/native-touch-relay.log"
#define EVENT_LOG "/native-touch-events.bin"
#define READY_MARKER "/native-touch-ready"
#define MAX_TOUCH_SLOTS 64
#define MAX_FRAME_EVENTS 512
static volatile sig_atomic_t stop_requested;
static int log_fd = -1;

struct relay_slot {
    bool physical_active;
    bool palm;
    bool forwarded_active;
    bool x_valid;
    bool y_valid;
    int tracking_id;
    int x;
    int y;
};

static void handle_signal(int signal_number)
{
    (void)signal_number;
    stop_requested = 1;
}

static void log_message(const char *format, ...)
{
    char message[768];
    char line[1024];
    struct timespec now;
    struct tm utc;
    va_list arguments;
    int length;

    va_start(arguments, format);
    vsnprintf(message, sizeof(message), format, arguments);
    va_end(arguments);
    clock_gettime(CLOCK_REALTIME, &now);
    gmtime_r(&now.tv_sec, &utc);
    length = snprintf(line, sizeof(line),
                      "%04d-%02d-%02dT%02d:%02d:%02d.%03ldZ %s\n",
                      utc.tm_year + 1900, utc.tm_mon + 1, utc.tm_mday,
                      utc.tm_hour, utc.tm_min, utc.tm_sec,
                      now.tv_nsec / 1000000L, message);
    if (length < 0)
        return;
    if ((size_t)length > sizeof(line))
        length = sizeof(line);
    if (log_fd >= 0) {
        (void)write(log_fd, line, (size_t)length);
        fsync(log_fd);
    }
    (void)write(STDERR_FILENO, line, (size_t)length);
}

static bool emit_input_event(int virtual_fd,
                             unsigned short type,
                             unsigned short code,
                             int value)
{
    struct input_event event = {
        .type = type,
        .code = code,
        .value = value,
    };
    ssize_t written;

    /*
     * Leave the timestamp at zero. input-core stamps uinput events with the
     * current monotonic time; copying the Elan CLOCK_REALTIME timestamp makes
     * Android believe that the touch came from decades in the future.
     */
    do {
        written = write(virtual_fd, &event, sizeof(event));
    } while (written < 0 && errno == EINTR);
    if (written == sizeof(event))
        return true;
    log_message("ERROR uinput write type=%u code=%u value=%d: %s",
                type, code, value, strerror(errno));
    return false;
}

static bool flush_touch_frame(int virtual_fd,
                              struct relay_slot *slots,
                              int slot_count,
                              const struct input_event *events,
                              size_t event_count,
                              uint64_t *palm_report_count,
                              int *physical_current_slot,
                              int *last_x,
                              int *last_y,
                              int *last_tracking_id)
{
    bool tracking_changed[MAX_TOUCH_SLOTS] = {false};
    bool tool_changed[MAX_TOUCH_SLOTS] = {false};
    bool x_changed[MAX_TOUCH_SLOTS] = {false};
    bool y_changed[MAX_TOUCH_SLOTS] = {false};
    bool old_active[MAX_TOUCH_SLOTS];
    int old_tracking[MAX_TOUCH_SLOTS];
    bool syn_dropped = false;
    int current_slot = *physical_current_slot;
    int forwarded_count = 0;
    bool have_palm = false;

    for (int slot = 0; slot < slot_count; ++slot) {
        old_active[slot] = slots[slot].physical_active;
        old_tracking[slot] = slots[slot].tracking_id;
    }

    /*
     * The Elan driver reports MT_TOOL_PALM on event3.  The previous relay
     * stripped that axis and accidentally promoted every palm to a regular
     * Android finger.  Parse a complete SYN_REPORT first so a new slot whose
     * TOOL_TYPE arrives after TRACKING_ID is never forwarded prematurely.
     */
    for (size_t index = 0; index < event_count; ++index) {
        const struct input_event *event = &events[index];

        if (event->type == EV_SYN && event->code == SYN_DROPPED) {
            syn_dropped = true;
            continue;
        }
        if (event->type != EV_ABS)
            continue;
        if (event->code == ABS_MT_SLOT) {
            if (event->value >= 0 && event->value < slot_count) {
                current_slot = event->value;
                *physical_current_slot = current_slot;
            }
            continue;
        }
        switch (event->code) {
        case ABS_MT_TRACKING_ID:
            tracking_changed[current_slot] = true;
            slots[current_slot].tracking_id = event->value;
            slots[current_slot].physical_active = event->value >= 0;
            if (event->value < 0)
                slots[current_slot].palm = false;
            break;
        case ABS_MT_TOOL_TYPE:
            tool_changed[current_slot] = true;
            slots[current_slot].palm =
                event->value == MT_TOOL_PALM;
            break;
        case ABS_MT_POSITION_X:
            slots[current_slot].x = event->value;
            slots[current_slot].x_valid = true;
            x_changed[current_slot] = true;
            break;
        case ABS_MT_POSITION_Y:
            slots[current_slot].y = event->value;
            slots[current_slot].y_valid = true;
            y_changed[current_slot] = true;
            break;
        default:
            break;
        }
    }

    if (syn_dropped) {
        for (int slot = 0; slot < slot_count; ++slot) {
            slots[slot].physical_active = false;
            slots[slot].palm = false;
            slots[slot].tracking_id = -1;
        }
    }

    for (int slot = 0; slot < slot_count; ++slot) {
        const bool replaced =
            tracking_changed[slot] && old_active[slot] &&
            slots[slot].physical_active &&
            old_tracking[slot] != slots[slot].tracking_id;
        const bool must_end =
            slots[slot].forwarded_active &&
            (!slots[slot].physical_active || slots[slot].palm ||
             replaced || syn_dropped);

        if (must_end) {
            if (!emit_input_event(virtual_fd, EV_ABS,
                                  ABS_MT_SLOT, slot) ||
                !emit_input_event(virtual_fd, EV_ABS,
                                  ABS_MT_TRACKING_ID, -1)) {
                return false;
            }
            slots[slot].forwarded_active = false;
        }

        if (slots[slot].physical_active && slots[slot].palm) {
            have_palm = true;
            continue;
        }
        if (!slots[slot].physical_active)
            continue;

        if (!slots[slot].forwarded_active) {
            if (!emit_input_event(virtual_fd, EV_ABS,
                                  ABS_MT_SLOT, slot) ||
                !emit_input_event(virtual_fd, EV_ABS,
                                  ABS_MT_TRACKING_ID,
                                  slots[slot].tracking_id)) {
                return false;
            }
            slots[slot].forwarded_active = true;
            x_changed[slot] = slots[slot].x_valid;
            y_changed[slot] = slots[slot].y_valid;
        } else if (tool_changed[slot] && slots[slot].palm) {
            continue;
        }

        if (x_changed[slot] || y_changed[slot]) {
            if (!emit_input_event(virtual_fd, EV_ABS,
                                  ABS_MT_SLOT, slot)) {
                return false;
            }
            if (x_changed[slot] &&
                !emit_input_event(virtual_fd, EV_ABS,
                                  ABS_MT_POSITION_X,
                                  slots[slot].x)) {
                return false;
            }
            if (y_changed[slot] &&
                !emit_input_event(virtual_fd, EV_ABS,
                                  ABS_MT_POSITION_Y,
                                  slots[slot].y)) {
                return false;
            }
        }
        ++forwarded_count;
        *last_x = slots[slot].x;
        *last_y = slots[slot].y;
        *last_tracking_id = slots[slot].tracking_id;
    }

    if (have_palm)
        ++*palm_report_count;
    if (forwarded_count == 0)
        *last_tracking_id = -1;
    return emit_input_event(virtual_fd, EV_KEY, BTN_TOUCH,
                            forwarded_count > 0) &&
           emit_input_event(virtual_fd, EV_SYN, SYN_REPORT, 0);
}

static int enable_abs_axis(int physical_fd, int virtual_fd, int code)
{
    struct uinput_abs_setup setup = {
        .code = (uint16_t)code,
    };

    if (ioctl(physical_fd, EVIOCGABS(code), &setup.absinfo) < 0)
        return -1;
    if (ioctl(virtual_fd, UI_SET_ABSBIT, code) < 0 ||
        ioctl(virtual_fd, UI_ABS_SETUP, &setup) < 0)
        return -1;
    return 0;
}

static int create_virtual_touch(int physical_fd)
{
    static const int touch_axes[] = {
        ABS_MT_SLOT,
        ABS_MT_POSITION_X,
        ABS_MT_POSITION_Y,
        ABS_MT_TRACKING_ID,
    };
    struct input_id physical_id = {
        .bustype = BUS_VIRTUAL,
        .vendor = 0x524d,
        .product = 0x0001,
        .version = 1,
    };
    struct uinput_setup setup = {0};
    int virtual_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);

    if (virtual_fd < 0)
        return -1;
    (void)ioctl(physical_fd, EVIOCGID, &physical_id);
    physical_id.bustype = BUS_VIRTUAL;

    /*
     * Do not clone every Elan axis.  Its touch node advertises distance,
     * pressure and per-contact tool type so the stock stack can share logic
     * with the marker device.  Android consequently classified the cloned
     * device as TOUCHSCREEN | STYLUS and emitted HOVER_MOVE instead of DOWN.
     * A minimal protocol-B touchscreen is unambiguous.
     */
    if (ioctl(virtual_fd, UI_SET_EVBIT, EV_SYN) < 0 ||
        ioctl(virtual_fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(virtual_fd, UI_SET_EVBIT, EV_ABS) < 0 ||
        ioctl(virtual_fd, UI_SET_KEYBIT, BTN_TOUCH) < 0) {
        close(virtual_fd);
        return -1;
    }
    for (size_t index = 0;
         index < sizeof(touch_axes) / sizeof(touch_axes[0]);
         ++index) {
        if (enable_abs_axis(physical_fd, virtual_fd,
                            touch_axes[index]) < 0) {
            close(virtual_fd);
            return -1;
        }
    }
    if (ioctl(virtual_fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT) < 0) {
        close(virtual_fd);
        return -1;
    }

    setup.id = physical_id;
    snprintf(setup.name, sizeof(setup.name), "rm Android touch relay");
    if (ioctl(virtual_fd, UI_DEV_SETUP, &setup) < 0 ||
        ioctl(virtual_fd, UI_DEV_CREATE) < 0) {
        close(virtual_fd);
        return -1;
    }
    return virtual_fd;
}

static bool write_ready_marker(void)
{
    int marker = open(READY_MARKER,
                      O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (marker < 0)
        return false;
    const char value[] = "physical event3 grabbed; uinput relay ready\n";
    bool success =
        write(marker, value, sizeof(value) - 1) == sizeof(value) - 1;
    fsync(marker);
    close(marker);
    return success;
}

int main(int argc, char **argv)
{
    const char *input_path = argc > 1 ? argv[1] : DEFAULT_INPUT;
    const char *log_path = argc > 2 ? argv[2] : DEFAULT_LOG;
    int physical_fd = -1;
    int virtual_fd = -1;
    int event_log_fd = -1;
    uint64_t report_count = 0;
    uint64_t palm_report_count = 0;
    int last_x = -1;
    int last_y = -1;
    int last_tracking_id = -1;
    int physical_current_slot = 0;
    int slot_count = 10;
    struct relay_slot slots[MAX_TOUCH_SLOTS] = {{0}};
    struct input_event frame_events[MAX_FRAME_EVENTS];
    size_t frame_event_count = 0;

    unlink(READY_MARKER);
    log_fd = open(log_path,
                  O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    signal(SIGINT, handle_signal);
    signal(SIGTERM, handle_signal);
    signal(SIGPIPE, SIG_IGN);

    physical_fd = open(input_path, O_RDONLY | O_CLOEXEC);
    if (physical_fd < 0) {
        log_message("ERROR open %s failed: %s",
                    input_path, strerror(errno));
        return 10;
    }
    struct input_absinfo slot_info;
    if (ioctl(physical_fd, EVIOCGABS(ABS_MT_SLOT), &slot_info) == 0) {
        slot_count = slot_info.maximum + 1;
        if (slot_count < 1)
            slot_count = 1;
        if (slot_count > MAX_TOUCH_SLOTS)
            slot_count = MAX_TOUCH_SLOTS;
    }
    for (int slot = 0; slot < slot_count; ++slot) {
        slots[slot].tracking_id = -1;
        slots[slot].x = -1;
        slots[slot].y = -1;
    }
    if (ioctl(physical_fd, EVIOCGRAB, 1) < 0) {
        log_message("ERROR EVIOCGRAB %s failed: %s",
                    input_path, strerror(errno));
        close(physical_fd);
        return 11;
    }

    virtual_fd = create_virtual_touch(physical_fd);
    if (virtual_fd < 0) {
        log_message("ERROR create uinput relay failed: %s",
                    strerror(errno));
        ioctl(physical_fd, EVIOCGRAB, 0);
        close(physical_fd);
        return 12;
    }
    event_log_fd =
        open(EVENT_LOG, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    if (!write_ready_marker()) {
        log_message("ERROR readiness marker failed: %s", strerror(errno));
        ioctl(virtual_fd, UI_DEV_DESTROY);
        close(virtual_fd);
        ioctl(physical_fd, EVIOCGRAB, 0);
        close(physical_fd);
        return 13;
    }
    log_message("SUCCESS %s grabbed and mirrored through /dev/uinput",
                input_path);

    while (!stop_requested) {
        struct pollfd ready = {
            .fd = physical_fd,
            .events = POLLIN,
        };
        int poll_result = poll(&ready, 1, 1000);
        if (poll_result < 0) {
            if (errno == EINTR)
                continue;
            log_message("ERROR poll failed: %s", strerror(errno));
            break;
        }
        if (poll_result == 0)
            continue;
        if (!(ready.revents & POLLIN)) {
            log_message("ERROR physical input revents=0x%x",
                        ready.revents);
            break;
        }

        struct input_event events[32];
        ssize_t count = read(physical_fd, events, sizeof(events));
        if (count < 0) {
            if (errno == EINTR || errno == EAGAIN)
                continue;
            log_message("ERROR read failed: %s", strerror(errno));
            break;
        }
        if (count == 0 || count % sizeof(struct input_event) != 0) {
            log_message("ERROR short event read: %ld", (long)count);
            break;
        }
        if (event_log_fd >= 0)
            (void)write(event_log_fd, events, (size_t)count);

        size_t event_count = (size_t)count / sizeof(struct input_event);
        for (size_t index = 0; index < event_count; ++index) {
            struct input_event *event = &events[index];

            if (frame_event_count == MAX_FRAME_EVENTS) {
                log_message("ERROR physical touch frame exceeded %d events",
                            MAX_FRAME_EVENTS);
                frame_event_count = 0;
            }
            frame_events[frame_event_count++] = *event;
            if (event->type == EV_SYN &&
                event->code == SYN_REPORT) {
                if (!flush_touch_frame(
                        virtual_fd, slots, slot_count,
                        frame_events, frame_event_count,
                        &palm_report_count,
                        &physical_current_slot,
                        &last_x, &last_y, &last_tracking_id)) {
                    stop_requested = 1;
                    break;
                }
                frame_event_count = 0;
                ++report_count;
                if (report_count <= 10 || report_count % 50 == 0 ||
                    last_tracking_id < 0) {
                    log_message("touch report=%llu tracking=%d x=%d y=%d "
                                "palm-filtered=%llu",
                                (unsigned long long)report_count,
                                last_tracking_id, last_x, last_y,
                                (unsigned long long)palm_report_count);
                }
            }
        }
    }

    unlink(READY_MARKER);
    if (event_log_fd >= 0) {
        fsync(event_log_fd);
        close(event_log_fd);
    }
    if (virtual_fd >= 0) {
        ioctl(virtual_fd, UI_DEV_DESTROY);
        close(virtual_fd);
    }
    if (physical_fd >= 0) {
        ioctl(physical_fd, EVIOCGRAB, 0);
        close(physical_fd);
    }
    log_message("rm-touch-relay exiting after %llu reports; "
                "palm-filtered=%llu",
                (unsigned long long)report_count,
                (unsigned long long)palm_report_count);
    if (log_fd >= 0)
        close(log_fd);
    return stop_requested ? 0 : 14;
}

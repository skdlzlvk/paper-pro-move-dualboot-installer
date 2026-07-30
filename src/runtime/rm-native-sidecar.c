#define _GNU_SOURCE

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/reboot.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#define ANDROID_LOG "/data/local/tmp/native-sidecar.log"
#define ANDROID_KMSG "/data/local/tmp/native-kmsg.log"
#define HOST_ROOT "/rm-host"
#define HOST_VIEWER "/opt/rm-android/redroid-viewer"

static void log_line(const char *format, ...)
{
    char message[1024];
    va_list arguments;
    int length;
    int output;

    va_start(arguments, format);
    length = vsnprintf(message, sizeof(message), format, arguments);
    va_end(arguments);
    if (length < 0)
        return;
    if ((size_t)length >= sizeof(message))
        length = sizeof(message) - 1;

    output = open(ANDROID_LOG,
                  O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0666);
    if (output < 0)
        return;
    dprintf(output, "%.*s\n", length, message);
    fsync(output);
    close(output);
}

static bool tcp_ready(unsigned short port)
{
    struct sockaddr_in endpoint = {
        .sin_family = AF_INET,
        .sin_port = htons(port),
        .sin_addr.s_addr = htonl(INADDR_LOOPBACK),
    };
    int socket_fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    bool ready = false;

    if (socket_fd < 0)
        return false;
    if (connect(socket_fd, (struct sockaddr *)&endpoint,
                sizeof(endpoint)) == 0)
        ready = true;
    close(socket_fd);
    return ready;
}

static int run_kmsg_logger(void)
{
    int input;
    int output = -1;

    for (int attempt = 0; attempt < 60 && output < 0; ++attempt) {
        output = open(ANDROID_KMSG,
                      O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0666);
        if (output < 0)
            sleep(1);
    }
    input = open("/dev/kmsg", O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (input < 0 || output < 0)
        return 2;

    log_line("Android-side kernel logger started");
    for (;;) {
        char buffer[8192];
        ssize_t count = read(input, buffer, sizeof(buffer));
        if (count > 0) {
            ssize_t offset = 0;
            while (offset < count) {
                ssize_t written = write(output, buffer + offset,
                                        count - offset);
                if (written < 0) {
                    if (errno == EINTR)
                        continue;
                    return 3;
                }
                offset += written;
            }
            fsync(output);
            continue;
        }
        if (count < 0 && errno != EAGAIN && errno != EINTR)
            return 4;
        struct pollfd event = { .fd = input, .events = POLLIN };
        poll(&event, 1, 1000);
    }
}

static int run_watchdog(void)
{
    log_line("native boot watchdog started");
    for (int attempt = 0; attempt < 240; ++attempt) {
        if (tcp_ready(5901)) {
            log_line("Android VNC ready; watchdog disarmed");
            return 0;
        }
        sleep(1);
    }

    log_line("Android VNC unavailable after 240 seconds; rebooting to stock");
    sync();
    sleep(2);
    reboot(RB_AUTOBOOT);
    log_line("watchdog reboot failed: %s", strerror(errno));
    return 5;
}

static int run_viewer(void)
{
    int output;
    pid_t viewer;
    int status = -1;

    log_line("native epaper viewer launcher started");
    for (int attempt = 0; attempt < 60; ++attempt) {
        if (tcp_ready(5901))
            break;
        sleep(1);
    }
    if (!tcp_ready(5901)) {
        log_line("viewer launcher: VNC port never became ready");
        return 6;
    }

    if (chroot(HOST_ROOT) < 0 || chdir("/") < 0) {
        log_line("viewer launcher chroot failed: %s", strerror(errno));
        return 7;
    }

    output = open("/native-viewer.log",
                  O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (output >= 0) {
        dup2(output, STDOUT_FILENO);
        dup2(output, STDERR_FILENO);
        if (output > STDERR_FILENO)
            close(output);
    }

    setenv("LANG", "en_US.UTF-8", 1);
    setenv("QT_QUICK_BACKEND", "epaper", 1);
    setenv("RM_VNC_HOST", "127.0.0.1", 1);
    viewer = fork();
    if (viewer < 0)
        return 8;
    if (viewer == 0) {
        execl(HOST_VIEWER, HOST_VIEWER,
              "-platform", "epaper", (char *)NULL);
        dprintf(STDERR_FILENO, "viewer exec failed: %s\n", strerror(errno));
        _exit(127);
    }

    while (waitpid(viewer, &status, 0) < 0 && errno == EINTR)
        ;
    dprintf(STDERR_FILENO, "viewer exited, wait status=%d; "
            "rebooting to stock\n", status);
    sync();
    sleep(2);
    reboot(RB_AUTOBOOT);
    return 9;
}

int main(int argc, char **argv)
{
    if (argc != 2) {
        fprintf(stderr, "usage: %s kmsg|watchdog|viewer\n", argv[0]);
        return 1;
    }
    if (strcmp(argv[1], "kmsg") == 0)
        return run_kmsg_logger();
    if (strcmp(argv[1], "watchdog") == 0)
        return run_watchdog();
    if (strcmp(argv[1], "viewer") == 0)
        return run_viewer();
    return 1;
}

#define _GNU_SOURCE

#include <arpa/inet.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fib_rules.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <sched.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/resource.h>
#include <sys/reboot.h>
#include <sys/syscall.h>
#include <poll.h>
#include <time.h>
#include <unistd.h>

#define ANDROID_ROOT "/android"
#define ANDROID_DATA "/android-data"
#define EXPANDED_ANDROID_DATA "/home/root/native-android-data-v1"
#define EXPANDED_ANDROID_DATA_SENTINEL \
    EXPANDED_ANDROID_DATA "/.paper-expanded-data-v1"
#define BOOT_LOG "/native-boot.log"
#define KMSG_LOG "/native-kmsg.log"
#define SAFE_POWEROFF_REQUEST \
    ANDROID_DATA "/data/com.android.launcher3/files/" \
    "paper-safe-poweroff-request"
#define POWEROFF_IN_PROGRESS "/native-poweroff-in-progress"
#define ROOTB_ERRCNT "/sys/devices/platform/lpgpr/rootb_errcnt"
#define ROOTA_ERRCNT "/sys/devices/platform/lpgpr/roota_errcnt"
#define EMMC_BOOT_PART "/sys/bus/mmc/devices/mmc0:0001/boot_part"
#define BOOT_SELECTOR "/usr/bin/rm-boot-selector"

static void append_line(const char *path, const char *prefix,
                        const char *message, int length)
{
    int fd = open(path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    if (fd < 0)
        return;
    dprintf(fd, "%s%.*s\n", prefix, length, message);
    fsync(fd);
    close(fd);
}

static void log_message(const char *format, ...)
{
    char message[1024];
    va_list args;
    int fd;
    int length;

    va_start(args, format);
    length = vsnprintf(message, sizeof(message), format, args);
    va_end(args);

    if (length < 0)
        return;
    if ((size_t)length >= sizeof(message))
        length = sizeof(message) - 1;

    append_line(BOOT_LOG, "", message, length);

    fd = open("/dev/kmsg", O_WRONLY | O_CLOEXEC);
    if (fd >= 0) {
        dprintf(fd, "<6>rm-android-init: %.*s\n", length, message);
        close(fd);
    }
}

static void make_dir(const char *path, mode_t mode)
{
    if (mkdir(path, mode) < 0 && errno != EEXIST) {
        log_message("mkdir %s failed: %s", path, strerror(errno));
        _exit(120);
    }
}

static bool write_text_file(const char *path, const char *value)
{
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        log_message("open %s failed: %s", path, strerror(errno));
        return false;
    }
    size_t length = strlen(value);
    ssize_t written = write(fd, value, length);
    if (written != (ssize_t)length) {
        log_message("write %s failed: %s", path, strerror(errno));
        close(fd);
        return false;
    }
    close(fd);
    return true;
}

static bool create_text_file(const char *path, const char *value)
{
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) {
        log_message("create %s failed: %s", path, strerror(errno));
        return false;
    }
    size_t length = strlen(value);
    ssize_t written = write(fd, value, length);
    if (written != (ssize_t)length) {
        log_message("write %s failed: %s", path, strerror(errno));
        close(fd);
        return false;
    }
    fsync(fd);
    close(fd);
    return true;
}

static void mount_if_needed(
    const char *source,
    const char *target,
    const char *type,
    unsigned long flags,
    const char *data)
{
    if (mount(source, target, type, flags, data) < 0 &&
        errno != EBUSY) {
        log_message("mount %s on %s failed: %s",
                    source ? source : "none", target, strerror(errno));
        _exit(121);
    }
}

static void bind_mount(const char *source, const char *target)
{
    if (mount(source, target, NULL, MS_BIND | MS_REC, NULL) < 0) {
        log_message("bind mount %s on %s failed: %s",
                    source, target, strerror(errno));
        _exit(122);
    }
}

static void bind_mount_one(const char *source, const char *target)
{
    if (mount(source, target, NULL, MS_BIND, NULL) < 0) {
        log_message("plain bind mount %s on %s failed: %s",
                    source, target, strerror(errno));
        _exit(123);
    }
}

static int run_command(char *const argv[])
{
    pid_t child = fork();
    int status = -1;
    char command[768] = "";

    for (size_t index = 0; argv[index] != NULL; ++index) {
        size_t used = strlen(command);
        snprintf(command + used, sizeof(command) - used, "%s%s",
                 used ? " " : "", argv[index]);
    }

    if (child < 0) {
        log_message("fork for %s failed: %s", command, strerror(errno));
        return -1;
    }
    if (child == 0) {
        execv(argv[0], argv);
        _exit(127);
    }

    while (waitpid(child, &status, 0) < 0 && errno == EINTR)
        ;

    if (!WIFEXITED(status) || WEXITSTATUS(status) != 0)
        log_message("command failed [%s], wait status=%d", command, status);
    return status;
}

static int run_command_with_timeout(
    char *const argv[],
    int timeout_seconds)
{
    pid_t child = fork();
    int status = -1;
    char command[768] = "";

    for (size_t index = 0; argv[index] != NULL; ++index) {
        size_t used = strlen(command);
        snprintf(command + used, sizeof(command) - used, "%s%s",
                 used ? " " : "", argv[index]);
    }

    if (child < 0) {
        log_message("fork for %s failed: %s", command, strerror(errno));
        return -1;
    }
    if (child == 0) {
        setpgid(0, 0);
        execv(argv[0], argv);
        _exit(127);
    }
    setpgid(child, child);

    for (int elapsed = 0; elapsed < timeout_seconds * 10; ++elapsed) {
        pid_t result = waitpid(child, &status, WNOHANG);
        if (result == child) {
            if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) {
                log_message("command failed [%s], wait status=%d",
                            command, status);
            }
            return status;
        }
        if (result < 0 && errno != EINTR) {
            log_message("wait for %s failed: %s",
                        command, strerror(errno));
            return -1;
        }
        usleep(100000);
    }

    log_message("command timed out after %d seconds [%s]",
                timeout_seconds, command);
    kill(-child, SIGKILL);
    kill(child, SIGKILL);
    while (waitpid(child, &status, 0) < 0 && errno == EINTR)
        ;
    return status;
}

static bool command_succeeded(int status)
{
    return WIFEXITED(status) && WEXITSTATUS(status) == 0;
}

static int add_netlink_attribute(struct nlmsghdr *header, size_t capacity,
                                 int type, const void *data, size_t length)
{
    size_t attribute_length = RTA_LENGTH(length);
    size_t message_length = NLMSG_ALIGN(header->nlmsg_len);
    struct rtattr *attribute;

    if (message_length + RTA_ALIGN(attribute_length) > capacity)
        return -EMSGSIZE;
    attribute = (struct rtattr *)((char *)header + message_length);
    attribute->rta_type = type;
    attribute->rta_len = attribute_length;
    memcpy(RTA_DATA(attribute), data, length);
    header->nlmsg_len =
        message_length + RTA_ALIGN(attribute_length);
    return 0;
}

static int ensure_policy_rule(int family, const char *destination,
                              uint8_t prefix_length, uint32_t priority)
{
    struct {
        struct nlmsghdr header;
        struct fib_rule_hdr rule;
        char attributes[128];
    } request;
    struct sockaddr_nl kernel = {
        .nl_family = AF_NETLINK,
    };
    char response[4096];
    uint8_t address[16];
    size_t address_length = family == AF_INET ? 4 : 16;
    int socket_fd;
    ssize_t received;
    unsigned int remaining;

    if (inet_pton(family, destination, address) != 1)
        return -EINVAL;

    memset(&request, 0, sizeof(request));
    request.header.nlmsg_len = NLMSG_LENGTH(sizeof(request.rule));
    request.header.nlmsg_type = RTM_NEWRULE;
    request.header.nlmsg_flags =
        NLM_F_REQUEST | NLM_F_ACK | NLM_F_CREATE | NLM_F_EXCL;
    request.header.nlmsg_seq = (uint32_t)time(NULL);
    request.rule.family = family;
    request.rule.dst_len = prefix_length;
    request.rule.table = RT_TABLE_MAIN;
    request.rule.action = FR_ACT_TO_TBL;

    if (add_netlink_attribute(
            &request.header, sizeof(request), FRA_DST,
            address, address_length) < 0 ||
        add_netlink_attribute(
            &request.header, sizeof(request), FRA_PRIORITY,
            &priority, sizeof(priority)) < 0)
        return -EMSGSIZE;

    socket_fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC,
                       NETLINK_ROUTE);
    if (socket_fd < 0)
        return -errno;
    if (sendto(socket_fd, &request, request.header.nlmsg_len, 0,
               (struct sockaddr *)&kernel, sizeof(kernel)) < 0) {
        int error = -errno;
        close(socket_fd);
        return error;
    }
    received = recv(socket_fd, response, sizeof(response), 0);
    close(socket_fd);
    if (received < 0)
        return -errno;

    remaining = (unsigned int)received;
    for (struct nlmsghdr *header = (struct nlmsghdr *)response;
         NLMSG_OK(header, remaining);
         header = NLMSG_NEXT(header, remaining)) {
        if (header->nlmsg_type == NLMSG_ERROR) {
            const struct nlmsgerr *error =
                (const struct nlmsgerr *)NLMSG_DATA(header);
            if (error->error == 0 || error->error == -EEXIST)
                return 0;
            return error->error;
        }
    }
    return -EIO;
}

static int run_android_command(char *const argv[])
{
    pid_t child = fork();
    int status = -1;

    if (child < 0)
        return -1;
    if (child == 0) {
        if (chroot(ANDROID_ROOT) < 0 || chdir("/") < 0)
            _exit(126);
        execv(argv[0], argv);
        _exit(127);
    }
    while (waitpid(child, &status, 0) < 0 && errno == EINTR)
        ;
    return status;
}

/*
 * Load the stock kernel/firmware pair before Android starts, but leave wlan0
 * otherwise untouched.  Android's NXP vendor HAL, wificond, and HIDL
 * wpa_supplicant own the interface from this point onward.
 */
static bool prepare_native_android_wifi(void)
{
    char *const wlan_down[] = {
        "/sbin/ip", "link", "set", "wlan0", "down", NULL
    };
    char *const set_regdom[] = {
        "/usr/sbin/iw", "reg", "set", "KR", NULL
    };

    for (int attempt = 0; attempt < 80; ++attempt) {
        if (access("/sys/class/net/wlan0", F_OK) == 0)
            break;
        usleep(100000);
    }
    if (access("/sys/class/net/wlan0", F_OK) != 0) {
        log_message("NXP IW61x did not create wlan0; Android will boot "
                    "offline and the one-shot stock fallback remains armed");
        return false;
    }

    if (!command_succeeded(run_command(set_regdom)))
        log_message("could not set KR Wi-Fi regulatory domain; continuing");
    if (!command_succeeded(run_command(wlan_down))) {
        log_message("could not place wlan0 down for Android Wi-Fi HAL");
        return false;
    }
    log_message("NXP IW61x ready as wlan0 for native Android Wi-Fi");
    return true;
}

static void start_kmsg_capture(void)
{
    pid_t child = fork();

    if (child < 0) {
        log_message("kmsg logger fork failed: %s", strerror(errno));
        return;
    }
    if (child != 0)
        return;

    setsid();
    int input = open("/dev/kmsg", O_RDONLY | O_NONBLOCK);
    int output = open(KMSG_LOG, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (input < 0 || output < 0)
        _exit(123);

    dprintf(output, "\n===== native Android boot =====\n");
    fsync(output);
    for (;;) {
        char buffer[4096];
        ssize_t count = read(input, buffer, sizeof(buffer));
        if (count > 0) {
            ssize_t offset = 0;
            while (offset < count) {
                ssize_t written = write(output, buffer + offset, count - offset);
                if (written < 0) {
                    if (errno == EINTR)
                        continue;
                    _exit(124);
                }
                offset += written;
            }
            fsync(output);
            continue;
        }
        /*
         * /dev/kmsg returns EPIPE when this reader falls behind the kernel
         * ring. That is a dropped-log notification, not a fatal device error.
         * Keep capturing so early Android chatter cannot kill diagnostics.
         */
        if (count < 0 && errno == EPIPE) {
            dprintf(output, "\n===== /dev/kmsg reader overrun =====\n");
            fsync(output);
            continue;
        }
        if (count < 0 && errno != EAGAIN && errno != EINTR)
            _exit(125);
        struct pollfd event = { .fd = input, .events = POLLIN };
        poll(&event, 1, 1000);
    }
}

static int run_epd_smoke_test(void)
{
    pid_t child = fork();
    int status = -1;

    if (child < 0) {
        log_message("EPD smoke test fork failed: %s", strerror(errno));
        return -1;
    }
    if (child == 0) {
        setenv("QT_QPA_PLATFORM", "offscreen", 1);
        setenv("QT_QUICK_BACKEND", "software", 1);
        setenv("XDG_RUNTIME_DIR", "/run/rm-epd", 1);
        execl("/usr/bin/rm-epd-test", "rm-epd-test",
              "/epd-test-result.log", NULL);
        _exit(127);
    }

    for (int elapsed = 0; elapsed < 30; ++elapsed) {
        pid_t result = waitpid(child, &status, WNOHANG);
        if (result == child)
            return status;
        if (result < 0 && errno != EINTR) {
            log_message("EPD smoke test wait failed: %s", strerror(errno));
            return -1;
        }
        sleep(1);
    }

    log_message("EPD smoke test timed out after 30 seconds; killing it");
    kill(child, SIGKILL);
    while (waitpid(child, &status, 0) < 0 && errno == EINTR)
        ;
    return status;
}

static bool android_process_running(const char *needle)
{
    DIR *proc = opendir("/proc");
    struct dirent *entry;

    if (!proc)
        return false;
    while ((entry = readdir(proc)) != NULL) {
        char path[512];
        char cmdline[256] = {0};
        char *end;
        int fd;
        ssize_t count;

        if (entry->d_name[0] < '0' || entry->d_name[0] > '9')
            continue;
        (void)strtol(entry->d_name, &end, 10);
        if (*end != '\0')
            continue;
        snprintf(path, sizeof(path), "/proc/%s/cmdline", entry->d_name);
        fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0)
            continue;
        count = read(fd, cmdline, sizeof(cmdline) - 1);
        close(fd);
        if (count > 0 && strstr(cmdline, needle)) {
            closedir(proc);
            return true;
        }
    }
    closedir(proc);
    return false;
}

static bool direct_display_ready(void)
{
    return access("/native-display-ready", F_OK) == 0 &&
           access("/native-touch-ready", F_OK) == 0 &&
           android_process_running("surfaceflinger") &&
           android_process_running(
               "android.hardware.graphics.composer@2.1-service");
}

static pid_t start_epd_bridge(void)
{
    pid_t child;

    unlink("/native-display-ready");
    child = fork();
    if (child < 0) {
        log_message("EPD bridge fork failed: %s", strerror(errno));
        return -1;
    }
    if (child != 0) {
        log_message("native HWC-to-EPD bridge started (host pid=%ld)",
                    (long)child);
        return child;
    }

    setenv("QT_QPA_PLATFORM", "offscreen", 1);
    setenv("QT_QUICK_BACKEND", "software", 1);
    setenv("XDG_RUNTIME_DIR", "/run/rm-epd-bridge", 1);
    execl("/usr/bin/rm-epd-bridge", "rm-epd-bridge", NULL);
    _exit(127);
}

static pid_t start_touch_relay(void)
{
    pid_t child;

    unlink("/native-touch-ready");
    child = fork();
    if (child < 0) {
        log_message("touch relay fork failed: %s", strerror(errno));
        return -1;
    }
    if (child != 0) {
        log_message("physical-to-uinput touch relay started (host pid=%ld)",
                    (long)child);
        return child;
    }

    execl("/usr/bin/rm-touch-relay", "rm-touch-relay",
          "/dev/input/event3", "/native-touch-relay.log", NULL);
    _exit(127);
}

static pid_t start_native_controls(void)
{
    pid_t child = fork();

    if (child < 0) {
        log_message("native controls fork failed: %s", strerror(errno));
        return -1;
    }
    if (child != 0) {
        log_message("Paper Home native controls started (host pid=%ld)",
                    (long)child);
        return child;
    }

    execl("/usr/bin/rm-native-controls", "rm-native-controls", NULL);
    _exit(127);
}

static pid_t start_tee_supplicant(void)
{
    pid_t child = fork();

    if (child < 0) {
        log_message("TEE supplicant fork failed: %s", strerror(errno));
        return -1;
    }
    if (child != 0) {
        log_message("stock TEE supplicant started (host pid=%ld)",
                    (long)child);
        return child;
    }

    execl("/usr/sbin/tee-supplicant", "tee-supplicant", NULL);
    _exit(127);
}

static bool prepare_expanded_android_data(pid_t *tee_child)
{
    const char *encrypted_home_device =
        "/dev/mapper/home-encrypted-disk";
    char *const keystore_init[] = {
        "/usr/sbin/keystore", "init", NULL
    };
    char *const homecryptor_start[] = {
        /*
         * systemd gives homecryptor.service a private session keyring.
         * Recreate that contract here: homekey and dmsetup must inherit the
         * same keyring or dm-crypt reports ENOKEY even though homekey itself
         * successfully talked to OP-TEE.
         */
        "/usr/bin/keyctl", "session", "paper-android-home",
        "/usr/sbin/homecryptor", "start", NULL
    };

    if (access("/usr/sbin/tee-supplicant", X_OK) != 0 ||
        access("/usr/sbin/keystore", X_OK) != 0 ||
        access("/usr/sbin/homecryptor", X_OK) != 0 ||
        access("/usr/bin/keyctl", X_OK) != 0) {
        log_message("expanded Android data unavailable: stock crypto "
                    "helpers are missing");
        return false;
    }

    /*
     * Paper Pro Move keeps the home-volume key material on its small
     * persistent data partition. Mount it exactly as stock userspace does,
     * then ask the stock OP-TEE helpers to expose the encrypted home volume.
     */
    make_dir("/data", 0755);
    if (mount("/dev/mmcblk0p1", "/data", "ext4", MS_SYNCHRONOUS,
              "data=journal,journal_checksum") < 0 &&
        errno != EBUSY) {
        log_message("expanded Android data unavailable: stock data mount "
                    "failed: %s", strerror(errno));
        return false;
    }

    *tee_child = start_tee_supplicant();
    if (*tee_child < 0)
        return false;
    usleep(500000);
    if (kill(*tee_child, 0) < 0) {
        log_message("expanded Android data unavailable: TEE supplicant "
                    "exited during startup");
        return false;
    }
    if (!command_succeeded(
            run_command_with_timeout(keystore_init, 15))) {
        log_message("expanded Android data unavailable: keystore init "
                    "failed");
        return false;
    }
    if (!command_succeeded(
            run_command_with_timeout(homecryptor_start, 20))) {
        log_message("expanded Android data unavailable: encrypted home "
                    "mapping failed");
        return false;
    }

    make_dir("/home", 0755);
    /*
     * dmsetup creates dm-0, but without stock udev there may be no
     * /dev/mapper/home-encrypted-disk convenience symlink.
     */
    if (access(encrypted_home_device, F_OK) != 0 &&
        access("/dev/dm-0", F_OK) == 0) {
        encrypted_home_device = "/dev/dm-0";
        log_message("encrypted home mapper symlink absent; using dm-0");
    }
    if (mount(encrypted_home_device, "/home", "ext4", 0, NULL) < 0 &&
        errno != EBUSY) {
        log_message("expanded Android data unavailable: encrypted home "
                    "mount failed: %s", strerror(errno));
        return false;
    }
    if (access(EXPANDED_ANDROID_DATA_SENTINEL, R_OK) != 0) {
        log_message("expanded Android data not provisioned; using internal "
                    "data");
        return false;
    }
    if (mount(EXPANDED_ANDROID_DATA, ANDROID_DATA, NULL,
              MS_BIND | MS_REC, NULL) < 0) {
        log_message("expanded Android data bind failed: %s",
                    strerror(errno));
        return false;
    }

    log_message("expanded Android data enabled from encrypted home volume");
    return true;
}

static pid_t start_usb_dhcp(void)
{
    pid_t child = fork();

    if (child < 0) {
        log_message("USB diagnostic DHCP fork failed: %s", strerror(errno));
        return -1;
    }
    if (child != 0) {
        log_message("USB diagnostic DHCP started (host pid=%ld)",
                    (long)child);
        return child;
    }

    execl("/usr/sbin/udhcpd", "udhcpd", "-f", "-S",
          "/etc/paperhome/udhcpd-usb.conf", NULL);
    _exit(127);
}

static bool configure_usb_diagnostics(pid_t *dhcp_child)
{
    char *const usb_gadget[] = {
        "/usr/sbin/usb-ether-once", NULL
    };
    char *const usb_address[] = {
        "/sbin/ip", "address", "replace",
        "10.11.99.1/27", "dev", "usb0", NULL
    };
    char *const usb_up[] = {
        "/sbin/ip", "link", "set", "usb0", "up", NULL
    };
    if (access("/usr/sbin/usb-ether-once", X_OK) != 0 ||
        access("/etc/paperhome/udhcpd-usb.conf", R_OK) != 0) {
        log_message("USB diagnostic support files are unavailable");
        return false;
    }
    if (!command_succeeded(
            run_command_with_timeout(usb_gadget, 8))) {
        log_message("stock USB Ethernet gadget setup failed");
        return false;
    }
    for (int attempt = 0; attempt < 50; ++attempt) {
        if (access("/sys/class/net/usb0", F_OK) == 0)
            break;
        usleep(100000);
    }
    if (access("/sys/class/net/usb0", F_OK) != 0 ||
        !command_succeeded(run_command(usb_address)) ||
        !command_succeeded(run_command(usb_up))) {
        log_message("USB Ethernet gadget did not expose usb0");
        return false;
    }
    *dhcp_child = start_usb_dhcp();
    if (*dhcp_child < 0)
        return false;
    log_message("USB ADB diagnostics ready at 10.11.99.1:5555");
    return true;
}

static pid_t start_usb_route_guard(void)
{
    pid_t child = fork();

    if (child < 0) {
        log_message("USB policy route guard fork failed: %s",
                    strerror(errno));
        return -1;
    }
    if (child != 0) {
        log_message("USB policy route guard started (host pid=%ld)",
                    (long)child);
        return child;
    }

    int last_ipv4 = -EAGAIN;
    int last_ipv6 = -EAGAIN;

    setsid();
    for (;;) {
        int ipv4 = ensure_policy_rule(
            AF_INET, "10.11.99.0", 27, 10500);
        int ipv6 = ensure_policy_rule(
            AF_INET6, "fe80::", 64, 10500);

        if (ipv4 != last_ipv4) {
            if (ipv4 == 0)
                log_message("USB IPv4 policy route ready");
            else
                log_message("USB IPv4 policy route failed: %s",
                            strerror(-ipv4));
            last_ipv4 = ipv4;
        }
        if (ipv6 != last_ipv6) {
            if (ipv6 == 0)
                log_message("USB IPv6 policy route ready");
            else
                log_message("USB IPv6 policy route failed: %s",
                            strerror(-ipv6));
            last_ipv6 = ipv6;
        }
        sleep(5);
    }
}

static bool select_persistent_android_boot(const char *reason)
{
    char *const select_android_boot[] = {
        "/usr/bin/mmc", "bootpart", "enable", "2", "0",
        "/dev/mmcblk0boot0", NULL
    };
    char boot_part = '\0';
    char error_count = '\0';
    int fd;
    ssize_t read_count;

    /*
     * The boot ROM increments rootb_errcnt on every B-slot attempt. The old
     * one-shot policy deliberately left it at the fallback threshold, so a
     * successful safe shutdown still cold-booted stock. Commit B only after
     * the native display watchdog has declared Android healthy, and commit it
     * again immediately before an intentional power-off.
     */
    if (!write_text_file(ROOTB_ERRCNT, "0\n")) {
        log_message("%s: cannot clear Android boot error count", reason);
        return false;
    }
    if (!command_succeeded(run_command(select_android_boot))) {
        log_message("%s: cannot select Android boot partition", reason);
        return false;
    }

    fd = open(ROOTB_ERRCNT, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        read_count = read(fd, &error_count, 1);
        if (read_count != 1)
            error_count = '\0';
        close(fd);
    }
    fd = open(EMMC_BOOT_PART, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        read_count = read(fd, &boot_part, 1);
        if (read_count != 1)
            boot_part = '\0';
        close(fd);
    }
    if (error_count != '0' || boot_part != '2') {
        log_message("%s: Android boot verification failed "
                    "(rootb_errcnt=%c boot_part=%c)",
                    reason,
                    error_count ? error_count : '?',
                    boot_part ? boot_part : '?');
        return false;
    }

    sync();
    log_message("%s: persistent Android boot committed "
                "(rootb_errcnt=0 boot_part=2)", reason);
    return true;
}

static bool select_stock_boot(const char *reason)
{
    char *const select_stock[] = {
        "/usr/bin/mmc", "bootpart", "enable", "1", "0",
        "/dev/mmcblk0boot0", NULL
    };
    char boot_part = '\0';
    ssize_t read_count;
    int fd;

    if (!write_text_file(ROOTA_ERRCNT, "0\n")) {
        log_message("%s: cannot clear stock boot error count", reason);
        return false;
    }
    if (!command_succeeded(run_command(select_stock))) {
        log_message("%s: cannot select stock boot partition", reason);
        return false;
    }
    fd = open(EMMC_BOOT_PART, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        read_count = read(fd, &boot_part, 1);
        if (read_count != 1)
            boot_part = '\0';
        close(fd);
    }
    if (boot_part != '1') {
        log_message("%s: stock boot verification failed (boot_part=%c)",
                    reason, boot_part ? boot_part : '?');
        return false;
    }

    sync();
    log_message("%s: stock boot committed (roota_errcnt=0 boot_part=1)",
                reason);
    return true;
}

static pid_t start_safe_poweroff_guard(void)
{
    pid_t child = fork();

    if (child < 0) {
        log_message("safe power-off guard fork failed: %s",
                    strerror(errno));
        return -1;
    }
    if (child != 0) {
        log_message("safe power-off guard started (host pid=%ld)",
                    (long)child);
        return child;
    }

    char *const android_shutdown[] = {
        "/system/bin/setprop", "sys.powerctl", "shutdown", NULL
    };

    setsid();
    for (;;) {
        if (access(SAFE_POWEROFF_REQUEST, F_OK) != 0) {
            sleep(1);
            continue;
        }

        unlink(SAFE_POWEROFF_REQUEST);
        if (!select_persistent_android_boot("safe power-off")) {
            log_message("safe power-off refused: cannot commit Android "
                        "as the next boot");
            sleep(2);
            continue;
        }
        if (!create_text_file(POWEROFF_IN_PROGRESS, "1\n")) {
            log_message("safe power-off refused: cannot create marker");
            sleep(2);
            continue;
        }

        log_message("safe power-off: Android boot selected; requesting "
                    "framework shutdown");
        sync();
        (void)run_android_command(android_shutdown);

        /*
         * Android shutdown normally exits the nested PID namespace. The host
         * PID 1 path below sees POWEROFF_IN_PROGRESS and powers the hardware
         * off instead of applying its ordinary crash fallback to stock.
         */
        for (int elapsed = 0; elapsed < 15; ++elapsed)
            sleep(1);

        log_message("safe power-off: Android did not exit in 15 seconds; "
                    "using synchronized host power-off");
        sync();
        sleep(1);
        reboot(RB_POWER_OFF);
        log_message("safe host power-off failed: %s", strerror(errno));
        _exit(127);
    }
}

static pid_t start_android_tuning(void)
{
    pid_t child = fork();

    if (child < 0) {
        log_message("Android e-ink tuning fork failed: %s", strerror(errno));
        return -1;
    }
    if (child != 0) {
        log_message("Android e-ink tuning sidecar started (host pid=%ld)",
                    (long)child);
        return child;
    }

    char *const overlay_appop[] = {
        "/system/bin/appops", "set", "com.android.launcher3",
        "SYSTEM_ALERT_WINDOW", "allow", NULL
    };
    char *const window_animation[] = {
        "/system/bin/settings", "put", "global",
        "window_animation_scale", "0", NULL
    };
    char *const transition_animation[] = {
        "/system/bin/settings", "put", "global",
        "transition_animation_scale", "0", NULL
    };
    char *const animator_duration[] = {
        "/system/bin/settings", "put", "global",
        "animator_duration_scale", "0", NULL
    };
    char *const wifi_background_scan[] = {
        "/system/bin/settings", "put", "global",
        "wifi_scan_always_enabled", "0", NULL
    };
    char *const ble_background_scan[] = {
        "/system/bin/settings", "put", "global",
        "ble_scan_always_enabled", "0", NULL
    };
    char *const mobile_data_always_on[] = {
        "/system/bin/settings", "put", "global",
        "mobile_data_always_on", "0", NULL
    };
    char *const app_standby[] = {
        "/system/bin/settings", "put", "global",
        "app_standby_enabled", "1", NULL
    };
    char *const adaptive_battery[] = {
        "/system/bin/settings", "put", "global",
        "adaptive_battery_management_enabled", "1", NULL
    };
    char *const doze_setting[] = {
        "/system/bin/settings", "put", "secure",
        "doze_enabled", "1", NULL
    };
    char *const enable_device_idle[] = {
        "/system/bin/cmd", "deviceidle", "enable", NULL
    };

    sleep(20);
    for (int attempt = 0; attempt < 10; ++attempt) {
        bool window_ok =
            command_succeeded(run_android_command(window_animation));
        bool transition_ok =
            command_succeeded(run_android_command(transition_animation));
        bool animator_ok =
            command_succeeded(run_android_command(animator_duration));

        (void)run_android_command(overlay_appop);
        if (window_ok && transition_ok && animator_ok) {
            int idle_tuning_ok = 0;
            idle_tuning_ok += command_succeeded(
                run_android_command(wifi_background_scan));
            idle_tuning_ok += command_succeeded(
                run_android_command(ble_background_scan));
            idle_tuning_ok += command_succeeded(
                run_android_command(mobile_data_always_on));
            idle_tuning_ok += command_succeeded(
                run_android_command(app_standby));
            idle_tuning_ok += command_succeeded(
                run_android_command(adaptive_battery));
            idle_tuning_ok += command_succeeded(
                run_android_command(doze_setting));
            idle_tuning_ok += command_succeeded(
                run_android_command(enable_device_idle));
            log_message("Android animations disabled; floating display "
                        "control permission requested; idle tuning=%d/7",
                        idle_tuning_ok);
            _exit(0);
        }
        sleep(3);
    }
    log_message("Android e-ink tuning could not reach settings service");
    _exit(125);
}

static void start_boot_watchdog(bool stream_probe_mode)
{
    pid_t child = fork();

    if (child < 0) {
        log_message("boot watchdog fork failed: %s", strerror(errno));
        return;
    }
    if (child != 0)
        return;

    setsid();
    const int timeout = stream_probe_mode ? 90 : 180;
    for (int attempt = 0; attempt < timeout; ++attempt) {
        if (stream_probe_mode) {
            if (attempt % 10 == 0) {
                log_message("stream probe watchdog status: elapsed=%ds "
                            "surfaceflinger=%d composer=%d vncserver=%d",
                            attempt,
                            android_process_running("surfaceflinger"),
                            android_process_running(
                                "android.hardware.graphics.composer@2.1-service"),
                            android_process_running("/vendor/bin/vncserver"));
            }
            sleep(1);
            continue;
        }
        if (direct_display_ready()) {
            log_message("boot watchdog: native Android EPD display is ready");
            if (!select_persistent_android_boot("healthy Android boot"))
                log_message("healthy Android boot could not replace the "
                            "one-shot stock fallback");
            _exit(0);
        }
        if (attempt % 10 == 0) {
            log_message("boot watchdog status: elapsed=%ds epd_bridge=%d "
                        "touch_relay=%d "
                        "surfaceflinger=%d composer=%d",
                        attempt,
                        access("/native-display-ready", F_OK) == 0,
                        access("/native-touch-ready", F_OK) == 0,
                        android_process_running("surfaceflinger"),
                        android_process_running(
                            "android.hardware.graphics.composer@2.1-service"));
        }
        sleep(1);
    }

    if (stream_probe_mode) {
        log_message("stream probe capture window completed after %d seconds; "
                    "returning to stock slot", timeout);
    } else {
        log_message("boot watchdog: no native Android EPD display after "
                    "%d seconds; returning to stock slot", timeout);
    }
    sync();
    sleep(2);
    reboot(RB_AUTOBOOT);
    log_message("boot watchdog reboot failed: %s", strerror(errno));
    _exit(126);
}

static void prepare_kernel_filesystems(void)
{
    make_dir("/dev", 0755);
    mount_if_needed("devtmpfs", "/dev", "devtmpfs",
                    MS_NOSUID, "mode=0755");
    make_dir("/proc", 0555);
    mount_if_needed("proc", "/proc", "proc",
                    MS_NOSUID | MS_NOEXEC | MS_NODEV, NULL);
    make_dir("/sys", 0555);
    mount_if_needed("sysfs", "/sys", "sysfs",
                    MS_NOSUID | MS_NOEXEC | MS_NODEV, NULL);
    /*
     * usb-ether-once builds the stock RNDIS/ECM gadget through ConfigFS.
     * The native Android init used to omit this mount, so the script failed
     * before usb0 existed even though the UDC and Windows driver were ready.
     */
    make_dir("/sys/kernel/config", 0755);
    mount_if_needed("configfs", "/sys/kernel/config", "configfs",
                    MS_NOSUID | MS_NOEXEC | MS_NODEV, NULL);
    make_dir("/run", 0755);
    mount_if_needed("tmpfs", "/run", "tmpfs",
                    MS_NOSUID | MS_NODEV, "mode=0755");
    make_dir("/tmp", 01777);
    mount_if_needed("tmpfs", "/tmp", "tmpfs",
                    MS_NOSUID | MS_NODEV,
                    "mode=1777,size=32m");

    make_dir("/dev/pts", 0755);
    mount_if_needed("devpts", "/dev/pts", "devpts",
                    MS_NOSUID | MS_NOEXEC,
                    "mode=0620,ptmxmode=0666,gid=5");
    unlink("/dev/ptmx");
    symlink("pts/ptmx", "/dev/ptmx");

    make_dir("/dev/shm", 01777);
    mount_if_needed("shm", "/dev/shm", "tmpfs",
                    MS_NOSUID | MS_NOEXEC | MS_NODEV,
                    "mode=1777,size=64m");
    make_dir("/dev/mqueue", 0755);
    mount_if_needed("mqueue", "/dev/mqueue", "mqueue",
                    MS_NOSUID | MS_NOEXEC | MS_NODEV, NULL);

    make_dir("/dev/binderfs", 0755);
    mount_if_needed("binder", "/dev/binderfs", "binder", 0, NULL);
    unlink("/dev/binder");
    unlink("/dev/hwbinder");
    unlink("/dev/vndbinder");
    symlink("binderfs/binder", "/dev/binder");
    symlink("binderfs/hwbinder", "/dev/hwbinder");
    symlink("binderfs/vndbinder", "/dev/vndbinder");
    chmod("/dev/binderfs/binder", 0666);
    chmod("/dev/binderfs/hwbinder", 0666);
    chmod("/dev/binderfs/vndbinder", 0666);

    make_dir("/sys/fs/bpf", 0755);
    mount_if_needed("bpf", "/sys/fs/bpf", "bpf", 0, NULL);
}

static void prepare_cgroups(void)
{
    static const struct {
        const char *directory;
        const char *options;
    } controllers[] = {
        { "cpu,cpuacct", "cpu,cpuacct" },
        { "freezer", "freezer" },
        { "devices", "devices" },
        { "hugetlb", "hugetlb" },
        { "blkio", "blkio" },
        { "pids", "pids" },
    };
    char path[256];

    make_dir("/sys/fs/cgroup", 0755);
    mount_if_needed("tmpfs", "/sys/fs/cgroup", "tmpfs",
                    MS_NOSUID | MS_NOEXEC | MS_NODEV,
                    "mode=0755,size=4m");

    make_dir("/sys/fs/cgroup/unified", 0755);
    mount_if_needed("cgroup2", "/sys/fs/cgroup/unified", "cgroup2",
                    MS_NOSUID | MS_NOEXEC | MS_NODEV,
                    "nsdelegate");

    for (size_t index = 0;
         index < sizeof(controllers) / sizeof(controllers[0]);
         ++index) {
        snprintf(path, sizeof(path), "/sys/fs/cgroup/%s",
                 controllers[index].directory);
        make_dir(path, 0755);
        mount_if_needed("cgroup", path, "cgroup",
                        MS_NOSUID | MS_NOEXEC | MS_NODEV,
                        controllers[index].options);
    }

    symlink("cpu,cpuacct", "/sys/fs/cgroup/cpu");
    symlink("cpu,cpuacct", "/sys/fs/cgroup/cpuacct");

    /*
     * Android's libprocessgroup looks up the v1 CPU and blkio hierarchies at
     * /dev/cpuctl and /dev/blkio. The stock kernel does not provide cpuset or
     * memcg, but making the available controllers visible at their Android
     * paths restores foreground/top-app CPU policy instead of logging
     * "Failed to find cpu cgroup" for every scheduling change.
     */
    make_dir("/dev/cpuctl", 0755);
    bind_mount("/sys/fs/cgroup/cpu,cpuacct", "/dev/cpuctl");
    make_dir("/dev/blkio", 0755);
    bind_mount("/sys/fs/cgroup/blkio", "/dev/blkio");
}

static void prepare_android_swap(void)
{
    const char *mkswap_path =
        access("/sbin/mkswap", X_OK) == 0
            ? "/sbin/mkswap"
            : "/android/system/bin/mkswap";
    char *const zram_init[] = {
        "/usr/libexec/zram-swap-init", "/dev/zram0", NULL
    };
    char *const zram_on[] = {
        "/sbin/swapon", "-p", "100", "/dev/zram0", NULL
    };
    char *const disk_swap_on[] = {
        "/sbin/swapon", "-p", "10", "/dev/dm-1", NULL
    };
    char *disk_swap_format[] = {
        (char *)mkswap_path, "/dev/dm-1", NULL
    };

    /*
     * zram-swap-init loads/configures zram on stock Paper OS. Do not test
     * for /sys/block/zram0 before running it: that short-circuits the helper
     * precisely when the module still needs to be initialized.
     */
    if (access("/usr/libexec/zram-swap-init", X_OK) != 0 ||
        !command_succeeded(run_command(zram_init)) ||
        access("/sys/block/zram0/disksize", F_OK) != 0) {
        log_message("zram initialization failed; continuing with disk swap");
    } else if (!command_succeeded(run_command(zram_on))) {
        log_message("zram swap activation failed");
    } else {
        log_message("Android zram swap enabled");
    }

    /*
     * swap-encrypted-disk is recreated with a fresh key every boot, so its
     * old swap signature is intentionally lost and must be regenerated.
     */
    if (access("/dev/dm-1", F_OK) == 0 &&
        access(mkswap_path, X_OK) == 0 &&
        command_succeeded(run_command(disk_swap_format)) &&
        command_succeeded(run_command(disk_swap_on))) {
        log_message("Android encrypted disk swap enabled");
    } else {
        log_message("encrypted disk swap unavailable");
    }

    write_text_file("/proc/sys/vm/swappiness", "80\n");
    write_text_file("/proc/sys/vm/page-cluster", "0\n");
}

static void prepare_android_performance(void)
{
    write_text_file(
        "/sys/devices/system/cpu/cpufreq/policy0/scaling_governor",
        "performance\n");
    write_text_file("/sys/block/mmcblk0/queue/read_ahead_kb", "256\n");
    log_message("Android foreground performance governor enabled");
}

static void prepare_android_mounts(void)
{
    char path[256];

    /* Android's SetupMountNamespaces requires its future / to be a mount. */
    bind_mount_one(ANDROID_ROOT, ANDROID_ROOT);

    snprintf(path, sizeof(path), "%s/data", ANDROID_ROOT);
    make_dir(path, 0771);
    bind_mount(ANDROID_DATA, path);

    /*
     * Share only Redroid's local graphics/input IPC directory with the tiny
     * host supervisor. This is the native HWC path; no VNC transport or
     * reMarkable userspace is involved.
     */
    make_dir("/run/rm-ipc", 0777);
    chmod("/run/rm-ipc", 0777);
    snprintf(path, sizeof(path), "%s/ipc", ANDROID_ROOT);
    make_dir(path, 0777);
    bind_mount_one("/run/rm-ipc", path);

    snprintf(path, sizeof(path), "%s/dev", ANDROID_ROOT);
    make_dir(path, 0755);
    bind_mount("/dev", path);

    snprintf(path, sizeof(path), "%s/proc", ANDROID_ROOT);
    make_dir(path, 0555);
    bind_mount("/proc", path);

    snprintf(path, sizeof(path), "%s/sys", ANDROID_ROOT);
    make_dir(path, 0555);
    bind_mount("/sys", path);

    /*
     * Do not expose the old host root inside Android. Diagnostics persist in
     * the separately bound Android data directory and graphics/input cross
     * the narrowly scoped /ipc mount above.
     */
}

int main(void)
{
    struct rlimit nofile = {
        .rlim_cur = 65536,
        .rlim_max = 65536,
    };
    const char *const android_modules[] = {
        /*
         * Paper Pro Move keeps its Elan marker/touch controller as a module.
         * Stock systemd loads it, but this native PID 1 intentionally does
         * not start stock userspace, so load it explicitly before Android's
         * ueventd performs its input-device coldboot.
         */
        /*
         * The reading-light controller is modular as well. Without this,
         * Android can change its logical brightness while the physical
         * rm_frontlight sysfs device never exists.
         */
        /*
         * usb-ether-once waits indefinitely for ci_hdrc.{0,1}. Stock
         * systemd loads ci_hdrc_imx before running that script; native PID 1
         * must do the same. The USB command also has its own hard timeout so
         * diagnostics can never block Android boot again.
         */
        "zram", "max77818_charger", "ci_hdrc_imx", "fusb303b", "elants_spi",
        "aw99703_bl", "iw61x_sdw61x",
        "uinput", "veth",
        "bridge", "br_netfilter",
        "tun", "fuse",
        "overlay", "nfnetlink", "nfnetlink_log", "nfnetlink_queue",
        "nfnetlink_acct", "x_tables", "ip_tables", "iptable_filter",
        "iptable_mangle", "iptable_nat", "iptable_raw", "ip6_tables",
        "ip6table_filter", "ip6table_mangle", "ip6table_nat",
        "ip6table_raw", "ipt_REJECT", "ip6t_REJECT", "xt_CHECKSUM",
        "xt_LOG", "xt_MASQUERADE", "xt_NFLOG", "xt_TCPMSS",
        "xt_addrtype", "xt_bpf", "xt_comment", "xt_connmark",
        "xt_conntrack", "xt_mark", "xt_multiport", "xt_nat",
        "xt_owner", "xt_policy", "xt_state", "xt_tcpudp", "xt_u32",
        NULL
    };
    char *const ip_link[] = {
        "/sbin/ip", "link", "set", "lo", "up", NULL
    };
    char *const arm_stock_fallback[] = {
        "/usr/bin/mmc", "bootpart", "enable", "1", "0",
        "/dev/mmcblk0boot0", NULL
    };
    char *const android_argv[] = {
        "/init",
        "qemu=1",
        "androidboot.hardware=redroid",
        "androidboot.redroid_width=954",
        "androidboot.redroid_height=1696",
        "androidboot.redroid_dpi=264",
        "androidboot.redroid_fps=30",
        "androidboot.redroid_gpu_mode=guest",
        "androidboot.use_memfd=true",
        "androidboot.use_redroid_stream=1",
        "androidboot.use_redroid_vnc=0",
        "androidboot.redroid_net_ndns=1",
        "androidboot.redroid_net_dns1=1.1.1.1",
        "wlan.interface=wlan0",
        "ro.hardware.gralloc=redroid",
        "ro.hardware.hwcomposer=redroid",
        "ro.hardware.egl=angle",
        "ro.hardware.vulkan=pastel",
        "dalvik.vm.dex2oat64.enabled=true",
        "ro.config.low_ram=true",
        "ro.secure=0",
        "ro.adb.secure=1",
        NULL
    };
    char *const android_env[] = {
        "PATH=/system/bin:/system/xbin:/vendor/bin",
        "ANDROID_ROOT=/system",
        "ANDROID_DATA=/data",
        "TMPDIR=/data/local/tmp",
        NULL
    };
    pid_t android_child;
    pid_t epd_bridge_child = -1;
    pid_t touch_relay_child = -1;
    pid_t native_controls_child = -1;
    pid_t usb_dhcp_child = -1;
    pid_t usb_route_guard_child = -1;
    pid_t safe_poweroff_guard_child = -1;
    pid_t android_tuning_child = -1;
    pid_t tee_supplicant_child = -1;
    int child_status = -1;
    bool stream_probe_mode = false;
    bool native_wifi = false;

    if (getpid() != 1) {
        fprintf(stderr, "rm-android-init must run as PID 1\n");
        return 125;
    }

    if (mount(NULL, "/", NULL, MS_REMOUNT, NULL) < 0)
        fprintf(stderr, "root remount rw failed: %s\n", strerror(errno));
    prepare_kernel_filesystems();
    unlink(POWEROFF_IN_PROGRESS);
    unlink(SAFE_POWEROFF_REQUEST);
    log_message("native Android boot wrapper started");
    chmod("/dev/dri/card0", 0666);
    chmod("/dev/input/event0", 0666);
    chmod("/dev/input/event1", 0666);
    chmod("/dev/input/event2", 0666);
    chmod("/dev/input/event3", 0666);
    write_text_file(
        "/proc/sys/kernel/printk_devkmsg", "ratelimit\n");
    write_text_file("/proc/sys/kernel/printk_ratelimit", "5\n");
    write_text_file("/proc/sys/kernel/printk_ratelimit_burst", "20\n");

    /*
     * Switch the next boot back to the untouched A slot before running any
     * experimental display code. The smoke-test marker deliberately bypasses
     * Android and exercises only the stock 3.27.3 EPFramebuffer engine.
     */
    log_message("arming one-shot stock fallback");
    child_status = run_command(arm_stock_fallback);
    if (!WIFEXITED(child_status) || WEXITSTATUS(child_status) != 0) {
        log_message("cannot arm stock fallback; refusing experimental boot");
        sync();
        sleep(2);
        reboot(RB_AUTOBOOT);
        return 126;
    }

    if (access("/enable-native-kmsg", F_OK) == 0) {
        start_kmsg_capture();
        log_message("developer kmsg capture enabled by marker");
    } else {
        log_message("production boot: persistent kmsg capture disabled");
    }
    if (access("/epd-smoke-test", F_OK) == 0) {
        log_message("EPD smoke-test marker found; Android will not start");
        child_status = run_epd_smoke_test();
        if (WIFEXITED(child_status) && WEXITSTATUS(child_status) == 0) {
            log_message("EPD smoke test succeeded; keeping pattern for "
                        "15 seconds");
            unlink("/epd-smoke-test");
            sync();
            sleep(15);
        } else {
            log_message("EPD smoke test failed, wait status=%d; "
                        "returning to stock", child_status);
            unlink("/epd-smoke-test");
            sync();
            sleep(3);
        }
        reboot(RB_AUTOBOOT);
        log_message("EPD smoke-test reboot failed: %s", strerror(errno));
        return 127;
    }
    if (access("/stream-probe-test", F_OK) == 0) {
        stream_probe_mode = true;
        unlink("/stream-probe-test");
        log_message("HWC stream probe marker found; capture window is "
                    "90 seconds");
    }

    log_message("mounting Android cgroup hierarchy");
    prepare_cgroups();
    prepare_android_performance();
    if (setrlimit(RLIMIT_NOFILE, &nofile) < 0)
        log_message("setrlimit RLIMIT_NOFILE failed: %s", strerror(errno));

    log_message("loading Android support modules");
    for (size_t index = 0; android_modules[index] != NULL; ++index) {
        char *modprobe[] = {
            "/sbin/modprobe", (char *)android_modules[index], NULL
        };
        run_command(modprobe);
    }
    prepare_android_swap();
    if (!prepare_expanded_android_data(&tee_supplicant_child))
        log_message("continuing with internal 4 GB Android data");
    /*
     * ANDROID_DATA may have changed from the slot-B directory to the encrypted
     * expanded-data bind above. Clear a request left by an interrupted prior
     * boot only after that final data source is visible.
     */
    unlink(SAFE_POWEROFF_REQUEST);
    if (!configure_usb_diagnostics(&usb_dhcp_child))
        log_message("continuing without live USB ADB diagnostics");
    /*
     * elants_spi registers marker as event2 and multitouch as event3. Give
     * firmware probing a bounded moment to finish, then make the real input
     * nodes available even before Android ueventd applies its own policy.
     */
    for (int attempt = 0; attempt < 30; ++attempt) {
        if (access("/dev/input/event3", F_OK) == 0)
            break;
        usleep(100000);
    }
    for (int event = 0; event < 8; ++event) {
        char event_path[64];
        snprintf(event_path, sizeof(event_path),
                 "/dev/input/event%d", event);
        if (access(event_path, F_OK) == 0)
            chmod(event_path, 0666);
    }
    if (access("/dev/input/event3", F_OK) == 0) {
        log_message("Elan marker/touch input ready on event2/event3");
        /*
         * Match the stock xochitl startup state.  The controller itself marks
         * broad contacts as MT_TOOL_PALM; rm-touch-relay then prevents those
         * slots from being promoted to ordinary Android fingers.
         */
        write_text_file(
            "/sys/bus/spi/devices/spi0.0/elants_ktf/palm_reject",
            "1\n");
        log_message("Elan hardware palm rejection requested");
    } else {
        log_message("Elan touch event3 did not appear after module load");
    }
    if (!stream_probe_mode && access(BOOT_SELECTOR, X_OK) == 0) {
        char *const boot_selector[] = {BOOT_SELECTOR, NULL};
        int selector_status;

        log_message("showing native E-ink boot selector");
        /*
         * A full Gallery panel update can take almost five seconds before the
         * menu is visible. Leave enough host-watchdog margin for the complete
         * five-second touch window and orderly EPFramebuffer shutdown.
         */
        selector_status = run_command_with_timeout(boot_selector, 15);
        if (WIFEXITED(selector_status) &&
            WEXITSTATUS(selector_status) == 20) {
            log_message("boot selector chose stock OS");
            if (!select_stock_boot("boot selector")) {
                log_message("stock selection refused; continuing Android");
            } else {
                sync();
                sleep(1);
                reboot(RB_AUTOBOOT);
                log_message("stock selection reboot failed: %s",
                            strerror(errno));
                return 126;
            }
        }
        if (!command_succeeded(selector_status)) {
            log_message("boot selector failed or timed out; defaulting to "
                        "Android");
        } else {
            log_message("boot selector chose Android or timed out");
        }
    } else if (!stream_probe_mode) {
        log_message("native E-ink boot selector unavailable; continuing "
                    "Android");
    }
    if (!stream_probe_mode) {
        touch_relay_child = start_touch_relay();
        if (touch_relay_child < 0) {
            log_message("cannot start touch relay; returning to stock");
            sync();
            sleep(2);
            reboot(RB_AUTOBOOT);
            return 126;
        }
        for (int attempt = 0; attempt < 30; ++attempt) {
            if (access("/native-touch-ready", F_OK) == 0)
                break;
            usleep(100000);
        }
        if (access("/native-touch-ready", F_OK) != 0) {
            log_message("touch relay did not become ready; returning to stock");
            sync();
            sleep(2);
            reboot(RB_AUTOBOOT);
            return 126;
        }
        /*
         * The uinput relay appears as event4 after the four physical input
         * devices.  It is created before Android's ueventd starts, so its
         * devtmpfs node retains mode 0600 and EventHub cannot open it unless
         * the host supervisor fixes the mode here.  Cover additional event
         * numbers as well so the relay remains readable if enumeration moves.
         */
        for (int event = 0; event < 16; ++event) {
            char event_path[64];
            snprintf(event_path, sizeof(event_path),
                     "/dev/input/event%d", event);
            if (access(event_path, F_OK) == 0 &&
                chmod(event_path, 0666) < 0) {
                log_message("chmod %s after touch relay failed: %s",
                            event_path, strerror(errno));
            }
        }
        log_message("uinput touch relay nodes made Android-readable");
    }
    log_message("configuring native Android network");
    run_command(ip_link);
    native_wifi = prepare_native_android_wifi();
    if (!native_wifi)
        log_message("native Android Wi-Fi unavailable at handoff");
    /*
     * Keep only the stock-OS reboot request outside Android. Wi-Fi scanning,
     * authentication, DHCP, and saved networks are now fully Android-owned.
     */
    native_controls_child = start_native_controls();
    if (native_controls_child < 0)
        log_message("continuing without Paper Home native controls");
    log_message("preparing Android bind mounts");
    prepare_android_mounts();
    usb_route_guard_child = start_usb_route_guard();
    if (!stream_probe_mode) {
        epd_bridge_child = start_epd_bridge();
        if (epd_bridge_child < 0) {
            log_message("cannot start native EPD bridge; returning to stock");
            sync();
            sleep(2);
            reboot(RB_AUTOBOOT);
            return 126;
        }
    }
    start_boot_watchdog(stream_probe_mode);

    /*
     * Android init kills pre-existing processes visible in its PID namespace.
     * Give it a fresh PID namespace so it remains PID 1 there while the tiny
     * native supervisor, logger, and watchdog survive outside.
     * No reMarkable systemd/userspace is started beneath Android.
     */
    if (unshare(CLONE_NEWPID) < 0) {
        log_message("creating Android PID namespace failed: %s",
                    strerror(errno));
        return 126;
    }
    android_child = fork();
    if (android_child < 0) {
        log_message("forking Android namespace init failed: %s",
                    strerror(errno));
        return 126;
    }
    if (android_child == 0) {
        /*
         * A chroot alone leaves /proc/self/maps paths prefixed with /android.
         * ART 12 treats those as non-allowlisted APEX files and aborts while
         * forking system_server. Give Android its own mount namespace and
         * make its tree the actual VFS root, as runc/pivot_root would.
         */
        if (unshare(CLONE_NEWNS) < 0) {
            log_message("creating Android mount namespace failed: %s",
                        strerror(errno));
            _exit(126);
        }
        if (mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) < 0) {
            log_message("making Android mounts private failed: %s",
                        strerror(errno));
            _exit(126);
        }
        if (chdir(ANDROID_ROOT) < 0) {
            log_message("Android root chdir failed: %s", strerror(errno));
            _exit(126);
        }
        if (mkdir("rm-old-root", 0700) < 0 && errno != EEXIST) {
            log_message("creating pivot old-root failed: %s",
                        strerror(errno));
            _exit(126);
        }
        if (syscall(SYS_pivot_root, ".", "rm-old-root") < 0) {
            log_message("Android pivot_root failed: %s", strerror(errno));
            _exit(126);
        }
        if (chdir("/") < 0) {
            log_message("Android post-pivot chdir failed: %s",
                        strerror(errno));
            _exit(126);
        }
        if (umount2("/rm-old-root", MNT_DETACH) < 0) {
            log_message("detaching Android old root failed: %s",
                        strerror(errno));
            _exit(126);
        }
        rmdir("/rm-old-root");
        if (umount2("/proc", MNT_DETACH) < 0 && errno != EINVAL) {
            log_message("detaching inherited Android proc failed: %s",
                        strerror(errno));
            _exit(126);
        }
        make_dir("/proc", 0555);
        if (mount("proc", "/proc", "proc",
                  MS_NOSUID | MS_NOEXEC | MS_NODEV, NULL) < 0) {
            log_message("mounting Android PID-namespace proc failed: %s",
                        strerror(errno));
            _exit(126);
        }

        execve("/init", android_argv, android_env);
        _exit(127);
    }

    log_message("Android namespace init started (host pid=%ld)",
                (long)android_child);
    safe_poweroff_guard_child = start_safe_poweroff_guard();
    if (safe_poweroff_guard_child < 0)
        log_message("continuing without automatic safe power-off");
    android_tuning_child = start_android_tuning();
    for (;;) {
        pid_t exited = waitpid(-1, &child_status, 0);
        if (exited < 0) {
            if (errno == EINTR)
                continue;
            log_message("PID 1 waitpid failed: %s", strerror(errno));
            break;
        }
        if (exited == android_child) {
            if (access(POWEROFF_IN_PROGRESS, F_OK) == 0) {
                log_message("Android namespace completed intentional "
                            "power-off");
                sync();
                sleep(1);
                reboot(RB_POWER_OFF);
                log_message("intentional hardware power-off failed: %s",
                            strerror(errno));
                return 127;
            }
            if (WIFSIGNALED(child_status)) {
                log_message("Android namespace init killed by signal %d; "
                            "returning to stock",
                            WTERMSIG(child_status));
            } else if (WIFEXITED(child_status)) {
                log_message("Android namespace init exited with code %d; "
                            "returning to stock",
                            WEXITSTATUS(child_status));
            } else {
                log_message("Android namespace init ended, wait status=%d; "
                            "returning to stock", child_status);
            }
            break;
        }
        if (exited == epd_bridge_child) {
            if (WIFSIGNALED(child_status)) {
                log_message("native EPD bridge killed by signal %d; "
                            "returning to stock",
                            WTERMSIG(child_status));
            } else if (WIFEXITED(child_status)) {
                log_message("native EPD bridge exited with code %d; "
                            "returning to stock",
                            WEXITSTATUS(child_status));
            } else {
                log_message("native EPD bridge ended, wait status=%d; "
                            "returning to stock", child_status);
            }
            break;
        }
        if (exited == touch_relay_child) {
            if (WIFSIGNALED(child_status)) {
                log_message("touch relay killed by signal %d; "
                            "returning to stock",
                            WTERMSIG(child_status));
            } else if (WIFEXITED(child_status)) {
                log_message("touch relay exited with code %d; "
                            "returning to stock",
                            WEXITSTATUS(child_status));
            } else {
                log_message("touch relay ended, wait status=%d; "
                            "returning to stock", child_status);
            }
            break;
        }
        if (exited == native_controls_child) {
            log_message("Paper Home native controls exited, wait status=%d; "
                        "restarting controls", child_status);
            sleep(1);
            native_controls_child = start_native_controls();
            continue;
        }
        if (exited == usb_dhcp_child) {
            log_message("USB diagnostic DHCP exited, wait status=%d; "
                        "restarting", child_status);
            sleep(1);
            usb_dhcp_child = start_usb_dhcp();
            continue;
        }
        if (exited == usb_route_guard_child) {
            log_message("USB policy route guard exited, wait status=%d; "
                        "restarting", child_status);
            sleep(1);
            usb_route_guard_child = start_usb_route_guard();
            continue;
        }
        if (exited == safe_poweroff_guard_child) {
            if (access(POWEROFF_IN_PROGRESS, F_OK) == 0) {
                log_message("safe power-off guard ended during shutdown; "
                            "powering hardware off");
                sync();
                sleep(1);
                reboot(RB_POWER_OFF);
                return 127;
            }
            log_message("safe power-off guard exited, wait status=%d; "
                        "restarting", child_status);
            sleep(1);
            safe_poweroff_guard_child = start_safe_poweroff_guard();
            continue;
        }
        if (exited == android_tuning_child) {
            log_message("Android e-ink tuning sidecar exited, wait status=%d",
                        child_status);
            android_tuning_child = -1;
            continue;
        }
        if (exited == tee_supplicant_child) {
            log_message("stock TEE supplicant exited, wait status=%d; "
                        "restarting", child_status);
            sleep(2);
            tee_supplicant_child = start_tee_supplicant();
            continue;
        }
        log_message("sidecar host pid %ld exited, wait status=%d",
                    (long)exited, child_status);
    }

    if (!select_stock_boot("Android supervisor failure"))
        log_message("cannot commit stock fallback before supervisor reboot");
    sync();
    sleep(2);
    reboot(RB_AUTOBOOT);
    log_message("supervisor reboot failed: %s", strerror(errno));
    return 127;
}

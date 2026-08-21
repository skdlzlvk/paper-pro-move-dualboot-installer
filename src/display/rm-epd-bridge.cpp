#include <QByteArray>
#include <QColor>
#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDateTime>
#include <QFile>
#include <QFont>
#include <QFontDatabase>
#include <QGuiApplication>
#include <QImage>
#include <QMetaEnum>
#include <QMetaMethod>
#include <QObject>
#include <QPainter>
#include <QPolygonF>
#include <QProcess>
#include <QRect>

#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <fcntl.h>
#include <linux/input.h>
#include <memory>
#include <mutex>
#include <poll.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>
#include <vector>

#include "epframebuffer.h"

namespace {

constexpr char SocketPath[] = "/run/rm-ipc/hwcomposer.sock";
// Keep diagnostics inside Android's persistent data bind so they remain
// readable over ADB without exposing the host root inside the container.
constexpr char LogPath[] =
    "/android-data/local/tmp/native-epd-bridge.log";
constexpr char ReadyPath[] = "/native-display-ready";
constexpr char TouchActivePath[] = "/native-touch-active";
constexpr char TypingActivePath[] =
    "/android-data/data/com.android.launcher3/files/paper-typing-active";
constexpr char RefreshRequestPath[] =
    "/android-data/data/com.android.launcher3/files/paper-refresh-request";
constexpr char DisplayProfilePath[] =
    "/android-data/data/com.android.launcher3/files/paper-display-mode";
constexpr char ColorModePath[] =
    "/android-data/data/com.android.launcher3/files/paper-color-mode";
constexpr char InvertModePath[] =
    "/android-data/data/com.android.launcher3/files/paper-invert-mode";
constexpr char ScreenStatePath[] =
    "/android-data/data/com.android.launcher3/files/paper-screen-state";
constexpr char LockStylePath[] =
    "/android-data/data/com.android.launcher3/files/paper-lock-style";
constexpr char AutoPowerOffPath[] =
    "/android-data/data/com.android.launcher3/files/"
    "paper-auto-poweroff-minutes";
constexpr char DeviceBatteryPath[] =
    "/sys/class/power_supply/max77818_battery/capacity";
constexpr char ChargerOnlinePath[] =
    "/sys/class/power_supply/max77818-charger/online";
constexpr char KoreanFontPath[] =
    "/android/system/fonts/NotoSansCJK-Regular.ttc";
constexpr char UiLocalePath[] =
    "/android-data/data/com.android.launcher3/files/paper-ui-locale";
constexpr char LegacyUiLocalePath[] =
    "/android-data/paper-ui-locale";
constexpr char NoteActivePath[] =
    "/android-data/data/com.android.launcher3/files/paper-note-active";
constexpr char NoteToolPath[] =
    "/android-data/data/com.android.launcher3/files/paper-note-tool";
constexpr char NoteSizePath[] =
    "/android-data/data/com.android.launcher3/files/paper-note-size";
constexpr char NoteEraserSizePath[] =
    "/android-data/data/com.android.launcher3/files/paper-eraser-size";
constexpr char NoteUiBottomPath[] =
    "/android-data/data/com.android.launcher3/files/paper-note-ui-bottom";
constexpr char NoteUiLeftPath[] =
    "/android-data/data/com.android.launcher3/files/paper-note-ui-left";
constexpr char NoteUiRegionsPath[] =
    "/android-data/data/com.android.launcher3/files/paper-note-ui-regions";
constexpr char NoteOverlayResetPath[] =
    "/android-data/data/com.android.launcher3/files/paper-note-overlay-reset";
constexpr char NoteToolbarRefreshPath[] =
    "/android-data/data/com.android.launcher3/files/paper-note-toolbar-refresh";
constexpr char ReaderRefreshPolicyPath[] =
    "/android-data/data/com.android.launcher3/files/paper-reader-refresh";
constexpr char ReaderActivePath[] =
    "/android-data/data/com.android.launcher3/files/paper-reader-active";
constexpr char GhostControlPolicyPath[] =
    "/android-data/data/com.android.launcher3/files/paper-ghost-control";
constexpr char GhostRequestPath[] =
    "/android-data/data/com.android.launcher3/files/paper-ghost-request";
constexpr char TextContrastPath[] =
    "/android-data/data/com.android.launcher3/files/paper-text-contrast";
constexpr char DeveloperBridgeCandidatePath[] =
    "/android-data/local/tmp/rm-epd-bridge.next";
constexpr char DeveloperBridgeUpdatePath[] =
    "/android-data/local/tmp/paper-bridge-update";
constexpr char DeveloperLauncherCandidatePath[] =
    "/android-data/local/tmp/PaperHome.apk.next";
constexpr char DeveloperLauncherUpdatePath[] =
    "/android-data/local/tmp/paper-home-update";
constexpr char DeveloperLauncherTargetPath[] =
    "/android/system/system_ext/priv-app/Launcher3QuickStep/"
    "Launcher3QuickStep.apk";
constexpr char DeveloperLauncherBackupPath[] =
    "/android-data/local/tmp/Launcher3QuickStep.apk.before-r12";
constexpr int AndroidWidth = 954;
constexpr int AndroidHeight = 1696;
constexpr int NavigationBarHeight = 79;
constexpr int BytesPerPixel = 4;
constexpr int AndroidStrideBytes = AndroidWidth * BytesPerPixel;
constexpr size_t VisibleBytes =
    static_cast<size_t>(AndroidStrideBytes) * AndroidHeight;
constexpr int NativeHandleBytes = 64;
constexpr uint32_t NativeHandleVersion = 12;
constexpr uint32_t NativeHandleFds = 1;
constexpr uint32_t NativeHandleInts = 8;
constexpr uint32_t RedroidHandleMagic = 0x03141592;
QString uiFontFamily;
enum class UiLanguage { English, Korean, SimplifiedChinese };
UiLanguage uiLanguage = UiLanguage::English;
enum class LockStyle { Fade, Reading, Clean, Clock, Classic };
constexpr uint32_t MaximumBufferBytes = 32 * 1024 * 1024;
constexpr int NativePenSubmitIntervalMs = 16;
constexpr int NativePenPriorityTailMs = 48;
/*
 * A monochrome cleanup 650 ms after every pen lift visibly flashed between
 * Korean characters and made the next stroke feel blocked. Keep native Pen
 * mode continuous across ordinary writing pauses and stabilize the accumulated
 * dirty area only after a genuine idle period.
 */
/*
 * Stock keeps the fast pen waveform visible until the Marker has been idle
 * for a comparatively long time.  An 8 s cleanup was easy to collide with:
 * the asynchronous Mono waveform could still be settling when the next
 * stroke began.  Keep the immediate ink longer and never start cleanup while
 * the Marker is in proximity.
 */
constexpr int NativePenCleanupDelayMs = 30000;
constexpr int NativeEraserCleanupDelayMs = 450;
constexpr int NativePenControlPollMs = 250;
constexpr int AndroidFrameIntervalMs = 30;
constexpr int DamageTileSize = 32;
constexpr int DamagePadding = 4;
constexpr int DamageMergeGap = 8;
constexpr int MaximumDamageRectangles = 8;
/*
 * Finger and keyboard interaction benefit from a shorter queue interval and
 * the binary Fast conversion. Keep the boost short, then repaint from the
 * retained RGB frame in the user's selected profile after interaction ends.
 */
constexpr int TouchFrameIntervalMs = 18;
constexpr int TouchFastTailMs = 220;
constexpr int TouchSettleDelayMs = 520;
constexpr int TouchStatePollMs = 24;
constexpr int ColorSettleDelayMs = 2200;
constexpr int ColorControlPollMs = 200;
constexpr int ColorAutoCooldownMs = 12000;
constexpr int ColorMaximumCooldownMs = 60000;
constexpr int ColorSampleStep = 2;
constexpr int ColorChannelThreshold = 18;
constexpr int ColorTileSize = 64;
constexpr int ColorPadding = 16;
constexpr int MaximumColorRectangles = 4;
constexpr int ToolbarHeight = 238;
constexpr qint64 GhostBudgetScreenMultiples = 5;
constexpr int GhostCleanupIdleMs = 1800;
/*
 * Reader page turns repaint most of the panel at once. Stock xochitl routes
 * comparable view changes through its GhostBuster, while the Android side had
 * no notion of a page and only the five-screen ghost budget above cleaned up,
 * so a reader accumulated ghosting for five or more pages (physical evidence
 * 2026-08-21). A page turn is a non-pen, non-interactive damage covering at
 * least ReaderPageAreaPercent of the panel while a reader app is in the
 * foreground. After the selected number of turns the page is repainted once
 * from the retained RGB frame with the stable Grayscale waveform while idle,
 * or handed to the stock ghost control when that policy is selected.
 */
constexpr int ReaderPageAreaPercent = 60;
/*
 * Kindle paints a turned page twice about 300 ms apart (text, then the
 * re-laid-out page with its footer); both frames cover the panel. Anything
 * closer than this gap is the same page (physical log, 2026-08-21).
 */
constexpr int ReaderPageMinimumGapMs = 600;
constexpr int ReaderCleanupIdleMs = 700;
constexpr int ReaderDefaultPagesPerCleanup = 5;
constexpr int ReaderPolicyPollMs = 1000;
constexpr qint64 PanelArea =
    static_cast<qint64>(AndroidWidth) * AndroidHeight;

volatile sig_atomic_t stopRequested = 0;
QFile *logFile = nullptr;
std::mutex logMutex;

void loadUiLocale()
{
    QFile file(QString::fromLatin1(UiLocalePath));
    if (!file.open(QIODevice::ReadOnly)) {
        file.setFileName(QString::fromLatin1(LegacyUiLocalePath));
        if (!file.open(QIODevice::ReadOnly))
            return;
    }
    const QByteArray locale = file.read(32).trimmed().toLower();
    if (locale == QByteArrayLiteral("ko") ||
        locale.startsWith(QByteArrayLiteral("ko-")) ||
        locale.startsWith(QByteArrayLiteral("ko_"))) {
        uiLanguage = UiLanguage::Korean;
    } else if (locale == QByteArrayLiteral("zh") ||
               locale.startsWith(QByteArrayLiteral("zh-")) ||
               locale.startsWith(QByteArrayLiteral("zh_"))) {
        uiLanguage = UiLanguage::SimplifiedChinese;
    }
}

QString uiText(const char *english, const char *korean, const char *chinese)
{
    if (uiLanguage == UiLanguage::Korean)
        return QString::fromUtf8(korean);
    if (uiLanguage == UiLanguage::SimplifiedChinese)
        return QString::fromUtf8(chinese);
    return QString::fromUtf8(english);
}

QString uiLanguageName()
{
    if (uiLanguage == UiLanguage::Korean)
        return QStringLiteral("ko-KR");
    if (uiLanguage == UiLanguage::SimplifiedChinese)
        return QStringLiteral("zh-CN");
    return QStringLiteral("en-US");
}

enum class DisplayProfile {
    Fast,
    Balanced,
    Quality,
};

enum class ColorMode {
    Mono,
    Auto,
    OnceAuto,
    OnceMono,
};

void requestStop(int)
{
    stopRequested = 1;
}

void writeLog(const QString &message)
{
    const std::lock_guard<std::mutex> lock(logMutex);
    const QString line =
        QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs) +
        QStringLiteral(" ") + message + QLatin1Char('\n');
    const QByteArray encoded = line.toUtf8();

    if (logFile != nullptr && logFile->isOpen()) {
        logFile->write(encoded);
        logFile->flush();
    }
    ::write(STDERR_FILENO, encoded.constData(),
            static_cast<size_t>(encoded.size()));
}

bool replaceSettingLine(const char *path, const QByteArray &prefix,
                        const QByteArray &replacement)
{
    QFile input(QString::fromLatin1(path));
    if (!input.exists())
        return true;
    if (!input.open(QIODevice::ReadOnly))
        return false;
    struct stat metadata {};
    const bool haveMetadata = ::stat(path, &metadata) == 0;
    QByteArray contents = input.readAll();
    input.close();

    QList<QByteArray> lines = contents.split('\n');
    bool replaced = false;
    for (QByteArray &line : lines) {
        if (line.startsWith(prefix)) {
            line = replacement;
            replaced = true;
        }
    }
    if (!replaced)
        lines.append(replacement);
    QByteArray updated = lines.join('\n');
    if (!updated.endsWith('\n'))
        updated.append('\n');

    const QByteArray temporary = QByteArray(path) + ".paper-update";
    QFile::remove(QString::fromLocal8Bit(temporary));
    QFile output(QString::fromLocal8Bit(temporary));
    if (!output.open(QIODevice::WriteOnly | QIODevice::Truncate) ||
        output.write(updated) != updated.size() || !output.flush()) {
        output.close();
        QFile::remove(QString::fromLocal8Bit(temporary));
        return false;
    }
    output.close();
    const mode_t mode = haveMetadata ? metadata.st_mode & 07777 : 0644;
    ::chmod(temporary.constData(), mode == 0 ? 0644 : mode);
    if (haveMetadata)
        ::chown(temporary.constData(), metadata.st_uid, metadata.st_gid);
    if (::rename(temporary.constData(), path) != 0) {
        QFile::remove(QString::fromLocal8Bit(temporary));
        return false;
    }
    return true;
}

void applyDeveloperLauncherUpdate()
{
    if (::access(DeveloperLauncherUpdatePath, F_OK) != 0)
        return;

    QFile request(QString::fromLatin1(DeveloperLauncherUpdatePath));
    QFile candidate(QString::fromLatin1(DeveloperLauncherCandidatePath));
    if (!request.open(QIODevice::ReadOnly) ||
        !candidate.open(QIODevice::ReadOnly)) {
        writeLog(QStringLiteral(
            "developer launcher update rejected: request/candidate unreadable"));
        ::unlink(DeveloperLauncherUpdatePath);
        return;
    }
    const QByteArray expected = request.read(128).trimmed().toLower();
    const bool validHex = expected.size() == 64 &&
        std::all_of(expected.cbegin(), expected.cend(), [](char value) {
            return (value >= '0' && value <= '9') ||
                   (value >= 'a' && value <= 'f');
        });
    const qint64 candidateSize = candidate.size();
    const QByteArray actual = QCryptographicHash::hash(
        candidate.readAll(), QCryptographicHash::Sha256).toHex();
    candidate.close();
    if (!validHex || actual != expected || candidateSize < 32768 ||
        candidateSize > 8 * 1024 * 1024) {
        writeLog(QStringLiteral(
            "developer launcher update rejected: invalid metadata/hash"));
        ::unlink(DeveloperLauncherUpdatePath);
        return;
    }

    const QString target = QString::fromLatin1(DeveloperLauncherTargetPath);
    const QString temporary = target + QStringLiteral(".paper-update");
    QFile::remove(temporary);
    if (!QFile::exists(QString::fromLatin1(DeveloperLauncherBackupPath)) &&
        !QFile::copy(target,
                     QString::fromLatin1(DeveloperLauncherBackupPath))) {
        writeLog(QStringLiteral(
            "developer launcher update rejected: backup failed"));
        ::unlink(DeveloperLauncherUpdatePath);
        return;
    }
    if (!QFile::copy(QString::fromLatin1(DeveloperLauncherCandidatePath),
                     temporary) ||
        ::chmod(temporary.toLocal8Bit().constData(), 0644) != 0 ||
        ::rename(temporary.toLocal8Bit().constData(),
                 DeveloperLauncherTargetPath) != 0) {
        QFile::remove(temporary);
        writeLog(QStringLiteral(
            "developer launcher update rejected: atomic install failed: %1")
            .arg(QString::fromLocal8Bit(std::strerror(errno))));
        ::unlink(DeveloperLauncherUpdatePath);
        return;
    }

    const QByteArray launcherLine = "launcher_hash=" + actual;
    const QByteArray manifestLine = "paper_home_sha256=" + actual;
    bool metadataOk = true;
    const std::array<const char *, 5> bootScripts = {{
        "/home/root/xovi/exthome/appload/android-os/boot-android.sh",
        "/home/root/native-android/boot-android16-once.sh",
        "/home/root/boot-android16-once-20260804.sh",
        "/root/boot-android16-once-20260804.sh",
        "/boot-android16-once-20260804.sh"}};
    for (const char *path : bootScripts)
        metadataOk &= replaceSettingLine(
            path, QByteArrayLiteral("launcher_hash="), launcherLine);
    const std::array<const char *, 3> manifests = {{
        "/android16-install-manifest",
        "/home/root/android16-install-manifest",
        "/root/android16-install-manifest"}};
    for (const char *path : manifests) {
        metadataOk &= replaceSettingLine(
            path, QByteArrayLiteral("paper_home_sha256="), manifestLine);
        metadataOk &= replaceSettingLine(
            path, QByteArrayLiteral("hardware_hotfix="),
            QByteArrayLiteral("hardware_hotfix=20260805-r12"));
    }

    const char oatPath[] =
        "/android/system/system_ext/priv-app/Launcher3QuickStep/oat";
    const char oatBackup[] =
        "/android/system/system_ext/priv-app/Launcher3QuickStep/"
        "oat.before-r12";
    if (::access(oatPath, F_OK) == 0 && ::access(oatBackup, F_OK) != 0)
        ::rename(oatPath, oatBackup);

    ::unlink(DeveloperLauncherUpdatePath);
    ::sync();
    writeLog(QStringLiteral(
        "developer launcher update accepted sha256=%1 metadata=%2")
        .arg(QString::fromLatin1(actual))
        .arg(metadataOk ? QStringLiteral("ok") :
                          QStringLiteral("warning")));
}

void applyDeveloperBridgeUpdate()
{
    if (::access(DeveloperBridgeUpdatePath, F_OK) != 0)
        return;

    QFile request(QString::fromLatin1(DeveloperBridgeUpdatePath));
    QFile candidate(QString::fromLatin1(DeveloperBridgeCandidatePath));
    if (!request.open(QIODevice::ReadOnly) ||
        !candidate.open(QIODevice::ReadOnly)) {
        writeLog(QStringLiteral(
            "developer bridge update rejected: request/candidate unreadable"));
        ::unlink(DeveloperBridgeUpdatePath);
        return;
    }

    const QByteArray expected =
        request.read(128).trimmed().toLower();
    const bool validHex =
        expected.size() == 64 &&
        std::all_of(expected.cbegin(), expected.cend(),
                    [](char value) {
                        return (value >= '0' && value <= '9') ||
                               (value >= 'a' && value <= 'f');
                    });
    const qint64 candidateSize = candidate.size();
    if (!validHex || candidateSize < 65536 ||
        candidateSize > 4 * 1024 * 1024) {
        writeLog(QStringLiteral(
                     "developer bridge update rejected: invalid metadata "
                     "size=%1 hash_chars=%2")
                     .arg(candidateSize)
                     .arg(expected.size()));
        ::unlink(DeveloperBridgeUpdatePath);
        return;
    }

    const QByteArray actual =
        QCryptographicHash::hash(
            candidate.readAll(), QCryptographicHash::Sha256).toHex();
    candidate.close();
    if (actual != expected) {
        writeLog(QStringLiteral(
                     "developer bridge update rejected: sha256 mismatch "
                     "expected=%1 actual=%2")
                     .arg(QString::fromLatin1(expected))
                     .arg(QString::fromLatin1(actual)));
        ::unlink(DeveloperBridgeUpdatePath);
        return;
    }

    if (::chmod(DeveloperBridgeCandidatePath, 0755) != 0) {
        writeLog(QStringLiteral(
                     "developer bridge update rejected: chmod failed: %1")
                     .arg(QString::fromLocal8Bit(std::strerror(errno))));
        ::unlink(DeveloperBridgeUpdatePath);
        return;
    }

    ::unlink(DeveloperBridgeUpdatePath);
    ::sync();
    writeLog(QStringLiteral(
                 "developer bridge update accepted sha256=%1; exec")
                 .arg(QString::fromLatin1(actual)));

    /*
     * HWC, marker input and the stock framebuffer backend all keep device
     * descriptors open. exec() normally inherits them, which leaves the old
     * HWC session and SWTCON framebuffer owner alive inside the new image.
     * Close every non-standard descriptor immediately before exec so the
     * replacement reconnects as a genuinely fresh bridge process.
     */
#ifdef SYS_close_range
    if (::syscall(SYS_close_range, 3u, ~0u, 0u) != 0) {
        const long maximum = std::max(1024L, ::sysconf(_SC_OPEN_MAX));
        for (int descriptor = 3;
             descriptor < std::min(maximum, 65536L);
             ++descriptor) {
            ::close(descriptor);
        }
    }
#else
    const long maximum = std::max(1024L, ::sysconf(_SC_OPEN_MAX));
    for (int descriptor = 3;
         descriptor < std::min(maximum, 65536L);
         ++descriptor) {
        ::close(descriptor);
    }
#endif
    ::execl(DeveloperBridgeCandidatePath,
            "rm-epd-bridge", static_cast<char *>(nullptr));
    _exit(125);
}

bool runHostCommand(const char *program, char *const arguments[])
{
    const pid_t child = ::fork();
    if (child < 0) {
        writeLog(QStringLiteral("host command fork failed for %1: %2")
                     .arg(QString::fromLatin1(program))
                     .arg(QString::fromLocal8Bit(std::strerror(errno))));
        return false;
    }
    if (child == 0) {
        ::execv(program, arguments);
        _exit(127);
    }

    int status = -1;
    while (::waitpid(child, &status, 0) < 0 && errno == EINTR)
        ;
    const bool succeeded =
        WIFEXITED(status) && WEXITSTATUS(status) == 0;
    if (!succeeded) {
        writeLog(QStringLiteral("host command failed %1 status=%2")
                     .arg(QString::fromLatin1(program))
                     .arg(status));
    }
    return succeeded;
}

void ensureUsbTypeCNegotiation()
{
    char *const chargerArguments[] = {
        const_cast<char *>("/sbin/modprobe"),
        const_cast<char *>("max77818_charger"),
        nullptr,
    };
    char *const modprobeArguments[] = {
        const_cast<char *>("/sbin/modprobe"),
        const_cast<char *>("fusb303b"),
        nullptr,
    };
    char *const usbUpArguments[] = {
        const_cast<char *>("/sbin/ip"),
        const_cast<char *>("link"),
        const_cast<char *>("set"),
        const_cast<char *>("usb0"),
        const_cast<char *>("up"),
        nullptr,
    };
    constexpr char QueuePath[] =
        "/sys/bus/i2c/devices/1-0021/"
        "queue_charging_negotiation";

    const bool chargerReady =
        ::access("/sys/bus/platform/devices/max77818-charger/driver",
                 F_OK) == 0 ||
        runHostCommand("/sbin/modprobe", chargerArguments);
    bool moduleReady =
        ::access("/sys/bus/i2c/devices/1-0021/driver", F_OK) == 0;
    if (!moduleReady && chargerReady) {
        runHostCommand("/sbin/modprobe", modprobeArguments);
        const int bind = ::open(
            "/sys/bus/i2c/drivers/fusb303b/bind",
            O_WRONLY | O_CLOEXEC);
        if (bind >= 0) {
            const ssize_t written = ::write(bind, "1-0021\n", 7);
            ::close(bind);
            moduleReady = written == 7 ||
                          ::access(
                              "/sys/bus/i2c/devices/1-0021/driver",
                              F_OK) == 0;
        }
    }
    QFile carrierFile(
        QStringLiteral("/sys/class/net/usb0/carrier"));
    const bool carrierActive =
        carrierFile.open(QIODevice::ReadOnly) &&
        carrierFile.read(8).trimmed() == "1";
    bool negotiationQueued = carrierActive;
    if (moduleReady && !carrierActive) {
        for (int attempt = 0;
             attempt < 30 && ::access(QueuePath, W_OK) != 0;
             ++attempt) {
            ::usleep(100000);
        }
        const int queue = ::open(QueuePath, O_WRONLY | O_CLOEXEC);
        if (queue >= 0) {
            negotiationQueued = ::write(queue, "1\n", 2) == 2;
            ::close(queue);
        } else {
            writeLog(QStringLiteral(
                         "USB Type-C negotiation control unavailable: %1")
                         .arg(QString::fromLocal8Bit(
                             std::strerror(errno))));
        }
    }
    const bool usbUp =
        ::access("/sys/class/net/usb0", F_OK) == 0 &&
        runHostCommand("/sbin/ip", usbUpArguments);
    writeLog(QStringLiteral(
                 "USB Type-C recovery charger=%1 module=%2 "
                 "carrier=%3 negotiation=%4 usb0_up=%5")
                 .arg(chargerReady)
                 .arg(moduleReady)
                 .arg(carrierActive)
                 .arg(negotiationQueued)
                 .arg(usbUp));
}

void ensureUsbPolicyRoute()
{
    QProcess rules;
    rules.start(QStringLiteral("/sbin/ip"),
                {QStringLiteral("rule"), QStringLiteral("show")});
    if (rules.waitForFinished(2000) &&
        rules.readAllStandardOutput().contains(
            "to 10.11.99.0/27 lookup main")) {
        writeLog(QStringLiteral("USB policy route already active"));
        return;
    }

    char *const routeArguments[] = {
        const_cast<char *>("/sbin/ip"),
        const_cast<char *>("rule"),
        const_cast<char *>("add"),
        const_cast<char *>("priority"),
        const_cast<char *>("10500"),
        const_cast<char *>("to"),
        const_cast<char *>("10.11.99.0/27"),
        const_cast<char *>("lookup"),
        const_cast<char *>("main"),
        nullptr,
    };
    const bool routeReady =
        runHostCommand("/sbin/ip", routeArguments);
    writeLog(QStringLiteral("USB policy route installed=%1")
                 .arg(routeReady));
}

void qtMessageHandler(QtMsgType type,
                      const QMessageLogContext &,
                      const QString &message)
{
    const char *level = "debug";
    switch (type) {
    case QtInfoMsg:
        level = "info";
        break;
    case QtWarningMsg:
        level = "warning";
        break;
    case QtCriticalMsg:
        level = "critical";
        break;
    case QtFatalMsg:
        level = "fatal";
        break;
    default:
        break;
    }
    writeLog(QStringLiteral("qt[%1] %2")
                 .arg(QString::fromLatin1(level), message));
    if (type == QtFatalMsg)
        std::abort();
}

uint32_t readLe32(const std::array<unsigned char, NativeHandleBytes> &bytes,
                  size_t offset)
{
    return static_cast<uint32_t>(bytes[offset]) |
           (static_cast<uint32_t>(bytes[offset + 1]) << 8) |
           (static_cast<uint32_t>(bytes[offset + 2]) << 16) |
           (static_cast<uint32_t>(bytes[offset + 3]) << 24);
}

bool readExact(int fd, void *data, size_t bytes)
{
    auto *output = static_cast<unsigned char *>(data);
    size_t offset = 0;

    while (offset < bytes && !stopRequested) {
        struct pollfd ready = {
            .fd = fd,
            .events = POLLIN,
            .revents = 0,
        };
        const int pollResult = ::poll(&ready, 1, 1000);
        if (pollResult == 0)
            continue;
        if (pollResult < 0) {
            if (errno == EINTR)
                continue;
            return false;
        }
        if (!(ready.revents & POLLIN))
            return false;
        const ssize_t count =
            ::recv(fd, output + offset, bytes - offset, MSG_NOSIGNAL);
        if (count > 0) {
            offset += static_cast<size_t>(count);
            continue;
        }
        if (count < 0 && errno == EINTR)
            continue;
        return false;
    }
    return offset == bytes;
}

int receiveSharedFd(int socketFd)
{
    unsigned char marker = 0;
    struct iovec iov = {
        .iov_base = &marker,
        .iov_len = sizeof(marker),
    };
    alignas(struct cmsghdr)
        unsigned char control[CMSG_SPACE(sizeof(int))] = {};
    struct msghdr message = {};
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);

    while (!stopRequested) {
        struct pollfd ready = {
            .fd = socketFd,
            .events = POLLIN,
            .revents = 0,
        };
        const int pollResult = ::poll(&ready, 1, 1000);
        if (pollResult == 0)
            continue;
        if (pollResult < 0 && errno == EINTR)
            continue;
        if (pollResult < 0 || !(ready.revents & POLLIN))
            return -1;
        break;
    }
    if (stopRequested)
        return -1;

    ssize_t count;
    do {
        count = ::recvmsg(socketFd, &message, MSG_CMSG_CLOEXEC);
    } while (count < 0 && errno == EINTR);
    if (count != 1)
        return -1;

    for (struct cmsghdr *header = CMSG_FIRSTHDR(&message);
         header != nullptr;
         header = CMSG_NXTHDR(&message, header)) {
        if (header->cmsg_level == SOL_SOCKET &&
            header->cmsg_type == SCM_RIGHTS &&
            header->cmsg_len >= CMSG_LEN(sizeof(int))) {
            int receivedFd = -1;
            std::memcpy(&receivedFd, CMSG_DATA(header), sizeof(receivedFd));
            return receivedFd;
        }
    }
    errno = EBADMSG;
    return -1;
}

bool sendAck(int socketFd)
{
    constexpr char Ack[] = {'o', 'k'};
    size_t offset = 0;

    while (offset < sizeof(Ack)) {
        const ssize_t count =
            ::send(socketFd, Ack + offset, sizeof(Ack) - offset,
                   MSG_NOSIGNAL);
        if (count > 0) {
            offset += static_cast<size_t>(count);
            continue;
        }
        if (count < 0 && errno == EINTR)
            continue;
        return false;
    }
    return true;
}

int connectToComposer()
{
    for (int attempt = 0; attempt < 180 && !stopRequested; ++attempt) {
        const int socketFd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (socketFd >= 0) {
            struct sockaddr_un address = {};
            address.sun_family = AF_UNIX;
            std::strncpy(address.sun_path, SocketPath,
                         sizeof(address.sun_path) - 1);
            const socklen_t length =
                static_cast<socklen_t>(offsetof(struct sockaddr_un, sun_path) +
                                       std::strlen(address.sun_path) + 1);
            if (::connect(socketFd,
                          reinterpret_cast<struct sockaddr *>(&address),
                          length) == 0) {
                writeLog(QStringLiteral("connected to %1 after %2 seconds")
                             .arg(QString::fromLatin1(SocketPath))
                             .arg(attempt));
                return socketFd;
            }
            ::close(socketFd);
        }
        if (attempt % 10 == 0) {
            writeLog(QStringLiteral("waiting for Android HWC socket: %1")
                         .arg(QString::fromLocal8Bit(std::strerror(errno))));
        }
        ::sleep(1);
    }
    return -1;
}

struct FrameMailbox {
    struct PenSample {
        int rawX = 0;
        int rawY = 0;
        int pressure = 0;
        bool touching = false;
        bool strokeStart = false;
        bool hardwareEraser = false;
    };

    std::mutex mutex;
    std::condition_variable changed;
    std::shared_ptr<QByteArray> latest;
    std::deque<PenSample> penSamples;
    uint64_t receivedFrames = 0;
    uint64_t receivedPenReports = 0;
    int penMinX = 0;
    int penMaxX = 6760;
    int penMinY = 0;
    int penMaxY = 11960;
    int penMinPressure = 0;
    int penMaxPressure = 4096;
    bool penInRange = false;
    uint64_t penPresenceGeneration = 0;
    bool receiverEnded = false;
    bool penReceiverEnded = false;
};

void finishReceiver(FrameMailbox *mailbox)
{
    {
        const std::lock_guard<std::mutex> lock(mailbox->mutex);
        mailbox->receiverEnded = true;
    }
    mailbox->changed.notify_all();
}

void finishPenReceiver(FrameMailbox *mailbox)
{
    {
        const std::lock_guard<std::mutex> lock(mailbox->mutex);
        mailbox->penReceiverEnded = true;
    }
    mailbox->changed.notify_all();
}

int openMarkerDevice(QString &devicePath,
                     struct input_absinfo &xInfo,
                     struct input_absinfo &yInfo,
                     struct input_absinfo &pressureInfo)
{
    for (int index = 0; index < 32; ++index) {
        const QString eventName = QStringLiteral("event%1").arg(index);
        QFile nameFile(QStringLiteral("/sys/class/input/%1/device/name")
                           .arg(eventName));
        if (!nameFile.open(QIODevice::ReadOnly))
            continue;
        const QByteArray name = nameFile.read(128).trimmed().toLower();
        if (!name.contains("marker") && !name.contains("stylus"))
            continue;

        const QString candidate =
            QStringLiteral("/dev/input/%1").arg(eventName);
        const QByteArray encoded = candidate.toLocal8Bit();
        const int fd =
            ::open(encoded.constData(), O_RDONLY | O_CLOEXEC | O_NONBLOCK);
        if (fd < 0)
            continue;
        if (::ioctl(fd, EVIOCGABS(ABS_X), &xInfo) == 0 &&
            ::ioctl(fd, EVIOCGABS(ABS_Y), &yInfo) == 0 &&
            ::ioctl(fd, EVIOCGABS(ABS_PRESSURE), &pressureInfo) == 0) {
            devicePath = candidate;
            return fd;
        }
        ::close(fd);
    }
    errno = ENODEV;
    return -1;
}

void receivePenSamples(FrameMailbox *mailbox)
{
    int retryCount = 0;
    /*
     * The Elan SPI driver is loaded in parallel with Android userspace. On
     * some cold boots the display bridge wins that race by a few seconds.
     * The old one-shot probe then ended this thread permanently even though
     * /dev/input/event* appeared moments later. Keep the receiver alive until
     * the real Marker node is ready.
     */
    while (!stopRequested) {
        QString devicePath;
        struct input_absinfo xInfo = {};
        struct input_absinfo yInfo = {};
        struct input_absinfo pressureInfo = {};
        int fd = openMarkerDevice(
            devicePath, xInfo, yInfo, pressureInfo);
        if (fd < 0) {
            ++retryCount;
            if (retryCount == 1 || retryCount % 5 == 0) {
                writeLog(QStringLiteral(
                             "native marker waiting for input node retry=%1: %2")
                             .arg(retryCount)
                             .arg(QString::fromLocal8Bit(std::strerror(errno))));
            }
            ::sleep(1);
            continue;
        }

        retryCount = 0;
        writeLog(QStringLiteral(
                     "native marker opened path=%1 x=%2..%3 y=%4..%5 "
                     "pressure=%6..%7")
                     .arg(devicePath)
                     .arg(xInfo.minimum)
                     .arg(xInfo.maximum)
                     .arg(yInfo.minimum)
                     .arg(yInfo.maximum)
                     .arg(pressureInfo.minimum)
                     .arg(pressureInfo.maximum));
        {
            const std::lock_guard<std::mutex> lock(mailbox->mutex);
            mailbox->penMinX = xInfo.minimum;
            mailbox->penMaxX = xInfo.maximum;
            mailbox->penMinY = yInfo.minimum;
            mailbox->penMaxY = yInfo.maximum;
            mailbox->penMinPressure = pressureInfo.minimum;
            mailbox->penMaxPressure = pressureInfo.maximum;
            mailbox->penInRange = false;
            ++mailbox->penPresenceGeneration;
        }
        mailbox->changed.notify_one();

        int rawX = xInfo.minimum;
        int rawY = yInfo.minimum;
        int pressure = pressureInfo.minimum;
        bool haveX = false;
        bool haveY = false;
        bool touch = false;
        bool penTool = false;
        bool rubberTool = false;
        bool stylusButton = false;
        bool secondaryButton = false;
        bool wasTouching = false;

        bool reopenDevice = false;
        while (!stopRequested && !reopenDevice) {
        struct pollfd ready = {
            .fd = fd,
            .events = POLLIN,
            .revents = 0,
        };
        const int pollResult = ::poll(&ready, 1, 1000);
        if (pollResult == 0)
            continue;
        if (pollResult < 0 && errno == EINTR)
            continue;
        if (pollResult < 0 ||
            (ready.revents & (POLLERR | POLLHUP | POLLNVAL))) {
            reopenDevice = true;
            continue;
        }
        if (!(ready.revents & POLLIN))
            continue;

        std::array<struct input_event, 64> events = {};
        const ssize_t count =
            ::read(fd, events.data(), sizeof(events));
        if (count < 0 && (errno == EAGAIN || errno == EINTR))
            continue;
        if (count <= 0) {
            reopenDevice = true;
            continue;
        }
        const size_t eventCount =
            static_cast<size_t>(count) / sizeof(struct input_event);

        bool notify = false;
        {
            const std::lock_guard<std::mutex> lock(mailbox->mutex);
            for (size_t index = 0; index < eventCount; ++index) {
                const struct input_event &event = events[index];
                if (event.type == EV_ABS) {
                    if (event.code == ABS_X) {
                        rawX = event.value;
                        haveX = true;
                    } else if (event.code == ABS_Y) {
                        rawY = event.value;
                        haveY = true;
                    } else if (event.code == ABS_PRESSURE) {
                        pressure = event.value;
                    }
                    continue;
                }
                if (event.type == EV_KEY) {
                    const bool pressed = event.value != 0;
                    if (event.code == BTN_TOUCH)
                        touch = pressed;
                    else if (event.code == BTN_TOOL_PEN)
                        penTool = pressed;
                    else if (event.code == BTN_TOOL_RUBBER)
                        rubberTool = pressed;
                    else if (event.code == BTN_STYLUS)
                        stylusButton = pressed;
                    else if (event.code == BTN_STYLUS2)
                        secondaryButton = pressed;
                    continue;
                }
                if (event.type != EV_SYN || event.code != SYN_REPORT)
                    continue;

                const bool penInRange = penTool || rubberTool;
                if (mailbox->penInRange != penInRange) {
                    mailbox->penInRange = penInRange;
                    ++mailbox->penPresenceGeneration;
                    notify = true;
                }
                const bool touching =
                    touch && (penTool || rubberTool) && haveX && haveY;
                if (touching || wasTouching) {
                    FrameMailbox::PenSample sample;
                    sample.rawX = rawX;
                    sample.rawY = rawY;
                    sample.pressure = pressure;
                    sample.touching = touching;
                    sample.strokeStart = touching && !wasTouching;
                    sample.hardwareEraser =
                        rubberTool || stylusButton || secondaryButton;
                    if (mailbox->penSamples.size() >= 512)
                        mailbox->penSamples.pop_front();
                    mailbox->penSamples.push_back(sample);
                    ++mailbox->receivedPenReports;
                    notify = true;
                }
                wasTouching = touching;
            }
        }
        if (notify)
            mailbox->changed.notify_one();
        }

        const int receiverError = errno;
        ::close(fd);
        if (!stopRequested) {
            writeLog(QStringLiteral(
                         "native marker receiver interrupted; reopening: %1")
                         .arg(QString::fromLocal8Bit(
                             std::strerror(receiverError))));
            /*
             * Drop any incomplete stroke before binding a newly registered
             * event node. Keeping the previous touch bit could connect two
             * physically separate strokes after a driver reset.
             */
            {
                const std::lock_guard<std::mutex> lock(mailbox->mutex);
                mailbox->penSamples.clear();
            }
            ::sleep(1);
        }
    }

    writeLog(QStringLiteral("native marker receiver stopped"));
    finishPenReceiver(mailbox);
}

void receiveHwcFrames(FrameMailbox *mailbox)
{
    int socketFd = connectToComposer();

    if (socketFd < 0) {
        writeLog(QStringLiteral("ERROR Android HWC socket did not appear"));
        finishReceiver(mailbox);
        return;
    }

    while (!stopRequested) {
        std::array<unsigned char, NativeHandleBytes> handle = {};
        if (!readExact(socketFd, handle.data(), handle.size())) {
            writeLog(QStringLiteral("HWC handle stream reset: %1")
                         .arg(QString::fromLocal8Bit(std::strerror(errno))));
            ::close(socketFd);
            socketFd = -1;
            if (stopRequested)
                break;
            writeLog(QStringLiteral(
                "retrying HWC connection after stream reset"));
            ::sleep(2);
            socketFd = connectToComposer();
            if (socketFd < 0) {
                writeLog(QStringLiteral(
                    "ERROR Android HWC socket did not return"));
                break;
            }
            continue;
        }

        const uint32_t version = readLe32(handle, 0);
        const uint32_t numFds = readLe32(handle, 4);
        const uint32_t numInts = readLe32(handle, 8);
        const uint32_t magic = readLe32(handle, 16);
        const uint32_t flags = readLe32(handle, 20);
        const uint32_t bufferBytes = readLe32(handle, 24);
        const uint32_t bufferOffset = readLe32(handle, 28);
        if (version != NativeHandleVersion ||
            numFds != NativeHandleFds ||
            numInts != NativeHandleInts ||
            magic != RedroidHandleMagic ||
            flags != 0 ||
            bufferOffset != 0 ||
            bufferBytes < VisibleBytes ||
            bufferBytes > MaximumBufferBytes) {
            writeLog(QStringLiteral(
                         "ERROR invalid HWC handle version=%1 fds=%2 ints=%3 "
                         "magic=0x%4 flags=%5 size=%6 offset=%7")
                         .arg(version)
                         .arg(numFds)
                         .arg(numInts)
                         .arg(magic, 0, 16)
                         .arg(flags)
                         .arg(bufferBytes)
                         .arg(bufferOffset));
            break;
        }

        const int sharedFd = receiveSharedFd(socketFd);
        if (sharedFd < 0) {
            writeLog(QStringLiteral("ERROR did not receive HWC shared fd: %1")
                         .arg(QString::fromLocal8Bit(std::strerror(errno))));
            break;
        }
        void *mapping =
            ::mmap(nullptr, bufferBytes, PROT_READ, MAP_SHARED, sharedFd, 0);
        if (mapping == MAP_FAILED) {
            const int savedError = errno;
            ::close(sharedFd);
            writeLog(QStringLiteral("ERROR mmap HWC fd failed: %1")
                         .arg(QString::fromLocal8Bit(
                             std::strerror(savedError))));
            break;
        }

        /*
         * Copy the producer buffer before acknowledging it.  From this point
         * SurfaceFlinger is free to reuse the ashmem buffer; the slow Gallery
         * 3 waveform never back-pressures Android.  The single-slot mailbox
         * deliberately replaces intermediate animation frames.
         */
        auto frame = std::make_shared<QByteArray>(
            static_cast<const char *>(mapping),
            static_cast<qsizetype>(VisibleBytes));
        const bool acknowledged = sendAck(socketFd);
        ::munmap(mapping, bufferBytes);
        ::close(sharedFd);
        if (!acknowledged) {
            writeLog(QStringLiteral("ERROR HWC acknowledgement failed"));
            ::close(socketFd);
            socketFd = -1;
            if (stopRequested)
                break;
            ::sleep(2);
            socketFd = connectToComposer();
            if (socketFd < 0)
                break;
            continue;
        }

        {
            const std::lock_guard<std::mutex> lock(mailbox->mutex);
            mailbox->latest = std::move(frame);
            ++mailbox->receivedFrames;
        }
        mailbox->changed.notify_one();
    }

    if (socketFd >= 0)
        ::close(socketFd);
    finishReceiver(mailbox);
}

QRect rectanglesBoundingRect(const std::vector<QRect> &rectangles)
{
    QRect bounds;
    for (const QRect &rect : rectangles)
        bounds = bounds.isEmpty() ? rect : bounds.united(rect);
    return bounds;
}

void reduceRectangles(std::vector<QRect> &rectangles,
                      int maximumRectangles,
                      int mergeGap)
{
    const QRect panel(0, 0, AndroidWidth, AndroidHeight);
    bool merged = true;
    while (merged) {
        merged = false;
        for (size_t first = 0; first < rectangles.size() && !merged; ++first) {
            const QRect expanded = rectangles[first].adjusted(
                -mergeGap, -mergeGap, mergeGap, mergeGap);
            for (size_t second = first + 1;
                 second < rectangles.size(); ++second) {
                if (!expanded.intersects(rectangles[second]))
                    continue;
                rectangles[first] = rectangles[first]
                    .united(rectangles[second]).intersected(panel);
                rectangles.erase(rectangles.begin() + second);
                merged = true;
                break;
            }
        }
    }

    while (rectangles.size() > static_cast<size_t>(maximumRectangles)) {
        size_t bestFirst = 0;
        size_t bestSecond = 1;
        qint64 bestPenalty = -1;
        for (size_t first = 0; first < rectangles.size(); ++first) {
            for (size_t second = first + 1;
                 second < rectangles.size(); ++second) {
                const QRect united = rectangles[first].united(rectangles[second]);
                const qint64 penalty =
                    static_cast<qint64>(united.width()) * united.height() -
                    static_cast<qint64>(rectangles[first].width()) *
                        rectangles[first].height() -
                    static_cast<qint64>(rectangles[second].width()) *
                        rectangles[second].height();
                if (bestPenalty < 0 || penalty < bestPenalty) {
                    bestPenalty = penalty;
                    bestFirst = first;
                    bestSecond = second;
                }
            }
        }
        rectangles[bestFirst] = rectangles[bestFirst]
            .united(rectangles[bestSecond]).intersected(panel);
        rectangles.erase(rectangles.begin() + bestSecond);
    }

    std::sort(rectangles.begin(), rectangles.end(),
              [](const QRect &left, const QRect &right) {
                  return left.top() == right.top()
                      ? left.left() < right.left()
                      : left.top() < right.top();
              });
}

void addDamageRectangle(std::vector<QRect> &rectangles, const QRect &requested)
{
    const QRect rect = requested.intersected(
        QRect(0, 0, AndroidWidth, AndroidHeight));
    if (rect.isEmpty())
        return;
    rectangles.push_back(rect);
    reduceRectangles(
        rectangles, MaximumDamageRectangles, DamageMergeGap);
}

std::vector<QRect> changedRectangles(const unsigned char *frame,
                                     const QByteArray &previous,
                                     bool firstFrame)
{
    if (firstFrame || previous.size() != static_cast<qsizetype>(VisibleBytes))
        return {QRect(0, 0, AndroidWidth, AndroidHeight)};

    const int columns =
        (AndroidWidth + DamageTileSize - 1) / DamageTileSize;
    const int rows =
        (AndroidHeight + DamageTileSize - 1) / DamageTileSize;
    std::vector<unsigned char> changed(
        static_cast<size_t>(columns * rows), 0);
    const auto *old =
        reinterpret_cast<const unsigned char *>(previous.constData());

    for (int tileY = 0; tileY < rows; ++tileY) {
        const int top = tileY * DamageTileSize;
        const int bottom = std::min(AndroidHeight, top + DamageTileSize);
        for (int tileX = 0; tileX < columns; ++tileX) {
            const int left = tileX * DamageTileSize;
            const int right = std::min(AndroidWidth, left + DamageTileSize);
            const size_t rowBytes =
                static_cast<size_t>(right - left) * BytesPerPixel;
            bool tileChanged = false;
            for (int y = top; y < bottom; ++y) {
                const size_t offset =
                    static_cast<size_t>(y) * AndroidStrideBytes +
                    static_cast<size_t>(left) * BytesPerPixel;
                if (std::memcmp(frame + offset, old + offset, rowBytes) != 0) {
                    tileChanged = true;
                    break;
                }
            }
            if (tileChanged) {
                changed[static_cast<size_t>(tileY * columns + tileX)] = 1;
            }
        }
    }

    std::vector<QRect> rectangles;
    std::vector<int> stack;
    for (int tileY = 0; tileY < rows; ++tileY) {
        for (int tileX = 0; tileX < columns; ++tileX) {
            const int origin = tileY * columns + tileX;
            if (!changed[static_cast<size_t>(origin)])
                continue;
            changed[static_cast<size_t>(origin)] = 0;
            stack.clear();
            stack.push_back(origin);
            int minTileX = tileX;
            int maxTileX = tileX;
            int minTileY = tileY;
            int maxTileY = tileY;
            while (!stack.empty()) {
                const int current = stack.back();
                stack.pop_back();
                const int currentX = current % columns;
                const int currentY = current / columns;
                minTileX = std::min(minTileX, currentX);
                maxTileX = std::max(maxTileX, currentX);
                minTileY = std::min(minTileY, currentY);
                maxTileY = std::max(maxTileY, currentY);
                constexpr int Neighbors[4][2] = {
                    {-1, 0}, {1, 0}, {0, -1}, {0, 1}};
                for (const auto &neighbor : Neighbors) {
                    const int nextX = currentX + neighbor[0];
                    const int nextY = currentY + neighbor[1];
                    if (nextX < 0 || nextX >= columns ||
                        nextY < 0 || nextY >= rows)
                        continue;
                    const int next = nextY * columns + nextX;
                    if (!changed[static_cast<size_t>(next)])
                        continue;
                    changed[static_cast<size_t>(next)] = 0;
                    stack.push_back(next);
                }
            }
            const int left = std::max(
                0, minTileX * DamageTileSize - DamagePadding);
            const int top = std::max(
                0, minTileY * DamageTileSize - DamagePadding);
            const int right = std::min(
                AndroidWidth,
                (maxTileX + 1) * DamageTileSize + DamagePadding);
            const int bottom = std::min(
                AndroidHeight,
                (maxTileY + 1) * DamageTileSize + DamagePadding);
            rectangles.emplace_back(
                left, top, right - left, bottom - top);
        }
    }
    reduceRectangles(
        rectangles, MaximumDamageRectangles, DamageMergeGap);
    return rectangles;
}

QRect changedRectangle(const unsigned char *frame,
                       const QByteArray &previous,
                       bool firstFrame)
{
    return rectanglesBoundingRect(
        changedRectangles(frame, previous, firstFrame));
}

DisplayProfile readDisplayProfile()
{
    QFile file(QString::fromLatin1(DisplayProfilePath));
    if (!file.open(QIODevice::ReadOnly))
        return DisplayProfile::Quality;
    const QByteArray value = file.read(32).trimmed().toLower();
    if (value == "fast")
        return DisplayProfile::Fast;
    if (value == "balanced")
        return DisplayProfile::Balanced;
    if (value == "quality")
        return DisplayProfile::Quality;
    return DisplayProfile::Quality;
}

QString displayProfileName(DisplayProfile profile)
{
    switch (profile) {
    case DisplayProfile::Fast:
        return QStringLiteral("fast");
    case DisplayProfile::Quality:
        return QStringLiteral("quality");
    case DisplayProfile::Balanced:
    default:
        return QStringLiteral("balanced");
    }
}

enum class ReaderRefreshPolicy {
    Budget,
    EveryPage,
    EveryThreePages,
    EveryFivePages,
};

enum class GhostControlPolicy {
    Off,
    Bleach,
    BlinkLater,
};

ReaderRefreshPolicy readReaderRefreshPolicy()
{
    QFile file(QString::fromLatin1(ReaderRefreshPolicyPath));
    if (!file.open(QIODevice::ReadOnly))
        return ReaderRefreshPolicy::EveryFivePages;
    const QByteArray value = file.read(32).trimmed().toLower();
    if (value == "budget")
        return ReaderRefreshPolicy::Budget;
    if (value == "every-page")
        return ReaderRefreshPolicy::EveryPage;
    if (value == "every-3")
        return ReaderRefreshPolicy::EveryThreePages;
    return ReaderRefreshPolicy::EveryFivePages;
}

int readerPagesPerCleanup(ReaderRefreshPolicy policy)
{
    switch (policy) {
    case ReaderRefreshPolicy::EveryPage:
        return 1;
    case ReaderRefreshPolicy::EveryThreePages:
        return 3;
    case ReaderRefreshPolicy::EveryFivePages:
        return ReaderDefaultPagesPerCleanup;
    case ReaderRefreshPolicy::Budget:
    default:
        return 0;
    }
}

QString readerRefreshPolicyName(ReaderRefreshPolicy policy)
{
    switch (policy) {
    case ReaderRefreshPolicy::Budget:
        return QStringLiteral("budget");
    case ReaderRefreshPolicy::EveryPage:
        return QStringLiteral("every-page");
    case ReaderRefreshPolicy::EveryThreePages:
        return QStringLiteral("every-3");
    case ReaderRefreshPolicy::EveryFivePages:
    default:
        return QStringLiteral("every-5");
    }
}

GhostControlPolicy readGhostControlPolicy()
{
    QFile file(QString::fromLatin1(GhostControlPolicyPath));
    if (!file.open(QIODevice::ReadOnly))
        return GhostControlPolicy::Off;
    const QByteArray value = file.read(32).trimmed().toLower();
    if (value == "bleach")
        return GhostControlPolicy::Bleach;
    if (value == "blink-later")
        return GhostControlPolicy::BlinkLater;
    return GhostControlPolicy::Off;
}

const char *ghostControlKey(GhostControlPolicy policy)
{
    return policy == GhostControlPolicy::Bleach ? "BleachNow" : "BlinkLater";
}

QString ghostControlPolicyName(GhostControlPolicy policy)
{
    switch (policy) {
    case GhostControlPolicy::Bleach:
        return QStringLiteral("bleach");
    case GhostControlPolicy::BlinkLater:
        return QStringLiteral("blink-later");
    case GhostControlPolicy::Off:
    default:
        return QStringLiteral("off");
    }
}

bool readReaderActive()
{
    return ::access(ReaderActivePath, F_OK) == 0;
}

/*
 * libqsgepaper publishes EPFramebuffer::ghostControl(GhostControlMode) and the
 * GhostControlMode enumeration through Qt's meta-object system. Resolving both
 * by name at runtime uses only that public reflection surface: no private
 * constant or layout is assumed, and a library without the method simply
 * reports "unsupported" so the bridge falls back to its own repaint cleanup.
 */
bool invokeStockGhostControl(EPFramebuffer *framebuffer,
                             const char *key,
                             QString *detail)
{
    QObject *object = reinterpret_cast<QObject *>(framebuffer);
    const QMetaObject *meta = object->metaObject();
    if (meta == nullptr) {
        if (detail)
            *detail = QStringLiteral("no meta-object");
        return false;
    }
    const int enumIndex = meta->indexOfEnumerator("GhostControlMode");
    if (enumIndex < 0) {
        if (detail)
            *detail = QStringLiteral("GhostControlMode enumerator missing");
        return false;
    }
    bool known = false;
    int value = meta->enumerator(enumIndex).keyToValue(key, &known);
    if (!known) {
        if (detail)
            *detail = QStringLiteral("key unsupported");
        return false;
    }
    int methodIndex = -1;
    for (int index = 0; index < meta->methodCount(); ++index) {
        const QMetaMethod candidate = meta->method(index);
        if (candidate.name() == "ghostControl" &&
            candidate.parameterCount() == 1) {
            methodIndex = index;
            break;
        }
    }
    if (methodIndex < 0) {
        if (detail)
            *detail = QStringLiteral("ghostControl method missing");
        return false;
    }
    const QMetaMethod method = meta->method(methodIndex);
    const QByteArray typeName = method.parameterTypeName(0);
    const bool invoked = method.invoke(
        object,
        Qt::DirectConnection,
        QGenericArgument(typeName.constData(), &value));
    if (detail) {
        *detail = invoked
            ? QStringLiteral("invoked type=%1 value=%2")
                  .arg(QString::fromLatin1(typeName))
                  .arg(value)
            : QStringLiteral("invoke failed type=%1")
                  .arg(QString::fromLatin1(typeName));
    }
    return invoked;
}

ColorMode readColorMode()
{
    QFile file(QString::fromLatin1(ColorModePath));
    if (!file.open(QIODevice::ReadOnly))
        return ColorMode::Auto;
    const QByteArray value = file.read(32).trimmed().toLower();
    if (value == "mono")
        return ColorMode::Mono;
    if (value == "once-mono")
        return ColorMode::OnceMono;
    if (value == "once" || value == "once-auto")
        return ColorMode::OnceAuto;
    return ColorMode::Auto;
}

bool isOneShotColorMode(ColorMode mode)
{
    return mode == ColorMode::OnceAuto ||
           mode == ColorMode::OnceMono;
}

QString colorModeName(ColorMode mode)
{
    switch (mode) {
    case ColorMode::Mono:
        return QStringLiteral("mono");
    case ColorMode::OnceAuto:
        return QStringLiteral("once-auto");
    case ColorMode::OnceMono:
        return QStringLiteral("once-mono");
    case ColorMode::Auto:
    default:
        return QStringLiteral("auto");
    }
}

bool writeColorMode(ColorMode mode)
{
    QFile file(QString::fromLatin1(ColorModePath));
    if (!file.open(QIODevice::WriteOnly | QIODevice::Truncate))
        return false;
    const QByteArray value =
        colorModeName(mode).toLatin1() + QByteArrayLiteral("\n");
    const bool written = file.write(value) == value.size();
    file.flush();
    return written;
}

bool readInvertMode()
{
    QFile file(QString::fromLatin1(InvertModePath));
    if (!file.open(QIODevice::ReadOnly))
        return false;
    return file.read(32).trimmed().toLower() != "normal";
}

bool readScreenOff()
{
    QFile file(QString::fromLatin1(ScreenStatePath));
    if (!file.open(QIODevice::ReadOnly))
        return false;
    return file.read(16).trimmed().toLower() == "off";
}

LockStyle readLockStyle()
{
    QFile file(QString::fromLatin1(LockStylePath));
    if (!file.open(QIODevice::ReadOnly))
        return LockStyle::Fade;
    const QByteArray value = file.read(32).trimmed().toLower();
    if (value == "reading")
        return LockStyle::Reading;
    if (value == "clean")
        return LockStyle::Clean;
    if (value == "clock")
        return LockStyle::Clock;
    if (value == "classic")
        return LockStyle::Classic;
    return LockStyle::Fade;
}

QString lockStyleName(LockStyle style)
{
    switch (style) {
    case LockStyle::Reading:
        return QStringLiteral("reading");
    case LockStyle::Clean:
        return QStringLiteral("clean");
    case LockStyle::Clock:
        return QStringLiteral("clock");
    case LockStyle::Classic:
        return QStringLiteral("classic");
    case LockStyle::Fade:
    default:
        return QStringLiteral("fade");
    }
}

std::vector<QRect> chromaticRectangles(const unsigned char *source,
                                       const QRect &requested)
{
    const QRect bounds(0, 0, AndroidWidth, AndroidHeight);
    const QRect search = requested.intersected(bounds);
    if (search.isEmpty())
        return {};

    std::vector<QRect> rectangles;
    const int firstTileX = search.left() / ColorTileSize;
    const int lastTileX = search.right() / ColorTileSize;
    const int firstTileY = search.top() / ColorTileSize;
    const int lastTileY = search.bottom() / ColorTileSize;
    for (int tileY = firstTileY; tileY <= lastTileY; ++tileY) {
        for (int tileX = firstTileX; tileX <= lastTileX; ++tileX) {
            const QRect tile(
                tileX * ColorTileSize,
                tileY * ColorTileSize,
                ColorTileSize,
                ColorTileSize);
            const QRect sampleRect = tile.intersected(search);
            int chromaticSamples = 0;
            int totalSamples = 0;
            for (int y = sampleRect.top(); y <= sampleRect.bottom();
                 y += ColorSampleStep) {
                for (int x = sampleRect.left(); x <= sampleRect.right();
                     x += ColorSampleStep) {
                    ++totalSamples;
                    const size_t offset =
                        static_cast<size_t>(y) * AndroidStrideBytes +
                        static_cast<size_t>(x) * BytesPerPixel;
                    const int red = source[offset + 0];
                    const int green = source[offset + 1];
                    const int blue = source[offset + 2];
                    const int maximum = std::max({red, green, blue});
                    const int minimum = std::min({red, green, blue});
                    if (maximum - minimum >= ColorChannelThreshold)
                        ++chromaticSamples;
                }
            }

            /*
             * Decide color tile-by-tile instead of allowing two distant book
             * covers or palette swatches to inflate into one large Gallery 3
             * rectangle. Six samples filters colored anti-aliasing fringes
             * while preserving small, intentionally colored controls.
             */
            const int requiredSamples =
                std::max(6, totalSamples / 128);
            if (chromaticSamples < requiredSamples)
                continue;

            const int left = std::max(
                0, sampleRect.left() - ColorPadding);
            const int top = std::max(
                0, sampleRect.top() - ColorPadding);
            const int right = std::min(
                AndroidWidth, sampleRect.right() + ColorPadding + 1);
            const int bottom = std::min(
                AndroidHeight, sampleRect.bottom() + ColorPadding + 1);
            rectangles.emplace_back(
                left, top, right - left, bottom - top);
        }
    }
    reduceRectangles(rectangles, MaximumColorRectangles, 2);
    return rectangles;
}

void convertColorForEink(const unsigned char *source,
                         QByteArray &output,
                         const QRect &requested,
                         bool inverted)
{
    if (output.size() != static_cast<qsizetype>(VisibleBytes))
        output.resize(static_cast<qsizetype>(VisibleBytes));
    auto *destination =
        reinterpret_cast<unsigned char *>(output.data());
    const QRect rect =
        requested.intersected(QRect(0, 0, AndroidWidth, AndroidHeight));

    for (int y = rect.top(); y <= rect.bottom(); ++y) {
        for (int x = rect.left(); x <= rect.right(); ++x) {
            const size_t offset =
                static_cast<size_t>(y) * AndroidStrideBytes +
                static_cast<size_t>(x) * BytesPerPixel;
            const int red = source[offset + 0];
            const int green = source[offset + 1];
            const int blue = source[offset + 2];
            const int maximum = std::max({red, green, blue});
            const int minimum = std::min({red, green, blue});
            // Gallery 3 turns slightly warm UI whites into a conspicuous
            // colored-dot rectangle. Snap only near-neutral endpoints while
            // preserving real pastel and anti-aliased color content.
            const bool paperWhite = minimum >= 242
                && maximum - minimum <= 12;
            const bool inkBlack = maximum <= 12;
            const int normalizedRed = paperWhite ? 255 : inkBlack ? 0 : red;
            const int normalizedGreen =
                paperWhite ? 255 : inkBlack ? 0 : green;
            const int normalizedBlue =
                paperWhite ? 255 : inkBlack ? 0 : blue;
            destination[offset + 0] = inverted
                ? 255 - normalizedRed : normalizedRed;
            destination[offset + 1] = inverted
                ? 255 - normalizedGreen : normalizedGreen;
            destination[offset + 2] = inverted
                ? 255 - normalizedBlue : normalizedBlue;
            destination[offset + 3] = 0xff;
        }
    }
}

bool readNoteActive()
{
    return ::access(NoteActivePath, F_OK) == 0;
}

enum class NoteTool {
    Ballpoint,
    Fineliner,
    Pencil,
    Marker,
    Brush,
};

struct NotePenSettings {
    NoteTool tool = NoteTool::Ballpoint;
    float sizeMultiplier = 1.0f;
    float eraserWidth = 40.0f;
    float uiBottom = 92.0f;
    float uiLeft = 0.0f;
    std::array<QRectF, 4> uiRegions;
    int uiRegionCount = 0;
    bool appEraser = false;
};

QByteArray readNoteControl(const char *path)
{
    QFile file(QString::fromLatin1(path));
    if (!file.open(QIODevice::ReadOnly))
        return QByteArray();
    return file.read(192).trimmed().toLower();
}

NotePenSettings readNotePenSettings()
{
    NotePenSettings settings;
    const QByteArray tool = readNoteControl(NoteToolPath);
    settings.appEraser = tool == "erase";
    if (tool == "fineliner")
        settings.tool = NoteTool::Fineliner;
    else if (tool == "pencil")
        settings.tool = NoteTool::Pencil;
    else if (tool == "marker")
        settings.tool = NoteTool::Marker;
    else if (tool == "brush")
        settings.tool = NoteTool::Brush;

    const QByteArray size = readNoteControl(NoteSizePath);
    bool numericSize = false;
    const float requestedSize = size.toFloat(&numericSize);
    if (numericSize) {
        settings.sizeMultiplier =
            std::max(0.45f, std::min(2.20f, requestedSize));
    } else if (size == "small") {
        settings.sizeMultiplier = 0.72f;
    } else if (size == "large") {
        settings.sizeMultiplier = 1.42f;
    }

    const QByteArray eraserSize =
        readNoteControl(NoteEraserSizePath);
    bool numericEraser = false;
    const float requestedEraser =
        eraserSize.toFloat(&numericEraser);
    if (numericEraser) {
        settings.eraserWidth =
            std::max(12.0f, std::min(96.0f, requestedEraser));
    } else if (eraserSize == "small") {
        settings.eraserWidth = 22.0f;
    } else if (eraserSize == "large") {
        settings.eraserWidth = 68.0f;
    }

    const QByteArray uiBottom =
        readNoteControl(NoteUiBottomPath);
    bool numericBottom = false;
    const float requestedBottom =
        uiBottom.toFloat(&numericBottom);
    if (numericBottom) {
        settings.uiBottom =
            std::max(92.0f, std::min(740.0f, requestedBottom));
    }

    const QByteArray uiLeft = readNoteControl(NoteUiLeftPath);
    bool numericLeft = false;
    const float requestedLeft = uiLeft.toFloat(&numericLeft);
    if (numericLeft) {
        settings.uiLeft =
            std::max(0.0f, std::min(600.0f, requestedLeft));
    }

    const QByteArray encodedRegions =
        readNoteControl(NoteUiRegionsPath);
    const QList<QByteArray> regions = encodedRegions.split(';');
    for (const QByteArray &encoded : regions) {
        if (settings.uiRegionCount >=
            static_cast<int>(settings.uiRegions.size())) {
            break;
        }
        const QList<QByteArray> coordinates = encoded.split(',');
        if (coordinates.size() != 4)
            continue;
        bool okLeft = false;
        bool okTop = false;
        bool okRight = false;
        bool okBottom = false;
        const float left = coordinates[0].toFloat(&okLeft);
        const float top = coordinates[1].toFloat(&okTop);
        const float right = coordinates[2].toFloat(&okRight);
        const float bottom = coordinates[3].toFloat(&okBottom);
        if (!okLeft || !okTop || !okRight || !okBottom)
            continue;
        const float boundedLeft =
            std::max(0.0f, std::min(static_cast<float>(AndroidWidth), left));
        const float boundedTop =
            std::max(0.0f, std::min(static_cast<float>(AndroidHeight), top));
        const float boundedRight =
            std::max(0.0f, std::min(static_cast<float>(AndroidWidth), right));
        const float boundedBottom =
            std::max(0.0f, std::min(static_cast<float>(AndroidHeight), bottom));
        if (boundedRight <= boundedLeft || boundedBottom <= boundedTop)
            continue;
        settings.uiRegions[settings.uiRegionCount++] =
            QRectF(boundedLeft, boundedTop,
                   boundedRight - boundedLeft,
                   boundedBottom - boundedTop);
    }
    return settings;
}

float notePenWidth(NoteTool tool,
                   float pressure,
                   float segmentDistance,
                   bool strokeStart)
{
    switch (tool) {
    case NoteTool::Fineliner:
        return 2.5f + 1.15f * pressure;
    case NoteTool::Pencil:
        return 0.70f + 4.55f * std::pow(pressure, 0.88f);
    case NoteTool::Marker:
        return 8.5f + 4.5f * pressure;
    case NoteTool::Brush: {
        const float speedFactor =
            std::max(0.52f,
                     std::min(1.15f,
                              1.15f - segmentDistance / 35.0f));
        const float startFactor = strokeStart ? 0.48f : 1.0f;
        return (1.1f + 14.0f * std::pow(pressure, 0.48f)) *
               speedFactor * startFactor;
    }
    case NoteTool::Ballpoint:
    default:
        return 1.15f + 5.2f * std::pow(pressure, 0.72f);
    }
}

uint32_t pencilCoordinateHash(const QPointF &point)
{
    uint32_t value =
        static_cast<uint32_t>(std::lround(point.x() * 4.0)) *
            374761393u +
        static_cast<uint32_t>(std::lround(point.y() * 4.0)) *
            668265263u;
    value = (value ^ (value >> 13)) * 1274126177u;
    return value ^ (value >> 16);
}

int pencilGray(bool inverted, int normalGray)
{
    return inverted ? 255 - normalGray : normalGray;
}

struct NativePenOverlay {
    QImage image;
    bool drawing = false;
    bool contactActive = false;
    bool uiStrokeSuppressed = false;
    bool noteActive = false;
    bool inverted = false;
    QPointF lastPoint;
    float lastPressure = 0.12f;
    uint64_t strokePointIndex = 0;
    uint64_t submittedBatches = 0;

    NativePenOverlay()
        : image(AndroidWidth,
                AndroidHeight,
                QImage::Format_ARGB32_Premultiplied)
    {
        image.fill(Qt::transparent);
    }

    void clear()
    {
        image.fill(Qt::transparent);
        drawing = false;
        contactActive = false;
        uiStrokeSuppressed = false;
        strokePointIndex = 0;
    }
};

float normalizePenAxis(int value, int minimum, int maximum, int extent)
{
    if (maximum <= minimum || extent <= 1)
        return 0.0f;
    const int bounded = std::max(minimum, std::min(maximum, value));
    return static_cast<float>(bounded - minimum) *
           static_cast<float>(extent - 1) /
           static_cast<float>(maximum - minimum);
}

QRect drawNativePenSamples(
    NativePenOverlay &overlay,
    const std::deque<FrameMailbox::PenSample> &samples,
    int minimumX,
    int maximumX,
    int minimumY,
    int maximumY,
    int minimumPressure,
    int maximumPressure,
    bool inverted,
    const NotePenSettings &settings)
{
    QRect dirty;
    QPainter painter(&overlay.image);
    painter.setRenderHint(QPainter::Antialiasing, true);
    painter.setCompositionMode(QPainter::CompositionMode_SourceOver);
    QPen pen;
    pen.setCapStyle(Qt::RoundCap);
    pen.setJoinStyle(Qt::RoundJoin);
    painter.setPen(pen);

    for (const FrameMailbox::PenSample &sample : samples) {
        if (!sample.touching) {
            overlay.drawing = false;
            overlay.contactActive = false;
            overlay.uiStrokeSuppressed = false;
            overlay.strokePointIndex = 0;
            continue;
        }

        const QPointF point(
            normalizePenAxis(sample.rawX, minimumX, maximumX, AndroidWidth),
            normalizePenAxis(sample.rawY, minimumY, maximumY, AndroidHeight));
        bool insideControls = false;
        for (int index = 0; index < settings.uiRegionCount; ++index) {
            if (settings.uiRegions[index].contains(point)) {
                insideControls = true;
                break;
            }
        }
        const bool insideLegacyControls =
            settings.uiRegionCount == 0
            && point.y() < settings.uiBottom
            && point.x() < settings.uiLeft;
        /*
         * Latch a contact that begins in the toolbar for its complete life.
         * Without this, sliding a Marker from a button onto the page starts a
         * native ink line halfway through the same physical contact. Stock's
         * input/tool pipelines keep UI contacts separate in the same way.
         */
        if (!overlay.contactActive || sample.strokeStart) {
            overlay.contactActive = true;
            overlay.uiStrokeSuppressed =
                insideControls || insideLegacyControls;
            if (overlay.uiStrokeSuppressed)
                overlay.drawing = false;
        }
        if (overlay.uiStrokeSuppressed) {
            overlay.drawing = false;
            continue;
        }
        float pressure = 0.12f;
        if (maximumPressure > minimumPressure) {
            pressure =
                static_cast<float>(sample.pressure - minimumPressure) /
                static_cast<float>(maximumPressure - minimumPressure);
            pressure = std::max(0.02f, std::min(1.0f, pressure));
        }
        const bool erasing =
            settings.appEraser || sample.hardwareEraser;
        const float averagePressure =
            (overlay.drawing ? overlay.lastPressure + pressure
                             : pressure * 2.0f) *
            0.5f;
        const QPointF from =
            (!overlay.drawing || sample.strokeStart)
                ? point
                : overlay.lastPoint;
        const float segmentDistance =
            static_cast<float>(
                std::hypot(point.x() - from.x(),
                           point.y() - from.y()));
        const bool strokeStart =
            !overlay.drawing || sample.strokeStart;
        if (strokeStart)
            overlay.strokePointIndex = 0;
        const float width =
            erasing
                ? settings.eraserWidth
                : notePenWidth(settings.tool,
                               averagePressure,
                               segmentDistance,
                               strokeStart) *
                      settings.sizeMultiplier;
        const QColor color =
            erasing ? (inverted ? Qt::black : Qt::white)
                    : (inverted ? Qt::white : Qt::black);
        float visualWidth = width;
        if (erasing) {
            painter.setBrush(Qt::NoBrush);
            pen.setColor(color);
            pen.setCapStyle(Qt::RoundCap);
            pen.setJoinStyle(Qt::RoundJoin);
            pen.setWidthF(width);
            painter.setPen(pen);
            if (segmentDistance < 0.01f)
                painter.drawEllipse(point, width * 0.5f, width * 0.5f);
            else
                painter.drawLine(from, point);
        } else if (settings.tool == NoteTool::Pencil) {
            QPointF normal(0.707106f, -0.707106f);
            if (segmentDistance >= 0.01f) {
                normal = QPointF(
                    -(point.y() - from.y()) / segmentDistance,
                    (point.x() - from.x()) / segmentDistance);
            }
            const uint32_t grain =
                pencilCoordinateHash(point) ^
                static_cast<uint32_t>(
                    overlay.strokePointIndex * 2246822519u);
            const float jitter =
                (static_cast<float>(grain & 0xffffu) / 65535.0f -
                 0.5f) *
                width * 0.32f;
            const std::array<float, 3> offsets = {
                -width * 0.38f + jitter,
                jitter * 0.22f,
                width * 0.38f + jitter,
            };
            const std::array<float, 3> widths = {
                std::max(0.42f, width * 0.23f),
                std::max(0.58f, width * 0.52f),
                std::max(0.42f, width * 0.23f),
            };
            const std::array<int, 3> gray = {
                148 - static_cast<int>(averagePressure * 34.0f),
                78 - static_cast<int>(averagePressure * 42.0f),
                174 - static_cast<int>(averagePressure * 30.0f),
            };
            painter.setBrush(Qt::NoBrush);
            for (size_t index = 0; index < offsets.size(); ++index) {
                const QPointF offset = normal * offsets[index];
                pen.setColor(QColor(
                    pencilGray(inverted, gray[index]),
                    pencilGray(inverted, gray[index]),
                    pencilGray(inverted, gray[index])));
                pen.setCapStyle(Qt::RoundCap);
                pen.setWidthF(widths[index]);
                painter.setPen(pen);
                if (segmentDistance < 0.01f) {
                    painter.drawEllipse(
                        point + offset,
                        widths[index] * 0.5f,
                        widths[index] * 0.5f);
                } else {
                    painter.drawLine(from + offset, point + offset);
                }
            }
            visualWidth = width * 1.45f;
        } else if (settings.tool == NoteTool::Marker) {
            const QPointF nib =
                QPointF(0.707106f, -0.707106f) * (width * 0.5f);
            painter.setPen(Qt::NoPen);
            painter.setBrush(color);
            if (segmentDistance < 0.01f) {
                QPolygonF diamond;
                const QPointF side(-nib.y() * 0.18f,
                                   nib.x() * 0.18f);
                diamond << point + nib + side
                        << point - nib + side
                        << point - nib - side
                        << point + nib - side;
                painter.drawPolygon(diamond);
            } else {
                QPolygonF body;
                body << from + nib << from - nib
                     << point - nib << point + nib;
                painter.drawPolygon(body);
                pen.setColor(color);
                pen.setWidthF(std::max(1.2f, width * 0.16f));
                pen.setCapStyle(Qt::SquareCap);
                painter.setPen(pen);
                painter.setBrush(Qt::NoBrush);
                painter.drawLine(point - nib, point + nib);
            }
            visualWidth = width * 1.2f;
        } else {
            painter.setBrush(Qt::NoBrush);
            pen.setColor(color);
            pen.setCapStyle(Qt::RoundCap);
            pen.setJoinStyle(Qt::RoundJoin);
            pen.setWidthF(width);
            painter.setPen(pen);
            if (segmentDistance < 0.01f)
                painter.drawEllipse(point, width * 0.5f, width * 0.5f);
            else
                painter.drawLine(from, point);
        }
        const int padding =
            static_cast<int>(std::ceil(visualWidth * 0.75f + 6.0f));
        const QRect segmentDirty(
            static_cast<int>(std::floor(
                std::min(from.x(), point.x()))) - padding,
            static_cast<int>(std::floor(
                std::min(from.y(), point.y()))) - padding,
            static_cast<int>(std::ceil(
                std::abs(from.x() - point.x()))) + padding * 2 + 1,
            static_cast<int>(std::ceil(
                std::abs(from.y() - point.y()))) + padding * 2 + 1);
        dirty = dirty.isEmpty() ? segmentDirty : dirty.united(segmentDirty);
        overlay.lastPoint = point;
        overlay.lastPressure = pressure;
        overlay.drawing = true;
        ++overlay.strokePointIndex;
    }

    painter.end();
    return dirty.intersected(
        QRect(0, 96, AndroidWidth, AndroidHeight - 96));
}

void composeNativePenOverlay(const NativePenOverlay &overlay,
                             QImage &destination,
                             const QRect &region)
{
    if (!overlay.noteActive || region.isEmpty())
        return;
    const QRect clipped =
        region.intersected(QRect(0, 96, AndroidWidth, AndroidHeight - 96));
    if (clipped.isEmpty())
        return;
    QPainter painter(&destination);
    painter.setCompositionMode(QPainter::CompositionMode_SourceOver);
    painter.drawImage(clipped.topLeft(), overlay.image, clipped);
}

void drawSleepPage(QImage &destination)
{
    loadUiLocale();
    QPainter painter(&destination);
    painter.setCompositionMode(QPainter::CompositionMode_Source);
    painter.fillRect(destination.rect(), Qt::white);
    QFont font = painter.font();
    if (!uiFontFamily.isEmpty())
        font.setFamily(uiFontFamily);
    font.setPixelSize(34);
    font.setWeight(QFont::DemiBold);
    painter.setFont(font);
    painter.drawText(
        QRect(56, 48, destination.width() - 112, 60),
        Qt::AlignLeft | Qt::AlignVCenter,
        QStringLiteral("paper"));

    QPen divider(QColor(80, 80, 80));
    divider.setWidth(2);
    painter.setPen(divider);
    painter.drawLine(56, 126, destination.width() - 56, 126);

    const int centerY = destination.height() / 2;
    font.setPixelSize(24);
    font.setWeight(QFont::Normal);
    painter.setFont(font);
    painter.setPen(QColor(90, 90, 90));
    painter.drawText(
        QRect(0, centerY - 145, destination.width(), 50),
        Qt::AlignCenter,
        QStringLiteral("PAPER HOME"));

    font.setPixelSize(58);
    font.setWeight(QFont::DemiBold);
    painter.setFont(font);
    painter.setPen(Qt::black);
    painter.drawText(
        QRect(0, centerY - 80, destination.width(), 90),
        Qt::AlignCenter,
        uiText("Standby", "대기 중", "待机"));

    font.setPixelSize(25);
    font.setWeight(QFont::Normal);
    painter.setFont(font);
    painter.setPen(QColor(70, 70, 70));
    painter.drawText(
        QRect(0, centerY + 20, destination.width(), 55),
        Qt::AlignCenter,
        uiText(
            "Press the power button to resume",
            "전원 버튼을 눌러 다시 시작",
            "按电源键以继续"));

    auto readInteger = [](const char *path, int fallback) {
        QFile file(QString::fromLatin1(path));
        if (!file.open(QIODevice::ReadOnly))
            return fallback;
        bool ok = false;
        const int value = file.read(32).trimmed().toInt(&ok);
        return ok ? value : fallback;
    };
    const int battery =
        std::clamp(readInteger(DeviceBatteryPath, 0), 0, 100);
    const bool charging = readInteger(ChargerOnlinePath, 0) == 1;
    const int autoPoweroff =
        std::clamp(readInteger(AutoPowerOffPath, 30), 0, 180);

    QString standbyDetail;
    if (charging) {
        standbyDetail = uiText(
            "Charging · automatic power-off paused",
            "충전 중 · 자동 종료 일시 정지",
            "正在充电 · 自动关机已暂停");
    } else if (autoPoweroff > 0) {
        standbyDetail = uiText(
            "Powers off completely in %1 min",
            "%1분 뒤 완전 종료",
            "%1 分钟后完全关机").arg(autoPoweroff);
    } else {
        standbyDetail = uiText(
            "Automatic power-off disabled",
            "자동 종료 꺼짐",
            "自动关机已关闭");
    }
    font.setPixelSize(23);
    painter.setFont(font);
    painter.drawText(
        QRect(0, centerY + 78, destination.width(), 50),
        Qt::AlignCenter,
        standbyDetail);

    painter.setPen(divider);
    painter.drawLine(
        56, destination.height() - 150,
        destination.width() - 56, destination.height() - 150);
    font.setPixelSize(25);
    painter.setPen(Qt::black);
    painter.setFont(font);
    painter.drawText(
        QRect(56, destination.height() - 125,
              destination.width() - 112, 55),
        Qt::AlignLeft | Qt::AlignVCenter,
        uiText("Battery %1%", "배터리 %1%", "电量 %1%").arg(battery));
    painter.drawText(
        QRect(56, destination.height() - 125,
              destination.width() - 112, 55),
        Qt::AlignRight | Qt::AlignVCenter,
        uiText("Android standby", "Android 대기", "Android 待机"));
}

void drawVectorLock(QPainter &painter, const QRect &bounds, int strokeWidth)
{
    const int bodyTop = bounds.top() + bounds.height() * 44 / 100;
    const QRect body(
        bounds.left() + bounds.width() * 17 / 100,
        bodyTop,
        bounds.width() * 66 / 100,
        bounds.height() * 48 / 100);
    const QRect shackle(
        bounds.left() + bounds.width() * 29 / 100,
        bounds.top() + bounds.height() * 7 / 100,
        bounds.width() * 42 / 100,
        bounds.height() * 66 / 100);
    QPen pen(Qt::black);
    pen.setWidth(strokeWidth);
    pen.setCapStyle(Qt::RoundCap);
    pen.setJoinStyle(Qt::RoundJoin);
    painter.setPen(pen);
    painter.setBrush(Qt::NoBrush);
    painter.drawArc(shackle, 0, 180 * 16);
    painter.drawLine(shackle.left(), shackle.center().y(),
                     shackle.left(), bodyTop);
    painter.drawLine(shackle.right(), shackle.center().y(),
                     shackle.right(), bodyTop);
    painter.drawRoundedRect(body, strokeWidth, strokeWidth);
    painter.setBrush(Qt::black);
    painter.drawEllipse(
        QPoint(body.center().x(), body.center().y()),
        std::max(2, strokeWidth), std::max(2, strokeWidth));
}

bool imageContainsChromaticColor(const QImage &image)
{
    const int right = std::min(AndroidWidth, image.width());
    const int bottom = std::min(AndroidHeight, image.height());
    // Locking is rare, so a format-independent sparse scan is preferable to
    // assuming that the mapped EP framebuffer has Android's RGBX byte order.
    for (int y = 0; y < bottom; y += 4) {
        for (int x = 0; x < right; x += 4) {
            const QRgb pixel = image.pixel(x, y);
            const int red = qRed(pixel);
            const int green = qGreen(pixel);
            const int blue = qBlue(pixel);
            const int maximum = std::max({red, green, blue});
            const int minimum = std::min({red, green, blue});
            if (maximum - minimum >= 22 && maximum >= 52)
                return true;
        }
    }
    return false;
}

void drawRetainedLockBadge(QPainter &painter,
                           const QImage &destination,
                           bool compact)
{
    const int badgeWidth = compact ? 184 : 270;
    const int badgeHeight = compact ? 62 : 86;
    const int badgeX = (destination.width() - badgeWidth) / 2;
    const int badgeY = destination.height() - badgeHeight -
        (compact ? 58 : 92);
    const QRect badge(badgeX, badgeY, badgeWidth, badgeHeight);
    painter.setPen(QPen(Qt::black, compact ? 2 : 3));
    painter.setBrush(QColor(255, 255, 255, 224));
    painter.drawRoundedRect(badge, 16, 16);
    const int iconSize = compact ? 32 : 44;
    drawVectorLock(
        painter,
        QRect(badge.left() + 18,
              badge.center().y() - iconSize / 2,
              iconSize, iconSize),
        compact ? 3 : 4);
    QFont font = painter.font();
    if (!uiFontFamily.isEmpty())
        font.setFamily(uiFontFamily);
    font.setPixelSize(compact ? 20 : 26);
    font.setWeight(QFont::DemiBold);
    painter.setFont(font);
    painter.setPen(Qt::black);
    painter.drawText(
        QRect(badge.left() + iconSize + 32, badge.top(),
              badge.width() - iconSize - 46, badge.height()),
        Qt::AlignLeft | Qt::AlignVCenter,
        uiText("Locked", "잠김", "已锁定"));
}

void drawAutoPoweroffHint(QPainter &painter, const QImage &destination)
{
    auto readInteger = [](const char *path, int fallback) {
        QFile file(QString::fromLatin1(path));
        if (!file.open(QIODevice::ReadOnly))
            return fallback;
        bool ok = false;
        const int value = file.read(32).trimmed().toInt(&ok);
        return ok ? value : fallback;
    };
    const bool charging = readInteger(ChargerOnlinePath, 0) == 1;
    const int autoPoweroff =
        std::clamp(readInteger(AutoPowerOffPath, 30), 0, 180);
    QString detail;
    if (charging) {
        detail = uiText(
            "Charging · auto power-off paused",
            "충전 중 · 자동 종료 일시 정지",
            "正在充电 · 自动关机已暂停");
    } else if (autoPoweroff > 0) {
        detail = uiText(
            "Powers off completely in %1 min",
            "%1분 뒤 완전 종료",
            "%1 分钟后完全关机").arg(autoPoweroff);
    } else {
        detail = uiText(
            "Automatic power-off disabled",
            "자동 종료 꺼짐",
            "自动关机已关闭");
    }

    const int width = std::min(540, destination.width() - 72);
    const QRect hint(
        (destination.width() - width) / 2,
        destination.height() - 54,
        width,
        42);
    painter.setPen(QPen(Qt::black, 2));
    painter.setBrush(QColor(255, 255, 255, 224));
    painter.drawRoundedRect(hint, 12, 12);
    QFont font = painter.font();
    if (!uiFontFamily.isEmpty())
        font.setFamily(uiFontFamily);
    font.setPixelSize(20);
    font.setWeight(QFont::Medium);
    painter.setFont(font);
    painter.setPen(Qt::black);
    painter.drawText(hint, Qt::AlignCenter, detail);
}

void drawLockPage(QImage &destination, LockStyle style)
{
    loadUiLocale();
    if (style == LockStyle::Classic) {
        drawSleepPage(destination);
        return;
    }

    QPainter painter(&destination);
    painter.setCompositionMode(QPainter::CompositionMode_SourceOver);
    if (style == LockStyle::Clean) {
        painter.fillRect(destination.rect(), Qt::white);
        const int iconSize = 92;
        drawVectorLock(
            painter,
            QRect((destination.width() - iconSize) / 2,
                  destination.height() / 2 - 105,
                  iconSize, iconSize),
            7);
        QFont font = painter.font();
        if (!uiFontFamily.isEmpty())
            font.setFamily(uiFontFamily);
        font.setPixelSize(32);
        font.setWeight(QFont::DemiBold);
        painter.setFont(font);
        painter.setPen(Qt::black);
        painter.drawText(
            QRect(0, destination.height() / 2 + 8,
                  destination.width(), 54),
            Qt::AlignCenter,
            uiText("Locked", "잠김", "已锁定"));
        drawAutoPoweroffHint(painter, destination);
        return;
    }

    if (style == LockStyle::Reading) {
        painter.fillRect(destination.rect(), QColor(255, 255, 255, 32));
        drawRetainedLockBadge(painter, destination, true);
        drawAutoPoweroffHint(painter, destination);
        return;
    }

    if (style == LockStyle::Clock) {
        painter.fillRect(destination.rect(), QColor(255, 255, 255, 142));
        QFont font = painter.font();
        if (!uiFontFamily.isEmpty())
            font.setFamily(uiFontFamily);
        font.setPixelSize(112);
        font.setWeight(QFont::DemiBold);
        painter.setFont(font);
        painter.setPen(Qt::black);
        painter.drawText(
            QRect(0, destination.height() / 2 - 105,
                  destination.width(), 150),
            Qt::AlignCenter,
            QDateTime::currentDateTime().toString(QStringLiteral("HH:mm")));
        drawRetainedLockBadge(painter, destination, true);
        drawAutoPoweroffHint(painter, destination);
        return;
    }

    // Stock-like default: retain the last panel image only in memory, fade it
    // beneath a white veil, and add a clear lock badge. No screenshot or
    // reader content is written to persistent storage.
    painter.fillRect(destination.rect(), QColor(255, 255, 255, 104));
    drawRetainedLockBadge(painter, destination, false);
    drawAutoPoweroffHint(painter, destination);
}

/*
 * Text contrast. Android anti-aliases glyph edges into mid-grays that the
 * panel shows lighter than ink on paper, so text reads thinner than stock
 * xochitl's typography. A gamma curve darkens those edge pixels while leaving
 * pure white and black untouched: the e-reader "font darkness" control. The
 * table is rebuilt only when the user's setting changes and is read from the
 * display thread only.
 */
enum class TextContrast {
    Normal,
    Dark,
    Darker,
};

std::array<unsigned char, 256> textContrastTable = [] {
    std::array<unsigned char, 256> identity{};
    for (int value = 0; value < 256; ++value)
        identity[static_cast<size_t>(value)] =
            static_cast<unsigned char>(value);
    return identity;
}();

TextContrast readTextContrast()
{
    QFile file(QString::fromLatin1(TextContrastPath));
    if (!file.open(QIODevice::ReadOnly))
        return TextContrast::Normal;
    const QByteArray value = file.read(32).trimmed().toLower();
    if (value == "dark")
        return TextContrast::Dark;
    if (value == "darker")
        return TextContrast::Darker;
    return TextContrast::Normal;
}

QString textContrastName(TextContrast contrast)
{
    switch (contrast) {
    case TextContrast::Dark:
        return QStringLiteral("dark");
    case TextContrast::Darker:
        return QStringLiteral("darker");
    case TextContrast::Normal:
    default:
        return QStringLiteral("normal");
    }
}

void applyTextContrast(TextContrast contrast)
{
    const double gamma =
        contrast == TextContrast::Darker ? 1.9 :
        contrast == TextContrast::Dark ? 1.45 : 1.0;
    for (int value = 0; value < 256; ++value) {
        const double normalized = value / 255.0;
        const double mapped = std::pow(normalized, gamma) * 255.0;
        textContrastTable[static_cast<size_t>(value)] =
            static_cast<unsigned char>(
                std::clamp(static_cast<int>(std::lround(mapped)), 0, 255));
    }
}

unsigned char quantizedLuminance(int luminance,
                                int x,
                                int y,
                                DisplayProfile profile)
{
    luminance = textContrastTable[static_cast<size_t>(
        std::clamp(luminance, 0, 255))];
    if (profile == DisplayProfile::Fast) {
        static constexpr int Bayer4x4[4][4] = {
            { 0,  8,  2, 10},
            {12,  4, 14,  6},
            { 3, 11,  1,  9},
            {15,  7, 13,  5},
        };
        const int threshold = Bayer4x4[y & 3][x & 3] * 16 + 8;
        return luminance >= threshold ? 0xff : 0x00;
    }

    /*
     * Gallery 3's stable grayscale path can retain sixteen levels.  Keeping
     * only eight made Android's hinted font edges collapse into visibly
     * coarse steps even though SurfaceFlinger was rendering at the panel's
     * native 954x1696 resolution.  Balanced deliberately stays at four
     * levels for faster interaction; Quality preserves the extra edge detail
     * for static pages and the Paper Home launcher.
     */
    const int levels =
        profile == DisplayProfile::Quality ? 16 : 4;
    const int maximumIndex = levels - 1;
    const int index =
        (luminance * maximumIndex + 127) / 255;
    return static_cast<unsigned char>(
        (index * 255 + maximumIndex / 2) / maximumIndex);
}

void convertForEink(const unsigned char *source,
                    QByteArray &output,
                    bool inverted,
                    DisplayProfile profile)
{
    output.resize(static_cast<qsizetype>(VisibleBytes));
    auto *destination =
        reinterpret_cast<unsigned char *>(output.data());

    /*
     * Keep the panel on its calm monochrome path, but do not throw away all
     * Android gray values. Fast mode uses stable ordered dithering, balanced
     * mode keeps four gray levels, and quality mode keeps sixteen. This retains
     * font hinting, thin separators, book illustrations, and disabled-state UI
     * while avoiding Gallery 3 color flashing.
     */
    for (int y = 0; y < AndroidHeight; ++y) {
        for (int x = 0; x < AndroidWidth; ++x) {
            const size_t offset =
                static_cast<size_t>(y) * AndroidStrideBytes +
                static_cast<size_t>(x) * BytesPerPixel;
            const int red = source[offset + 0];
            const int green = source[offset + 1];
            const int blue = source[offset + 2];
            int luminance =
                (red * 77 + green * 150 + blue * 29 + 128) >> 8;
            if (inverted)
                luminance = 255 - luminance;
            const unsigned char value =
                quantizedLuminance(luminance, x, y, profile);
            destination[offset + 0] = value;
            destination[offset + 1] = value;
            destination[offset + 2] = value;
            destination[offset + 3] = 0xff;
        }
    }
}

QRect submitSettledColor(EPFramebufferFusion *framebuffer,
                         QImage &destination,
                         const QByteArray &latestRgb,
                         QByteArray &colorBuffer,
                         const QRect &requested,
                         bool inverted,
                         const NativePenOverlay &penOverlay,
                         const QByteArray &previousDigest,
                         const QRect &previousRect,
                         bool suppressDuplicate,
                         bool &duplicate,
                         QByteArray &settledDigest,
                         qint64 &submitMilliseconds)
{
    duplicate = false;
    settledDigest.clear();
    submitMilliseconds = 0;
    if (latestRgb.size() < static_cast<qsizetype>(VisibleBytes))
        return QRect();

    const auto *pixels =
        reinterpret_cast<const unsigned char *>(latestRgb.constData());
    const std::vector<QRect> colorRectangles =
        chromaticRectangles(pixels, requested);
    const QRect colorRect = rectanglesBoundingRect(colorRectangles);
    if (colorRectangles.empty() || colorRect.isEmpty())
        return QRect();

    QCryptographicHash digest(QCryptographicHash::Sha256);
    for (const QRect &region : colorRectangles) {
        const std::array<qint32, 4> geometry = {
            region.x(), region.y(), region.width(), region.height()};
        digest.addData(
            reinterpret_cast<const char *>(geometry.data()),
            static_cast<qsizetype>(sizeof(geometry)));
        for (int y = region.top(); y <= region.bottom(); ++y) {
            const size_t offset =
                static_cast<size_t>(y) * AndroidStrideBytes +
                static_cast<size_t>(region.left()) * BytesPerPixel;
            digest.addData(
                reinterpret_cast<const char *>(pixels + offset),
                static_cast<qsizetype>(
                    region.width() * BytesPerPixel));
        }
    }
    settledDigest = digest.result();
    if (suppressDuplicate &&
        colorRect == previousRect &&
        settledDigest == previousDigest) {
        duplicate = true;
        return colorRect;
    }

    for (const QRect &region : colorRectangles)
        convertColorForEink(pixels, colorBuffer, region, inverted);
    QImage source(
        reinterpret_cast<const unsigned char *>(colorBuffer.constData()),
        AndroidWidth,
        AndroidHeight,
        AndroidStrideBytes,
        QImage::Format_RGBX8888);
    QPainter painter(&destination);
    painter.setCompositionMode(QPainter::CompositionMode_Source);
    for (const QRect &region : colorRectangles)
        painter.drawImage(region.topLeft(), source, region);
    painter.end();
    // A completed Note color settle deliberately releases note ownership.
    // Never paint its stale monochrome FastPen overlay over the RGB frame.
    if (penOverlay.noteActive && readNoteActive()) {
        for (const QRect &region : colorRectangles)
            composeNativePenOverlay(penOverlay, destination, region);
    }

    /*
     * This is the slow Gallery 3 operation. It runs only after the Android
     * frame has stopped changing, on the panel worker thread, so SurfaceFlinger
     * and touch input remain decoupled while sync() waits for the physical
     * waveform.
     */
    const auto started = std::chrono::steady_clock::now();
    for (const QRect &region : colorRectangles) {
        framebuffer->swapBuffers(
            region,
            EPContentType::Color,
            EPScreenMode::Content,
            QFlags<EPFramebuffer::UpdateFlag>(
                EPFramebuffer::UpdateFlag::UIUpdate));
    }
    framebuffer->sync();
    const auto finished = std::chrono::steady_clock::now();
    submitMilliseconds =
        std::chrono::duration_cast<std::chrono::milliseconds>(
            finished - started).count();
    writeLog(QStringLiteral(
                 "regional color settle regions=%1 bounds=%2,%3 %4x%5")
                 .arg(static_cast<qulonglong>(colorRectangles.size()))
                 .arg(colorRect.x())
                 .arg(colorRect.y())
                 .arg(colorRect.width())
                 .arg(colorRect.height()));
    return colorRect;
}

bool markDisplayReady()
{
    const int fd =
        ::open(ReadyPath, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0)
        return false;
    const QByteArray value =
        QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs).toUtf8() +
        '\n';
    const bool ok =
        ::write(fd, value.constData(), static_cast<size_t>(value.size())) ==
        value.size();
    ::fsync(fd);
    ::close(fd);
    return ok;
}

int runDecoupledDisplay(EPFramebufferFusion *framebuffer,
                        QImage &destination)
{
    FrameMailbox mailbox;
    std::thread receiver(receiveHwcFrames, &mailbox);
    std::thread penReceiver(receivePenSamples, &mailbox);
    NativePenOverlay penOverlay;
    QByteArray previous;
    previous.resize(static_cast<qsizetype>(VisibleBytes));
    bool havePrevious = false;
    bool ready = false;
    bool displayFailure = false;
    uint64_t consumedFrames = 0;
    uint64_t consumedPenPresenceGeneration = 0;
    uint64_t displayedFrames = 0;
    std::shared_ptr<QByteArray> deferredAndroidFrame;
    QByteArray converted;
    QByteArray latestRgb;
    QByteArray colorBuffer;
    bool haveLatestRgb = false;
    QRect pendingColorSettle;
    QByteArray lastColorDigest;
    QRect lastColorRect;
    DisplayProfile activeProfile = DisplayProfile::Quality;
    ColorMode activeColorMode = readColorMode();
    bool activeInvert = false;
    bool haveControlState = false;
    bool screenOff = readScreenOff();
    bool screenWakeFullRefreshPending = false;
    bool overlayResetPending = false;
    uint64_t overlayResetBaseline = 0;
    bool toolbarRefreshPending = false;
    int toolbarRefreshRight = AndroidWidth;
    int toolbarRefreshBottom = ToolbarHeight;
    QRect pendingIdleCleanup;
    QRect pendingEraserCleanup;
    qint64 accumulatedGhostArea = 0;
    ReaderRefreshPolicy readerPolicy = readReaderRefreshPolicy();
    GhostControlPolicy ghostPolicy = readGhostControlPolicy();
    TextContrast textContrast = readTextContrast();
    applyTextContrast(textContrast);
    int readerPagesSinceCleanup = 0;
    QRect readerPendingCleanup;
    bool readerCleanupFrame = false;
    auto lastReaderPageTurn =
        std::chrono::steady_clock::now() -
        std::chrono::seconds(10);
    auto nextPolicyRead =
        std::chrono::steady_clock::time_point::min();
    auto nextPenSubmit =
        std::chrono::steady_clock::time_point::min();
    auto nextPenControlRead =
        std::chrono::steady_clock::time_point::min();
    NotePenSettings cachedPenSettings;
    auto nextAndroidSubmit =
        std::chrono::steady_clock::time_point::min();
    auto lastPanelActivity =
        std::chrono::steady_clock::now();
    auto lastContentActivity =
        std::chrono::steady_clock::now();
    auto nextAutoColorSettle =
        std::chrono::steady_clock::time_point::min();
    auto lastPenActivity =
        std::chrono::steady_clock::now() -
        std::chrono::seconds(10);
    auto lastTouchActivity =
        std::chrono::steady_clock::now() -
        std::chrono::seconds(10);
    auto lastTypingActivity =
        std::chrono::steady_clock::now() -
        std::chrono::seconds(10);
    bool touchSettlePending = false;
    bool typingSettlePending = false;

    writeLog(QStringLiteral(
        "decoupled HWC + native pen receivers active; "
        "16ms pen coalescing + adaptive 18/30ms Android pacing + "
        "idle Gallery 3 color settle"));
    while (!stopRequested) {
        applyDeveloperLauncherUpdate();
        applyDeveloperBridgeUpdate();
        std::shared_ptr<QByteArray> frame;
        std::deque<FrameMailbox::PenSample> penSamples;
        uint64_t receivedFrames = 0;
        uint64_t receivedPenReports = 0;
        int penMinX = 0;
        int penMaxX = 6760;
        int penMinY = 0;
        int penMaxY = 11960;
        int penMinPressure = 0;
        int penMaxPressure = 4096;
        bool penInRange = false;
        bool receiverEnded = false;
        {
            std::unique_lock<std::mutex> lock(mailbox.mutex);
            const auto waitNow = std::chrono::steady_clock::now();
            const bool touchActiveBeforeWait =
                ::access(TouchActivePath, F_OK) == 0;
            const bool typingActiveBeforeWait =
                ::access(TypingActivePath, F_OK) == 0;
            const bool waitingForColor =
                !pendingColorSettle.isEmpty() &&
                activeColorMode != ColorMode::Mono &&
                (isOneShotColorMode(activeColorMode) ||
                 waitNow >= nextAutoColorSettle) &&
                !screenOff;
            auto waitDuration =
                pendingIdleCleanup.isEmpty() &&
                        pendingEraserCleanup.isEmpty()
                    ? (waitingForColor
                           ? std::chrono::milliseconds(
                                 ColorControlPollMs)
                           : std::chrono::milliseconds(1000))
                    : std::chrono::milliseconds(
                          NativePenControlPollMs);
            if (touchActiveBeforeWait || typingActiveBeforeWait ||
                touchSettlePending || typingSettlePending) {
                waitDuration = std::min(
                    waitDuration,
                    std::chrono::milliseconds(TouchStatePollMs));
            }
            mailbox.changed.wait_for(
                lock,
                waitDuration,
                [&mailbox, consumedFrames,
                 consumedPenPresenceGeneration]() {
                    return stopRequested ||
                           !mailbox.penSamples.empty() ||
                           mailbox.receivedFrames != consumedFrames ||
                           mailbox.penPresenceGeneration !=
                               consumedPenPresenceGeneration ||
                           mailbox.receiverEnded;
                });
            if (stopRequested)
                break;
            receiverEnded = mailbox.receiverEnded;
            receivedFrames = mailbox.receivedFrames;
            receivedPenReports = mailbox.receivedPenReports;
            penInRange = mailbox.penInRange;
            consumedPenPresenceGeneration =
                mailbox.penPresenceGeneration;
            if (!mailbox.penSamples.empty()) {
                penSamples.swap(mailbox.penSamples);
                penMinX = mailbox.penMinX;
                penMaxX = mailbox.penMaxX;
                penMinY = mailbox.penMinY;
                penMaxY = mailbox.penMaxY;
                penMinPressure = mailbox.penMinPressure;
                penMaxPressure = mailbox.penMaxPressure;
            } else if (receivedFrames != consumedFrames) {
                frame = mailbox.latest;
                consumedFrames = receivedFrames;
            } else if (receiverEnded) {
                displayFailure = true;
                break;
            }
        }
        if (!frame && !penInRange && deferredAndroidFrame) {
            frame = deferredAndroidFrame;
            deferredAndroidFrame.reset();
        }
        bool manualRefresh =
            ::access(RefreshRequestPath, F_OK) == 0;
        if (manualRefresh && !penInRange)
            ::unlink(RefreshRequestPath);
        else if (manualRefresh)
            manualRefresh = false;
        const auto controlNow = std::chrono::steady_clock::now();
        const bool touchActive =
            ::access(TouchActivePath, F_OK) == 0;
        const bool typingActive =
            ::access(TypingActivePath, F_OK) == 0;
        if (touchActive) {
            lastTouchActivity = controlNow;
            touchSettlePending = true;
        }
        if (typingActive) {
            lastTypingActivity = controlNow;
            typingSettlePending = true;
        }
        const bool touchFastPath =
            touchActive ||
            controlNow - lastTouchActivity <
                std::chrono::milliseconds(TouchFastTailMs);
        const bool typingFastPath =
            typingActive ||
            controlNow - lastTypingActivity <
                std::chrono::milliseconds(TouchFastTailMs);
        const bool interactiveFastPath = touchFastPath || typingFastPath;
        const bool interactiveSettleReady =
            (touchSettlePending || typingSettlePending) &&
            !touchActive &&
            !typingActive &&
            controlNow - lastTouchActivity >=
                std::chrono::milliseconds(TouchSettleDelayMs) &&
            controlNow - lastTypingActivity >=
                std::chrono::milliseconds(TouchSettleDelayMs);
        bool interactiveSettleFrame = false;
        if (interactiveSettleReady && !frame && haveLatestRgb) {
            frame = std::make_shared<QByteArray>(latestRgb);
            interactiveSettleFrame = true;
            touchSettlePending = false;
            typingSettlePending = false;
            writeLog(QStringLiteral(
                "adaptive interaction idle settle using selected profile"));
        } else if (interactiveSettleReady && !haveLatestRgb) {
            touchSettlePending = false;
            typingSettlePending = false;
        }
        const ColorMode requestedColorMode = readColorMode();
        if (requestedColorMode != activeColorMode) {
            activeColorMode = requestedColorMode;
            if (activeColorMode == ColorMode::Mono) {
                pendingColorSettle = QRect();
                /*
                 * Repaint from the retained RGB frame so an existing Gallery
                 * 3 image really returns to monochrome instead of merely
                 * receiving a monochrome waveform over color pixels.
                 */
                if (haveLatestRgb) {
                    frame = std::make_shared<QByteArray>(latestRgb);
                    havePrevious = false;
                    manualRefresh = true;
                }
            } else if (haveLatestRgb) {
                pendingColorSettle = destination.rect();
                if (activeColorMode == ColorMode::Auto)
                    nextAutoColorSettle =
                        std::chrono::steady_clock::time_point::min();
                lastContentActivity =
                    isOneShotColorMode(activeColorMode)
                        ? controlNow -
                              std::chrono::milliseconds(
                                  ColorSettleDelayMs)
                        : controlNow;
            }
            writeLog(QStringLiteral("color control mode=%1")
                         .arg(colorModeName(activeColorMode)));
        }
        if (manualRefresh && !frame && haveLatestRgb) {
            frame = std::make_shared<QByteArray>(latestRgb);
            havePrevious = false;
        }
        bool automaticGhostRefresh = false;
        if (!manualRefresh &&
            !frame &&
            penSamples.empty() &&
            !penInRange &&
            !screenOff &&
            pendingColorSettle.isEmpty() &&
            !readNoteActive() &&
            accumulatedGhostArea >=
                PanelArea * GhostBudgetScreenMultiples &&
            controlNow - lastPanelActivity >=
                std::chrono::milliseconds(GhostCleanupIdleMs) &&
            controlNow - lastPenActivity >=
                std::chrono::milliseconds(GhostCleanupIdleMs)) {
            manualRefresh = true;
            automaticGhostRefresh = true;
            writeLog(QStringLiteral(
                         "ghost budget reached area=%1; idle full cleanup")
                         .arg(accumulatedGhostArea));
        }
        if (controlNow >= nextPolicyRead) {
            const TextContrast requestedContrast = readTextContrast();
            if (requestedContrast != textContrast) {
                textContrast = requestedContrast;
                applyTextContrast(textContrast);
                writeLog(QStringLiteral("text contrast=%1")
                             .arg(textContrastName(textContrast)));
                /* Re-present the retained frame with the new curve. */
                if (haveLatestRgb && !frame && penSamples.empty()) {
                    frame = std::make_shared<QByteArray>(latestRgb);
                    havePrevious = false;
                }
            }
            const ReaderRefreshPolicy requestedReaderPolicy =
                readReaderRefreshPolicy();
            const GhostControlPolicy requestedGhostPolicy =
                readGhostControlPolicy();
            if (requestedReaderPolicy != readerPolicy ||
                requestedGhostPolicy != ghostPolicy) {
                readerPolicy = requestedReaderPolicy;
                ghostPolicy = requestedGhostPolicy;
                readerPagesSinceCleanup = 0;
                readerPendingCleanup = QRect();
                writeLog(QStringLiteral(
                             "reader refresh policy=%1 ghost control=%2")
                             .arg(readerRefreshPolicyName(readerPolicy))
                             .arg(ghostControlPolicyName(ghostPolicy)));
            }
            nextPolicyRead =
                controlNow + std::chrono::milliseconds(ReaderPolicyPollMs);
        }
        if (::access(GhostRequestPath, F_OK) == 0) {
            ::unlink(GhostRequestPath);
            /*
             * Paper Home reports a settled window transition (app switch,
             * dialog open or close): the Android counterpart of the view
             * changes stock xochitl routes through its GhostBuster. Inside a
             * reader it counts as one page towards the selected policy;
             * elsewhere it charges half a panel to the ghost budget so the
             * existing idle cleanup arrives sooner without adding flashes to
             * ordinary navigation.
             */
            if (readReaderActive() &&
                readerPagesPerCleanup(readerPolicy) > 0 && haveLatestRgb) {
                lastReaderPageTurn = controlNow;
                if (++readerPagesSinceCleanup >=
                    readerPagesPerCleanup(readerPolicy)) {
                    readerPendingCleanup = destination.rect();
                }
            } else {
                accumulatedGhostArea = std::min(
                    PanelArea * GhostBudgetScreenMultiples * 2,
                    accumulatedGhostArea + PanelArea / 2);
            }
        }
        if (!manualRefresh && !frame &&
            !readerPendingCleanup.isEmpty() &&
            penSamples.empty() && !penInRange && !screenOff &&
            !interactiveFastPath && pendingColorSettle.isEmpty() &&
            haveLatestRgb &&
            controlNow - lastPanelActivity >=
                std::chrono::milliseconds(ReaderCleanupIdleMs) &&
            controlNow - lastReaderPageTurn >=
                std::chrono::milliseconds(ReaderCleanupIdleMs)) {
            bool handled = false;
            if (ghostPolicy != GhostControlPolicy::Off) {
                QString detail;
                handled = invokeStockGhostControl(
                    framebuffer, ghostControlKey(ghostPolicy), &detail);
                writeLog(QStringLiteral(
                             "stock ghost control key=%1 ok=%2 %3")
                             .arg(QString::fromLatin1(
                                      ghostControlKey(ghostPolicy)))
                             .arg(handled)
                             .arg(detail));
            }
            if (handled) {
                readerPendingCleanup = QRect();
                readerPagesSinceCleanup = 0;
                accumulatedGhostArea = 0;
                lastPanelActivity = std::chrono::steady_clock::now();
                QCoreApplication::processEvents();
                continue;
            }
            /*
             * Repainting identical pixels with a partial waveform would not
             * move the ghosted particles; the cleanup is a full refresh from
             * the retained RGB frame, the same flash a dedicated e-reader
             * performs after its configured number of pages.
             */
            frame = std::make_shared<QByteArray>(latestRgb);
            havePrevious = false;
            manualRefresh = true;
            readerCleanupFrame = true;
            writeLog(QStringLiteral(
                         "reader cleanup full refresh region=%1,%2 %3x%4 "
                         "pages=%5 policy=%6")
                         .arg(readerPendingCleanup.x())
                         .arg(readerPendingCleanup.y())
                         .arg(readerPendingCleanup.width())
                         .arg(readerPendingCleanup.height())
                         .arg(readerPagesSinceCleanup)
                         .arg(readerRefreshPolicyName(readerPolicy)));
        }
        if (::access(NoteToolbarRefreshPath, F_OK) == 0) {
            const QByteArray requestedRegion =
                readNoteControl(NoteToolbarRefreshPath);
            const int comma = requestedRegion.indexOf(',');
            if (comma > 0) {
                bool numericRight = false;
                bool numericBottom = false;
                const int requestedRight =
                    requestedRegion.left(comma).toInt(&numericRight);
                const int requestedBottom =
                    requestedRegion.mid(comma + 1).toInt(&numericBottom);
                if (numericRight && numericBottom) {
                    toolbarRefreshRight = std::max(
                        1, std::min(AndroidWidth, requestedRight));
                    toolbarRefreshBottom = std::max(
                        1, std::min(AndroidHeight, requestedBottom));
                }
            } else {
                bool numericBottom = false;
                const int requestedBottom =
                    requestedRegion.toInt(&numericBottom);
                if (numericBottom) {
                    toolbarRefreshRight = AndroidWidth;
                    toolbarRefreshBottom = std::max(
                        1, std::min(AndroidHeight, requestedBottom));
                }
            }
            ::unlink(NoteToolbarRefreshPath);
            /*
             * The low-latency pen layer is composed after Android's HWC
             * frame.  Keeping it alive while a popup is painted therefore
             * puts the previous handwriting above the popup.  The Note app
             * has already committed the stroke to its page bitmap before it
             * requests this refresh, so the Android frame is now the source
             * of truth and the native overlay can be dropped safely.
             */
            penOverlay.clear();
            pendingIdleCleanup = QRect();
            toolbarRefreshPending = true;
        }
        if (::access(NoteOverlayResetPath, F_OK) == 0) {
            ::unlink(NoteOverlayResetPath);
            penOverlay.clear();
            pendingIdleCleanup = QRect();
            havePrevious = false;
            overlayResetPending = true;
            overlayResetBaseline = receivedFrames;
            frame.reset();
            writeLog(QStringLiteral(
                "native pen overlay reset requested at HWC frame=%1")
                         .arg(overlayResetBaseline));
        }
        if (overlayResetPending) {
            if (receivedFrames <= overlayResetBaseline || penInRange) {
                QCoreApplication::processEvents();
                continue;
            }
            penOverlay.clear();
            overlayResetPending = false;
            manualRefresh = true;
            havePrevious = false;
            writeLog(QStringLiteral(
                "native pen overlay reset applying HWC frame=%1")
                         .arg(receivedFrames));
        }
        bool wakeRestoreThisFrame = false;
        const bool requestedScreenOff = readScreenOff();
        const bool screenStateChanged = requestedScreenOff != screenOff;
        if (screenStateChanged) {
            screenOff = requestedScreenOff;
            pendingIdleCleanup = QRect();
            pendingEraserCleanup = QRect();
            pendingColorSettle = QRect();
            havePrevious = false;
            if (screenOff) {
                penOverlay.clear();
                const LockStyle lockStyle = readLockStyle();
                const bool retainedStyle =
                    lockStyle == LockStyle::Fade ||
                    lockStyle == LockStyle::Reading ||
                    lockStyle == LockStyle::Clock;
                /*
                 * SurfaceFlinger may publish its monochrome screen-off frame
                 * before the host-side screen-state file reaches this loop.
                 * Rebuild the retained page from the last live RGB HWC frame
                 * instead of fading that transient monochrome buffer.  This
                 * stays entirely in memory; no reader page is persisted.
                 */
                if (retainedStyle &&
                    haveLatestRgb &&
                    latestRgb.size() >=
                        static_cast<qsizetype>(VisibleBytes)) {
                    const auto *retainedPixels =
                        reinterpret_cast<const unsigned char *>(
                            latestRgb.constData());
                    convertForEink(
                        retainedPixels,
                        converted,
                        activeInvert,
                        activeProfile);
                    QImage retainedMonochrome(
                        reinterpret_cast<const unsigned char *>(
                            converted.constData()),
                        AndroidWidth,
                        AndroidHeight,
                        AndroidStrideBytes,
                        QImage::Format_RGBX8888);
                    QPainter retainedPainter(&destination);
                    retainedPainter.setCompositionMode(
                        QPainter::CompositionMode_Source);
                    retainedPainter.drawImage(
                        QPoint(0, 0), retainedMonochrome);
                    retainedPainter.end();

                    if (activeColorMode != ColorMode::Mono) {
                        const std::vector<QRect> retainedColorRegions =
                            chromaticRectangles(
                                retainedPixels, destination.rect());
                        for (const QRect &region : retainedColorRegions)
                            convertColorForEink(
                                retainedPixels,
                                colorBuffer,
                                region,
                                activeInvert);
                        QImage retainedColor(
                            reinterpret_cast<const unsigned char *>(
                                colorBuffer.constData()),
                            AndroidWidth,
                            AndroidHeight,
                            AndroidStrideBytes,
                            QImage::Format_RGBX8888);
                        QPainter colorPainter(&destination);
                        colorPainter.setCompositionMode(
                            QPainter::CompositionMode_Source);
                        for (const QRect &region : retainedColorRegions)
                            colorPainter.drawImage(
                                region.topLeft(), retainedColor, region);
                        colorPainter.end();
                    }
                }
                const bool preserveColor =
                    retainedStyle &&
                    activeColorMode != ColorMode::Mono &&
                    imageContainsChromaticColor(destination);
                drawLockPage(destination, lockStyle);
                const auto sleepStarted =
                    std::chrono::steady_clock::now();
                framebuffer->swapBuffers(
                    destination.rect(),
                    preserveColor
                        ? EPContentType::Color
                        : EPContentType::Monochrome,
                    EPScreenMode::Content,
                    preserveColor
                        ? QFlags<EPFramebuffer::UpdateFlag>(
                              EPFramebuffer::UpdateFlag::UIUpdate)
                        : QFlags<EPFramebuffer::UpdateFlag>(
                              EPFramebuffer::UpdateFlag::UIUpdate |
                              EPFramebuffer::UpdateFlag::FullUpdate));
                framebuffer->sync();
                const auto sleepFinished =
                    std::chrono::steady_clock::now();
                writeLog(QStringLiteral(
                             "Android screen-off lock page displayed "
                             "style=%1 color=%2 submit_ms=%3")
                             .arg(lockStyleName(lockStyle))
                             .arg(preserveColor ? QStringLiteral("yes")
                                                : QStringLiteral("no"))
                             .arg(std::chrono::duration_cast<
                                      std::chrono::milliseconds>(
                                      sleepFinished - sleepStarted)
                                      .count()));
                QCoreApplication::processEvents();
                continue;
            }

            {
                const std::lock_guard<std::mutex> lock(mailbox.mutex);
                if (!frame) {
                    frame = mailbox.latest;
                    receivedFrames = mailbox.receivedFrames;
                }
            }
            if (!frame) {
                /*
                 * A wake can race an HWC stream reconnect.  Repainting the
                 * retained sleep/old destination here makes Android content
                 * appear before SystemUI's navigation icons even though the
                 * invisible button hit regions are already active.  Keep the
                 * sleep image on the panel until one real post-wake HWC frame
                 * is available, then restore that complete frame once.
                 */
                screenWakeFullRefreshPending = true;
                writeLog(QStringLiteral(
                    "Android screen-on awaiting first live HWC frame"));
                QCoreApplication::processEvents();
                continue;
            }
            screenWakeFullRefreshPending = false;
            manualRefresh = true;
            wakeRestoreThisFrame = true;
            if (activeColorMode != ColorMode::Mono) {
                pendingColorSettle = destination.rect();
                lastContentActivity = controlNow;
            }
            writeLog(QStringLiteral(
                "Android screen-on forcing full panel restore"));
        }
        if (screenOff)
            continue;
        if (screenWakeFullRefreshPending && frame) {
            screenWakeFullRefreshPending = false;
            manualRefresh = true;
            wakeRestoreThisFrame = true;
            havePrevious = false;
            writeLog(QStringLiteral(
                "Android screen-on restoring first live HWC frame"));
        }
        if (penInRange && manualRefresh && !wakeRestoreThisFrame) {
            if (frame)
                deferredAndroidFrame = frame;
            QCoreApplication::processEvents();
            continue;
        }

        const auto colorNow = std::chrono::steady_clock::now();
        const bool noteColorSettle = readNoteActive();
        const bool colorIdle =
            activeColorMode != ColorMode::Mono &&
            haveLatestRgb &&
            !pendingColorSettle.isEmpty() &&
            !frame &&
            !manualRefresh &&
            penSamples.empty() &&
            !penInRange &&
            (isOneShotColorMode(activeColorMode) ||
             colorNow >= nextAutoColorSettle) &&
            colorNow - lastContentActivity >=
                std::chrono::milliseconds(
                    isOneShotColorMode(activeColorMode)
                        ? 0 : ColorSettleDelayMs) &&
            colorNow - lastPenActivity >=
                std::chrono::milliseconds(ColorSettleDelayMs);
        if (colorIdle) {
            if (noteColorSettle) {
                /*
                 * Native FastPen is intentionally monochrome so live ink can
                 * follow the Marker without waiting for Gallery 3.  Once the
                 * Marker has been out of range for the normal color-idle
                 * delay, NoteActivity's committed RGB bitmap is authoritative.
                 * Drop the temporary monochrome overlay before submitting the
                 * retained RGB regions; otherwise it is composed over the
                 * colored stroke and makes every Note color look black.
                 */
                penOverlay.clear();
                pendingIdleCleanup = QRect();
                pendingEraserCleanup = QRect();
                writeLog(QStringLiteral(
                    "note idle color settle released native pen overlay"));
            }
            const QRect requestedColor = pendingColorSettle;
            pendingColorSettle = QRect();
            bool duplicateColor = false;
            QByteArray settledDigest;
            qint64 colorSubmitMilliseconds = 0;
            const QRect settled = submitSettledColor(
                framebuffer,
                destination,
                latestRgb,
                colorBuffer,
                requestedColor,
                activeInvert,
                penOverlay,
                lastColorDigest,
                lastColorRect,
                activeColorMode == ColorMode::Auto,
                duplicateColor,
                settledDigest,
                colorSubmitMilliseconds);
            if (!settled.isEmpty() && !duplicateColor) {
                lastPanelActivity = std::chrono::steady_clock::now();
                accumulatedGhostArea = 0;
                nextAndroidSubmit = lastPanelActivity;
                lastColorDigest = settledDigest;
                lastColorRect = settled;
                if (activeColorMode == ColorMode::Auto) {
                    const qint64 cooldownMilliseconds = std::clamp(
                        colorSubmitMilliseconds * 2,
                        static_cast<qint64>(ColorAutoCooldownMs),
                        static_cast<qint64>(ColorMaximumCooldownMs));
                    nextAutoColorSettle =
                        lastPanelActivity +
                        std::chrono::milliseconds(cooldownMilliseconds);
                }
                writeLog(QStringLiteral(
                             "idle color settle requested=%1,%2 %3x%4 "
                             "color=%5,%6 %7x%8 submit_ms=%9 "
                             "cooldown_ms=%10")
                             .arg(requestedColor.x())
                             .arg(requestedColor.y())
                             .arg(requestedColor.width())
                             .arg(requestedColor.height())
                             .arg(settled.x())
                             .arg(settled.y())
                             .arg(settled.width())
                             .arg(settled.height())
                             .arg(colorSubmitMilliseconds)
                             .arg(activeColorMode == ColorMode::Auto
                                      ? std::chrono::duration_cast<
                                            std::chrono::milliseconds>(
                                            nextAutoColorSettle -
                                            lastPanelActivity).count()
                                      : 0));
            } else if (duplicateColor) {
                writeLog(QStringLiteral(
                             "idle color settle skipped: unchanged "
                             "color=%1,%2 %3x%4")
                             .arg(settled.x())
                             .arg(settled.y())
                             .arg(settled.width())
                             .arg(settled.height()));
            } else {
                writeLog(QStringLiteral(
                             "idle color settle skipped: no material "
                             "chromatic content in %1,%2 %3x%4")
                             .arg(requestedColor.x())
                             .arg(requestedColor.y())
                             .arg(requestedColor.width())
                             .arg(requestedColor.height()));
            }
            if (isOneShotColorMode(activeColorMode)) {
                const ColorMode resumedMode =
                    activeColorMode == ColorMode::OnceAuto
                        ? ColorMode::Auto
                        : ColorMode::Mono;
                if (!writeColorMode(resumedMode)) {
                    writeLog(QStringLiteral(
                        "manual color settle could not reset control"));
                }
                activeColorMode = resumedMode;
            }
            QCoreApplication::processEvents();
            continue;
        }
        if (!penSamples.empty()) {
            /*
             * The Elan marker reports at several hundred Hz while the panel
             * can only consume a fraction of that rate. v28 submitted every
             * report and filled the asynchronous TCon queue, so the library
             * returned in 0 ms while visible ink fell behind. Preserve every
             * point, but submit one combined dirty rectangle per 16 ms.
             */
            auto penNow = std::chrono::steady_clock::now();
            const bool releaseAlreadyQueued =
                !penSamples.back().touching;
            if (!releaseAlreadyQueued && penNow < nextPenSubmit) {
                std::unique_lock<std::mutex> lock(mailbox.mutex);
                mailbox.changed.wait_until(
                    lock,
                    nextPenSubmit,
                    [&mailbox]() {
                        return stopRequested ||
                               mailbox.penReceiverEnded;
                    });
                if (stopRequested)
                    break;
                if (!mailbox.penSamples.empty()) {
                    std::deque<FrameMailbox::PenSample> additional;
                    additional.swap(mailbox.penSamples);
                    while (!additional.empty()) {
                        penSamples.push_back(additional.front());
                        additional.pop_front();
                    }
                    receivedPenReports = mailbox.receivedPenReports;
                    penMinX = mailbox.penMinX;
                    penMaxX = mailbox.penMaxX;
                    penMinY = mailbox.penMinY;
                    penMaxY = mailbox.penMaxY;
                    penMinPressure = mailbox.penMinPressure;
                    penMaxPressure = mailbox.penMaxPressure;
                }
                penNow = std::chrono::steady_clock::now();
            }
            lastPenActivity = penNow;
            const bool noteActive = readNoteActive();
            const bool requestedInvert = readInvertMode();
            if (noteActive != penOverlay.noteActive ||
                requestedInvert != penOverlay.inverted) {
                penOverlay.clear();
                penOverlay.noteActive = noteActive;
                penOverlay.inverted = requestedInvert;
                writeLog(QStringLiteral(
                             "native pen overlay active=%1 invert=%2")
                             .arg(noteActive)
                             .arg(requestedInvert));
            }
            if (noteActive) {
                const bool strokeStarting =
                    std::any_of(
                        penSamples.cbegin(), penSamples.cend(),
                        [](const FrameMailbox::PenSample &sample) {
                            return sample.strokeStart;
                        });
                /*
                 * Tool controls change only from toolbar actions. Opening
                 * four ext4 files for every 16 ms pen batch added avoidable
                 * I/O and scheduler jitter on Android 16. Refresh at every
                 * stroke start for correctness, otherwise use the existing
                 * 250 ms control interval.
                 */
                if (strokeStarting || penNow >= nextPenControlRead) {
                    cachedPenSettings = readNotePenSettings();
                    nextPenControlRead =
                        penNow + std::chrono::milliseconds(
                             NativePenControlPollMs);
                }
                const bool eraserBatch =
                    cachedPenSettings.appEraser ||
                    std::any_of(
                        penSamples.cbegin(), penSamples.cend(),
                        [](const FrameMailbox::PenSample &sample) {
                            return sample.hardwareEraser;
                        });
                const QRect penDirty = drawNativePenSamples(
                    penOverlay,
                    penSamples,
                    penMinX,
                    penMaxX,
                    penMinY,
                    penMaxY,
                    penMinPressure,
                    penMaxPressure,
                    requestedInvert,
                    cachedPenSettings);
                if (!penDirty.isEmpty()) {
                    if (eraserBatch) {
                        pendingEraserCleanup =
                            pendingEraserCleanup.isEmpty()
                                ? penDirty
                                : pendingEraserCleanup.united(penDirty);
                    }
                    composeNativePenOverlay(
                        penOverlay, destination, penDirty);
                    const auto penSubmitStarted =
                        std::chrono::steady_clock::now();
                    /*
                     * Match the stock Qt scene-graph path: the dirty region
                     * is classified as EPScreenMode::Pen, while update flags
                     * remain zero. On Acep2 this selects PixelMode 13. Using
                     * PenUpdate here instead selects PixelMode 14, which can
                     * leave visible ink waiting behind a deferred T-mode
                     * update even though swapBuffers() returns immediately.
                     */
                    framebuffer->swapBuffers(
                        penDirty,
                        EPContentType::Monochrome,
                        EPScreenMode::Pen,
                        QFlags<EPFramebuffer::UpdateFlag>());
                    const auto penSubmitFinished =
                        std::chrono::steady_clock::now();
                    nextPenSubmit =
                        penSubmitFinished +
                        std::chrono::milliseconds(
                            NativePenSubmitIntervalMs);
                    pendingIdleCleanup =
                        pendingIdleCleanup.isEmpty()
                            ? penDirty
                            : pendingIdleCleanup.united(penDirty);
                    ++penOverlay.submittedBatches;
                    if (penOverlay.submittedBatches <= 8 ||
                        penOverlay.submittedBatches % 50 == 0) {
                        writeLog(QStringLiteral(
                                     "native pen batch=%1 reports=%2 "
                                     "dirty=%3,%4 %5x%6 submit_ms=%7")
                                     .arg(penOverlay.submittedBatches)
                                     .arg(receivedPenReports)
                                     .arg(penDirty.x())
                                     .arg(penDirty.y())
                                     .arg(penDirty.width())
                                     .arg(penDirty.height())
                                     .arg(std::chrono::duration_cast<
                                              std::chrono::milliseconds>(
                                              penSubmitFinished -
                                              penSubmitStarted)
                                              .count()));
                    }
                }
            } else {
                penOverlay.drawing = false;
            }
            QCoreApplication::processEvents();
            if (!manualRefresh)
                continue;
        }
        if (!frame && !manualRefresh &&
            (!pendingIdleCleanup.isEmpty() ||
             !pendingEraserCleanup.isEmpty())) {
            const auto cleanupNow =
                std::chrono::steady_clock::now();
            const bool eraserCleanup = !pendingEraserCleanup.isEmpty();
            const int cleanupDelay = eraserCleanup
                ? NativeEraserCleanupDelayMs
                : NativePenCleanupDelayMs;
            if (penInRange ||
                cleanupNow - lastPenActivity <
                std::chrono::milliseconds(cleanupDelay)) {
                continue;
            }
            /*
             * Close the small race between the loop snapshot above and the
             * panel submission.  BTN_TOOL_PEN normally arrives before
             * BTN_TOUCH, so a newly approaching Marker cancels cleanup before
             * a visible stroke can be queued behind it.
             */
            {
                const std::lock_guard<std::mutex> lock(mailbox.mutex);
                if (mailbox.penInRange || !mailbox.penSamples.empty())
                    continue;
            }
            const QRect cleanup = pendingIdleCleanup.isEmpty()
                ? pendingEraserCleanup
                : (pendingEraserCleanup.isEmpty()
                       ? pendingIdleCleanup
                       : pendingIdleCleanup.united(
                             pendingEraserCleanup));
            pendingIdleCleanup = QRect();
            pendingEraserCleanup = QRect();
            const auto cleanupStarted = std::chrono::steady_clock::now();
            framebuffer->swapBuffers(
                cleanup,
                EPContentType::Monochrome,
                EPScreenMode::Mono,
                QFlags<EPFramebuffer::UpdateFlag>(
                    EPFramebuffer::UpdateFlag::UIUpdate));
            const auto cleanupFinished = std::chrono::steady_clock::now();
            const auto cleanupMilliseconds =
                std::chrono::duration_cast<std::chrono::milliseconds>(
                    cleanupFinished - cleanupStarted).count();
            writeLog(QStringLiteral(
                         "idle monochrome cleanup dirty=%1,%2 %3x%4 "
                         "submit_ms=%5 eraser=%6")
                         .arg(cleanup.x())
                         .arg(cleanup.y())
                         .arg(cleanup.width())
                         .arg(cleanup.height())
                         .arg(cleanupMilliseconds)
                         .arg(eraserCleanup));
            QCoreApplication::processEvents();
            continue;
        }
        if (!frame && !manualRefresh)
            continue;

        /*
         * Native ink owns the panel while the pen is down and for a short
         * tail after lift. Drop intermediate Android frames during that
         * window; the HWC receiver retains the newest one and the native
         * overlay already contains the complete stroke.
         */
        if (frame && !manualRefresh) {
            auto frameNow = std::chrono::steady_clock::now();
            if (penInRange || frameNow - lastPenActivity <
                std::chrono::milliseconds(
                    NativePenPriorityTailMs)) {
                deferredAndroidFrame = frame;
                QCoreApplication::processEvents();
                continue;
            }
            deferredAndroidFrame.reset();

            /* Do not inherit the slower idle deadline on finger-down. */
            if (interactiveFastPath &&
                nextAndroidSubmit - frameNow >
                    std::chrono::milliseconds(TouchFrameIntervalMs)) {
                nextAndroidSubmit = frameNow;
            }

            /*
             * swapBuffers is asynchronous on this panel. Without pacing,
             * SurfaceFlinger can enqueue far more updates than E-ink can
             * display. Cap Android animation traffic at about 33 fps and
             * always replace a waiting frame with the newest HWC buffer.
             */
            if (frameNow < nextAndroidSubmit) {
                std::unique_lock<std::mutex> lock(mailbox.mutex);
                mailbox.changed.wait_until(
                    lock,
                    nextAndroidSubmit,
                    [&mailbox]() {
                        return stopRequested ||
                               !mailbox.penSamples.empty() ||
                               mailbox.receiverEnded;
                    });
                if (stopRequested)
                    break;
                if (!mailbox.penSamples.empty()) {
                    QCoreApplication::processEvents();
                    continue;
                }
                receivedFrames = mailbox.receivedFrames;
                if (receivedFrames != consumedFrames) {
                    frame = mailbox.latest;
                    consumedFrames = receivedFrames;
                }
            }
        }

        QRect dirty = destination.rect();
        std::vector<QRect> dirtyRectangles = {destination.rect()};
        if (frame) {
            const DisplayProfile selectedProfile = readDisplayProfile();
            /*
             * The touch fast tail converts frames with 1-bit ordered
             * dithering. A reader answers a tap with a full page of text,
             * which would first appear dithered and only settle into gray
             * levels 520 ms later; while a reader is in the foreground keep
             * the selected conversion so the page arrives clean once.
             */
            const DisplayProfile requestedProfile =
                (interactiveFastPath && !readReaderActive())
                    ? DisplayProfile::Fast : selectedProfile;
            const bool requestedInvert = readInvertMode();
            const bool noteActive = readNoteActive();
            if (noteActive != penOverlay.noteActive ||
                requestedInvert != penOverlay.inverted) {
                penOverlay.clear();
                penOverlay.noteActive = noteActive;
                penOverlay.inverted = requestedInvert;
                writeLog(QStringLiteral(
                             "native pen overlay active=%1 invert=%2")
                             .arg(noteActive)
                             .arg(requestedInvert));
            }
            if (!haveControlState ||
                requestedProfile != activeProfile ||
                requestedInvert != activeInvert) {
                activeProfile = requestedProfile;
                activeInvert = requestedInvert;
                haveControlState = true;
                writeLog(QStringLiteral("display controls profile=%1 invert=%2")
                             .arg(displayProfileName(activeProfile))
                             .arg(activeInvert));
            }
            const auto *androidPixels =
                reinterpret_cast<const unsigned char *>(
                frame->constData());
            const QRect rgbDirty = changedRectangle(
                androidPixels, latestRgb, !haveLatestRgb);
            latestRgb = *frame;
            haveLatestRgb = true;
            if (activeColorMode != ColorMode::Mono &&
                !rgbDirty.isEmpty()) {
                pendingColorSettle =
                    pendingColorSettle.isEmpty()
                        ? rgbDirty
                        : pendingColorSettle.united(rgbDirty);
                const auto rgbActivity =
                    std::chrono::steady_clock::now();
                lastContentActivity =
                    isOneShotColorMode(activeColorMode)
                        ? rgbActivity -
                              std::chrono::milliseconds(
                                  ColorSettleDelayMs)
                        : rgbActivity;
            }
            if (manualRefresh &&
                activeColorMode != ColorMode::Mono) {
                pendingColorSettle = destination.rect();
                lastContentActivity =
                    std::chrono::steady_clock::now();
            }
            convertForEink(
                androidPixels, converted, activeInvert, activeProfile);
            const auto *pixels =
                reinterpret_cast<const unsigned char *>(
                    converted.constData());
            dirtyRectangles = changedRectangles(
                pixels, previous, !havePrevious);
            dirty = rectanglesBoundingRect(dirtyRectangles);
            const QRect navigationRect(
                0, AndroidHeight - NavigationBarHeight,
                AndroidWidth, NavigationBarHeight);
            const bool navigationChanged =
                std::any_of(
                    dirtyRectangles.cbegin(), dirtyRectangles.cend(),
                    [&navigationRect](const QRect &damage) {
                        return damage.intersects(navigationRect);
                    });
            if (navigationChanged) {
                /*
                 * Navigation icons are a small diff, but they are ordinary
                 * SystemUI rather than handwriting.  Refresh the complete
                 * white bar with the stable monochrome waveform so its dark
                 * Back/Home/Refresh glyphs do not wait behind Pen mode.
                 */
                addDamageRectangle(dirtyRectangles, navigationRect);
                dirty = rectanglesBoundingRect(dirtyRectangles);
            }
            if (dirty.isEmpty() && !manualRefresh &&
                !toolbarRefreshPending)
                continue;
            if (toolbarRefreshPending) {
                const QRect toolbarRect(
                    0, 0, toolbarRefreshRight, toolbarRefreshBottom);
                addDamageRectangle(dirtyRectangles, toolbarRect);
                dirty = rectanglesBoundingRect(dirtyRectangles);
                toolbarRefreshPending = false;
                toolbarRefreshRight = AndroidWidth;
                toolbarRefreshBottom = ToolbarHeight;
            }
            if (!dirty.isEmpty()) {
                QImage source(pixels,
                              AndroidWidth,
                              AndroidHeight,
                              AndroidStrideBytes,
                              QImage::Format_RGBX8888);
                QPainter painter(&destination);
                painter.setCompositionMode(
                    QPainter::CompositionMode_Source);
                if (!havePrevious)
                    painter.fillRect(destination.rect(), Qt::black);
                for (const QRect &damage : dirtyRectangles)
                    painter.drawImage(damage.topLeft(), source, damage);
                painter.end();
                const bool colorRegionOverwritten =
                    !lastColorRect.isEmpty() &&
                    std::any_of(
                        dirtyRectangles.cbegin(), dirtyRectangles.cend(),
                        [&lastColorRect](const QRect &damage) {
                            return damage.intersects(lastColorRect);
                        });
                if (colorRegionOverwritten) {
                    /*
                     * The fast path has repainted at least part of the last
                     * Gallery 3 region in monochrome. Its old RGB digest can
                     * no longer suppress a later settle, even when Android's
                     * source pixels themselves are unchanged.
                     */
                    lastColorDigest.clear();
                    lastColorRect = QRect();
                }
                for (const QRect &damage : dirtyRectangles)
                    composeNativePenOverlay(
                        penOverlay, destination, damage);
            } else {
                dirty = destination.rect();
                dirtyRectangles = {dirty};
            }
            std::memcpy(previous.data(), pixels, VisibleBytes);
            havePrevious = true;
        }
        ++displayedFrames;

        /*
         * Pen mode is used for the small rectangles produced by handwriting.
         * Larger app transitions stay on the monochrome waveform instead of
         * the flashing color/content path. Only a deliberate refresh or the
         * first frame receives a blocking full Content pass. Frame-count
         * based full refreshes used to land in the middle of handwriting and
         * stall both pen and UI for more than a second.
         */
        qint64 dirtyArea = 0;
        for (const QRect &damage : dirtyRectangles) {
            dirtyArea +=
                static_cast<qint64>(damage.width()) * damage.height();
        }
        const bool fullUpdate =
            manualRefresh || displayedFrames == 1;
        int penRegionCount = 0;
        const auto submitStarted = std::chrono::steady_clock::now();
        const bool readerActive = readReaderActive();
        /*
         * A page turn is the large repaint that follows the reader's tap, so
         * it usually lands inside the touch fast tail; only the later
         * interaction settle repaint and full refreshes are excluded.
         */
        const bool pageTurnFrame =
            !fullUpdate && !readerCleanupFrame && !interactiveSettleFrame &&
            !penOverlay.noteActive &&
            dirtyArea * 100 >= PanelArea * ReaderPageAreaPercent;
        if (pageTurnFrame && readerActive &&
            readerPagesPerCleanup(readerPolicy) > 0 &&
            submitStarted - lastReaderPageTurn >=
                std::chrono::milliseconds(ReaderPageMinimumGapMs)) {
            lastReaderPageTurn = submitStarted;
            ++readerPagesSinceCleanup;
            if (readerPagesSinceCleanup >=
                readerPagesPerCleanup(readerPolicy)) {
                readerPendingCleanup = readerPendingCleanup.isEmpty()
                    ? dirty
                    : readerPendingCleanup.united(dirty);
            }
            writeLog(QStringLiteral(
                         "reader page turn pages=%1/%2 area=%3 profile=%4")
                         .arg(readerPagesSinceCleanup)
                         .arg(readerPagesPerCleanup(readerPolicy))
                         .arg(dirtyArea)
                         .arg(displayProfileName(activeProfile)));
        }
        if (fullUpdate) {
            framebuffer->swapBuffers(
                destination.rect(),
                EPContentType::Monochrome,
                EPScreenMode::Content,
                QFlags<EPFramebuffer::UpdateFlag>(
                    EPFramebuffer::UpdateFlag::UIUpdate |
                    EPFramebuffer::UpdateFlag::FullUpdate));
        } else {
            const QRect toolbarRect(0, 0, AndroidWidth, ToolbarHeight);
            const QRect navigationRect(
                0, AndroidHeight - NavigationBarHeight,
                AndroidWidth, NavigationBarHeight);
            for (const QRect &damage : dirtyRectangles) {
                const qint64 regionArea =
                    static_cast<qint64>(damage.width()) * damage.height();
                const bool stableUiRegion =
                    damage.intersects(toolbarRect) ||
                    damage.intersects(navigationRect);
                const bool penUpdate =
                    !stableUiRegion && regionArea * 25 <= PanelArea;
                if (penUpdate)
                    ++penRegionCount;
                QFlags<EPFramebuffer::UpdateFlag> updateFlags(
                    EPFramebuffer::UpdateFlag::UIUpdate);
                EPScreenMode partialMode = EPScreenMode::Animate;
                if (penUpdate) {
                    /*
                     * Preserve the proven Acep2 small-region path. This uses
                     * the stable QRect overload; the old QRegion experiment
                     * crashed natively and is intentionally not restored.
                     */
                    updateFlags = QFlags<EPFramebuffer::UpdateFlag>(
                        EPFramebuffer::UpdateFlag::PenUpdate);
                    partialMode = EPScreenMode::Pen;
                } else if (stableUiRegion) {
                    partialMode = EPScreenMode::Mono;
                } else if (readerCleanupFrame ||
                           (pageTurnFrame && readerActive)) {
                    /*
                     * A reader page (or its scheduled cleanup) is static
                     * content: use the stable grayscale waveform even in the
                     * Fast profile instead of the animation path that leaves
                     * ghosting behind.
                     */
                    partialMode = EPScreenMode::Grayscale;
                } else if (activeProfile == DisplayProfile::Quality) {
                    partialMode = EPScreenMode::Grayscale;
                } else if (activeProfile == DisplayProfile::Fast) {
                    updateFlags |=
                        EPFramebuffer::UpdateFlag::AnimationUpdate;
                }
                framebuffer->swapBuffers(
                    damage,
                    EPContentType::Monochrome,
                    partialMode,
                    updateFlags);
            }
        }
        /*
         * All partial Android frames stay asynchronous so HWC and native pen
         * input cannot be serialized behind the slow physical waveform. The
         * native pen path above provides immediate ink independently. A full
         * refresh is the only operation that must wait for panel completion.
         */
        const bool waitForPanel = fullUpdate;
        if (waitForPanel)
            framebuffer->sync();
        if (fullUpdate) {
            pendingIdleCleanup = QRect();
            pendingEraserCleanup = QRect();
            accumulatedGhostArea = 0;
        } else if (!pendingIdleCleanup.isEmpty() &&
                   dirty.contains(pendingIdleCleanup)) {
            /*
             * This Android frame has already repainted the native stroke
             * area with a stable waveform, so a separate cleanup would only
             * add another queued update.
             */
            pendingIdleCleanup = pendingEraserCleanup;
        }
        const auto submitFinished = std::chrono::steady_clock::now();
        lastPanelActivity = submitFinished;
        if (readerCleanupFrame) {
            /* The full refresh removed the ghosting of the counted pages. */
            readerCleanupFrame = false;
            readerPendingCleanup = QRect();
            readerPagesSinceCleanup = 0;
            accumulatedGhostArea = 0;
        } else if (!fullUpdate) {
            accumulatedGhostArea = std::min(
                PanelArea * GhostBudgetScreenMultiples * 2,
                accumulatedGhostArea + dirtyArea);
        }
        nextAndroidSubmit =
            submitFinished +
            std::chrono::milliseconds(
                interactiveFastPath
                    ? TouchFrameIntervalMs
                    : AndroidFrameIntervalMs);
        const auto submitMilliseconds =
            std::chrono::duration_cast<std::chrono::milliseconds>(
                submitFinished - submitStarted).count();
        QCoreApplication::processEvents();

        if (!ready) {
            ready = markDisplayReady();
            if (ready)
                ensureUsbPolicyRoute();
            writeLog(ready
                         ? QStringLiteral(
                               "SUCCESS first Android frame displayed")
                         : QStringLiteral(
                               "ERROR could not create readiness marker"));
            if (!ready) {
                displayFailure = true;
                break;
            }
        }
        if (displayedFrames <= 8 || displayedFrames % 20 == 0) {
            writeLog(QStringLiteral(
                          "coalesced frame received=%1 displayed=%2 "
                           "dirty=%3,%4 %5x%6 regions=%7 full=%8 manual=%9 "
                           "pen_regions=%10 profile=%11 sync=%12 "
                           "submit_ms=%13 auto_ghost=%14")
                         .arg(receivedFrames)
                         .arg(displayedFrames)
                         .arg(dirty.x())
                         .arg(dirty.y())
                          .arg(dirty.width())
                          .arg(dirty.height())
                          .arg(static_cast<qulonglong>(dirtyRectangles.size()))
                          .arg(fullUpdate)
                          .arg(manualRefresh)
                          .arg(penRegionCount)
                          .arg(displayProfileName(activeProfile))
                          .arg(waitForPanel)
                          .arg(submitMilliseconds)
                          .arg(automaticGhostRefresh));
        }
    }

    const bool requestedStop = stopRequested;
    stopRequested = 1;
    mailbox.changed.notify_all();
    receiver.join();
    penReceiver.join();
    uint64_t receivedAtExit;
    uint64_t penReportsAtExit;
    bool receiverEndedAtExit;
    {
        const std::lock_guard<std::mutex> lock(mailbox.mutex);
        receivedAtExit = mailbox.receivedFrames;
        penReportsAtExit = mailbox.receivedPenReports;
        receiverEndedAtExit = mailbox.receiverEnded;
    }
    ::unlink(ReadyPath);
    writeLog(QStringLiteral(
                 "decoupled bridge exiting after displayed=%1 received=%2 "
                 "pen_reports=%3")
                 .arg(displayedFrames)
                 .arg(receivedAtExit)
                 .arg(penReportsAtExit));
    return requestedStop || (!displayFailure && !receiverEndedAtExit)
            ? 0 : 14;
}

} // namespace

int main(int argc, char **argv)
{
    qputenv("LANG", QByteArrayLiteral("C.UTF-8"));
    qputenv("LC_ALL", QByteArrayLiteral("C.UTF-8"));
    qputenv("QT_QPA_PLATFORM", QByteArrayLiteral("offscreen"));
    qputenv("QT_QUICK_BACKEND", QByteArrayLiteral("software"));
    qputenv("XDG_RUNTIME_DIR", QByteArrayLiteral("/run/rm-epd-bridge"));
    ::mkdir("/run/rm-epd-bridge", 0700);
    ::chmod("/run/rm-epd-bridge", 0700);
    ::unlink(ReadyPath);

    QFile log(QString::fromLatin1(LogPath));
    if (!log.open(QIODevice::WriteOnly | QIODevice::Append)) {
        std::fprintf(stderr, "cannot open %s: %s\n",
                     LogPath, std::strerror(errno));
        return 10;
    }
    logFile = &log;
    qInstallMessageHandler(qtMessageHandler);
    ::signal(SIGINT, requestStop);
    ::signal(SIGTERM, requestStop);
    ::signal(SIGPIPE, SIG_IGN);

    loadUiLocale();
    writeLog(QStringLiteral("rm-epd-bridge start; native HWC path, no VNC"));
    writeLog(QStringLiteral("UI locale=%1")
                 .arg(uiLanguageName()));
    ensureUsbTypeCNegotiation();
    QGuiApplication application(argc, argv);
    const int fontId = QFontDatabase::addApplicationFont(
        QString::fromLatin1(KoreanFontPath));
    if (fontId >= 0) {
        const QStringList families =
            QFontDatabase::applicationFontFamilies(fontId);
        if (!families.isEmpty()) {
            uiFontFamily = families.constFirst();
            QFont applicationFont(uiFontFamily);
            QGuiApplication::setFont(applicationFont);
            writeLog(QStringLiteral("Korean UI font loaded family=%1")
                         .arg(uiFontFamily));
        }
    }
    if (uiFontFamily.isEmpty()) {
        writeLog(QStringLiteral("Korean UI font load failed path=%1")
                     .arg(QString::fromLatin1(KoreanFontPath)));
    }
    QCoreApplication::processEvents();

    EPFramebufferFusion *framebuffer = EPFramebuffer::instance();
    if (framebuffer == nullptr || framebuffer->frameBuffer.isNull()) {
        writeLog(QStringLiteral("ERROR EPFramebuffer unavailable"));
        return 11;
    }
    QImage &destination = framebuffer->frameBuffer;
    writeLog(QStringLiteral("EP framebuffer %1x%2 format=%3 stride=%4")
                 .arg(destination.width())
                 .arg(destination.height())
                 .arg(static_cast<int>(destination.format()))
                 .arg(destination.bytesPerLine()));
    if (destination.width() < AndroidWidth ||
        destination.height() < AndroidHeight) {
        writeLog(QStringLiteral("ERROR EP framebuffer is smaller than Android"));
        return 12;
    }

    /*
     * The Redroid HWC creates its socket before SurfaceFlinger has completed
     * the first display reconfiguration. A client accepted in that short
     * window is later dropped when HWC submits its first native handle.
     * The Move can take roughly twenty seconds to finish its first
     * SurfaceFlinger/launcher display transition.  Connecting before that
     * transition occasionally yields an accepted socket that is closed before
     * the first handle.  Wait beyond that reset window.
     */
    writeLog(QStringLiteral(
        "waiting 8 seconds for SurfaceFlinger display stabilization"));
    for (int second = 0; second < 8 && !stopRequested; ++second)
        ::sleep(1);

    return runDecoupledDisplay(framebuffer, destination);

    int socketFd = connectToComposer();
    if (socketFd < 0) {
        writeLog(QStringLiteral("ERROR Android HWC socket did not appear"));
        return 13;
    }

    QByteArray previous;
    previous.resize(static_cast<qsizetype>(VisibleBytes));
    bool havePrevious = false;
    bool ready = false;
    uint64_t receivedFrames = 0;
    uint64_t displayedFrames = 0;

    while (!stopRequested) {
        std::array<unsigned char, NativeHandleBytes> handle = {};
        if (!readExact(socketFd, handle.data(), handle.size())) {
            writeLog(QStringLiteral("ERROR HWC handle stream ended: %1")
                         .arg(QString::fromLocal8Bit(std::strerror(errno))));
            ::close(socketFd);
            socketFd = -1;
            if (stopRequested)
                break;
            writeLog(QStringLiteral(
                "retrying HWC connection after stream reset"));
            ::sleep(2);
            socketFd = connectToComposer();
            if (socketFd < 0) {
                writeLog(QStringLiteral(
                    "ERROR Android HWC socket did not return"));
                break;
            }
            continue;
        }

        const uint32_t version = readLe32(handle, 0);
        const uint32_t numFds = readLe32(handle, 4);
        const uint32_t numInts = readLe32(handle, 8);
        const uint32_t magic = readLe32(handle, 16);
        const uint32_t flags = readLe32(handle, 20);
        const uint32_t bufferBytes = readLe32(handle, 24);
        const uint32_t bufferOffset = readLe32(handle, 28);
        if (version != NativeHandleVersion ||
            numFds != NativeHandleFds ||
            numInts != NativeHandleInts ||
            magic != RedroidHandleMagic ||
            flags != 0 ||
            bufferOffset != 0 ||
            bufferBytes < VisibleBytes ||
            bufferBytes > MaximumBufferBytes) {
            writeLog(QStringLiteral(
                         "ERROR invalid HWC handle version=%1 fds=%2 ints=%3 "
                         "magic=0x%4 flags=%5 size=%6 offset=%7")
                         .arg(version)
                         .arg(numFds)
                         .arg(numInts)
                         .arg(magic, 0, 16)
                         .arg(flags)
                         .arg(bufferBytes)
                         .arg(bufferOffset));
            break;
        }

        const int sharedFd = receiveSharedFd(socketFd);
        if (sharedFd < 0) {
            writeLog(QStringLiteral("ERROR did not receive HWC shared fd: %1")
                         .arg(QString::fromLocal8Bit(std::strerror(errno))));
            break;
        }
        void *mapping =
            ::mmap(nullptr, bufferBytes, PROT_READ, MAP_SHARED, sharedFd, 0);
        if (mapping == MAP_FAILED) {
            const int savedError = errno;
            ::close(sharedFd);
            writeLog(QStringLiteral("ERROR mmap HWC fd failed: %1")
                         .arg(QString::fromLocal8Bit(
                             std::strerror(savedError))));
            break;
        }

        ++receivedFrames;
        const auto *pixels = static_cast<const unsigned char *>(mapping);
        const QRect dirty = changedRectangle(pixels, previous, !havePrevious);
        if (!dirty.isEmpty()) {
            QImage source(pixels,
                          AndroidWidth,
                          AndroidHeight,
                          AndroidStrideBytes,
                          QImage::Format_RGBX8888);
            {
                QPainter painter(&destination);
                painter.setCompositionMode(QPainter::CompositionMode_Source);
                if (!havePrevious) {
                    painter.fillRect(destination.rect(), Qt::white);
                }
                painter.drawImage(dirty.topLeft(), source, dirty);
            }
            std::memcpy(previous.data(), pixels, VisibleBytes);
            havePrevious = true;

            /*
             * The converted pixels now live in the independent EP buffer, so
             * Android may safely reuse its ashmem buffer while the slow panel
             * waveform is submitted.
             */
            if (!sendAck(socketFd)) {
                ::munmap(mapping, bufferBytes);
                ::close(sharedFd);
                writeLog(QStringLiteral("ERROR HWC acknowledgement failed"));
                break;
            }
            ::munmap(mapping, bufferBytes);
            ::close(sharedFd);

            ++displayedFrames;
            const qint64 dirtyArea =
                static_cast<qint64>(dirty.width()) * dirty.height();
            const qint64 fullArea =
                static_cast<qint64>(AndroidWidth) * AndroidHeight;
            const bool periodicFull = displayedFrames % 30 == 0;
            const bool largeUpdate = dirtyArea * 10 >= fullArea * 6;
            const bool fullUpdate =
                displayedFrames == 1 || periodicFull;
            QFlags<EPFramebuffer::UpdateFlag> updateFlags(
                EPFramebuffer::UpdateFlag::UIUpdate);
            if (fullUpdate)
                updateFlags |= EPFramebuffer::UpdateFlag::FullUpdate;
            framebuffer->swapBuffers(
                fullUpdate || largeUpdate ? destination.rect() : dirty,
                EPContentType::Color,
                fullUpdate || largeUpdate
                    ? EPScreenMode::Content
                    : EPScreenMode::Animate,
                updateFlags);
            framebuffer->sync();
            QCoreApplication::processEvents();

            if (!ready) {
                ready = markDisplayReady();
                writeLog(ready
                             ? QStringLiteral(
                                   "SUCCESS first Android frame displayed")
                             : QStringLiteral(
                                   "ERROR could not create readiness marker"));
                if (!ready)
                    break;
            }
            if (displayedFrames <= 5 || displayedFrames % 25 == 0) {
                writeLog(QStringLiteral(
                             "frame received=%1 displayed=%2 dirty=%3,%4 "
                             "%5x%6 full=%7")
                             .arg(receivedFrames)
                             .arg(displayedFrames)
                             .arg(dirty.x())
                             .arg(dirty.y())
                             .arg(dirty.width())
                             .arg(dirty.height())
                             .arg(fullUpdate));
            }
        } else {
            if (!sendAck(socketFd)) {
                ::munmap(mapping, bufferBytes);
                ::close(sharedFd);
                writeLog(QStringLiteral(
                             "ERROR unchanged-frame acknowledgement failed"));
                break;
            }
            ::munmap(mapping, bufferBytes);
            ::close(sharedFd);
        }
    }

    if (socketFd >= 0)
        ::close(socketFd);
    ::unlink(ReadyPath);
    writeLog(QStringLiteral("rm-epd-bridge exiting after %1/%2 frames")
                 .arg(displayedFrames)
                 .arg(receivedFrames));
    return stopRequested ? 0 : 14;
}

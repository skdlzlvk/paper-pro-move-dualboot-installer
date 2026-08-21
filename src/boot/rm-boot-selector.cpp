#include <QCoreApplication>
#include <QDateTime>
#include <QFile>
#include <QFont>
#include <QFontDatabase>
#include <QGuiApplication>
#include <QImage>
#include <QPainter>

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>

#include "epframebuffer.h"

namespace {

constexpr char LogPath[] =
    "/android-data/boot-selector.log";
constexpr char TouchPath[] = "/dev/input/event3";
constexpr char KoreanFontPath[] =
    "/android/system/fonts/NotoSansCJK-Regular.ttc";
constexpr char UiLocalePath[] =
    "/android-data/data/com.android.launcher3/files/paper-ui-locale";
constexpr char LegacyUiLocalePath[] =
    "/android-data/paper-ui-locale";
constexpr int StockChoiceExitCode = 20;
constexpr int SelectionWindowSeconds = 5;

QFile *logFile = nullptr;
QString uiFontFamily;
enum class UiLanguage { English, Korean, SimplifiedChinese };
UiLanguage uiLanguage = UiLanguage::English;

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

void writeLog(const QString &message)
{
    const QString line =
        QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs) +
        QStringLiteral(" ") + message + QLatin1Char('\n');
    const QByteArray encoded = line.toUtf8();
    if (logFile != nullptr && logFile->isOpen()) {
        logFile->write(encoded);
        logFile->flush();
        ::fsync(logFile->handle());
    }
    ::write(STDERR_FILENO, encoded.constData(),
            static_cast<size_t>(encoded.size()));
}

void qtMessageHandler(QtMsgType type,
                      const QMessageLogContext &,
                      const QString &message)
{
    const char *level = "debug";
    if (type == QtInfoMsg)
        level = "info";
    else if (type == QtWarningMsg)
        level = "warning";
    else if (type == QtCriticalMsg)
        level = "critical";
    else if (type == QtFatalMsg)
        level = "fatal";
    writeLog(QStringLiteral("qt[%1] %2")
                 .arg(QString::fromLatin1(level), message));
}

struct MenuGeometry {
    QRect stock;
    QRect android;
};

MenuGeometry paintBootMenu(QImage &image)
{
    image.fill(Qt::white);
    QPainter painter(&image);
    painter.setRenderHint(QPainter::Antialiasing, false);
    painter.setPen(Qt::black);

    QFont titleFont = painter.font();
    if (!uiFontFamily.isEmpty())
        titleFont.setFamily(uiFontFamily);
    titleFont.setPixelSize(48);
    titleFont.setWeight(QFont::DemiBold);
    painter.setFont(titleFont);
    painter.drawText(
        QRect(48, 120, image.width() - 96, 90),
        Qt::AlignCenter,
        uiText("Choose an OS", "시작할 OS", "选择启动系统"));

    QFont bodyFont = painter.font();
    bodyFont.setPixelSize(29);
    bodyFont.setWeight(QFont::Normal);
    painter.setFont(bodyFont);
    painter.drawText(
        QRect(64, 215, image.width() - 128, 65),
        Qt::AlignCenter,
        uiText(
            "Android starts automatically in %1 seconds",
            "%1초 후 Android로 자동 시작합니다",
            "%1 秒后自动启动 Android")
            .arg(SelectionWindowSeconds));

    const int margin = 72;
    const int buttonHeight = 250;
    const int gap = 52;
    const int firstTop = image.height() / 2 - buttonHeight - gap / 2;
    MenuGeometry geometry{
        QRect(margin,
              firstTop + buttonHeight + gap,
              image.width() - margin * 2,
              buttonHeight),
        QRect(margin, firstTop, image.width() - margin * 2, buttonHeight),
    };

    QPen outline(Qt::black);
    outline.setWidth(3);
    painter.setPen(outline);
    painter.setBrush(Qt::white);
    painter.drawRoundedRect(geometry.stock, 6, 6);
    outline.setWidth(7);
    painter.setPen(outline);
    painter.drawRoundedRect(geometry.android, 6, 6);

    QFont optionFont = painter.font();
    optionFont.setPixelSize(42);
    optionFont.setWeight(QFont::DemiBold);
    painter.setFont(optionFont);
    painter.drawText(
        geometry.android.adjusted(20, 25, -20, -85),
        Qt::AlignCenter,
        QStringLiteral("Android OS"));
    painter.drawText(
        geometry.stock.adjusted(20, 25, -20, -85),
        Qt::AlignCenter,
        QStringLiteral("reMarkable OS"));

    QFont detailFont = painter.font();
    detailFont.setPixelSize(27);
    detailFont.setWeight(QFont::Normal);
    painter.setFont(detailFont);
    painter.drawText(
        geometry.android.adjusted(20, 125, -20, -25),
        Qt::AlignCenter,
        uiText(
            "Apps · notes · e-books",
            "기본 · 리디 · Android 앱",
            "应用 · 笔记 · 电子书"));
    painter.drawText(
        geometry.stock.adjusted(20, 125, -20, -25),
        Qt::AlignCenter,
        uiText(
            "Original writing environment · recovery",
            "순정 필기 환경 · 복구",
            "原厂书写环境 · 恢复"));

    painter.drawText(
        QRect(50, image.height() - 185, image.width() - 100, 90),
        Qt::AlignCenter,
        uiText(
            "Tap the screen to choose",
            "화면을 눌러 선택하세요",
            "点击屏幕进行选择"));
    painter.end();
    return geometry;
}

int scaledAxis(int value,
               const input_absinfo &axis,
               int extent)
{
    if (axis.maximum <= axis.minimum || extent <= 1)
        return -1;
    const long long numerator =
        static_cast<long long>(value - axis.minimum) * (extent - 1);
    return static_cast<int>(
        std::clamp(
            numerator / (axis.maximum - axis.minimum),
            0LL,
            static_cast<long long>(extent - 1)));
}

int waitForChoice(const MenuGeometry &geometry,
                  int imageWidth,
                  int imageHeight)
{
    int touchFd = -1;
    for (int attempt = 0; attempt < 20; ++attempt) {
        touchFd = ::open(TouchPath, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (touchFd >= 0)
            break;
        ::usleep(100000);
    }
    if (touchFd < 0) {
        writeLog(QStringLiteral(
                     "touch unavailable; defaulting to Android: %1")
                     .arg(QString::fromLocal8Bit(std::strerror(errno))));
        ::sleep(SelectionWindowSeconds);
        return 0;
    }

    input_absinfo axisX{};
    input_absinfo axisY{};
    if (::ioctl(touchFd, EVIOCGABS(ABS_MT_POSITION_X), &axisX) < 0 ||
        ::ioctl(touchFd, EVIOCGABS(ABS_MT_POSITION_Y), &axisY) < 0) {
        writeLog(QStringLiteral(
            "touch axis query failed; defaulting to Android"));
        ::close(touchFd);
        ::sleep(SelectionWindowSeconds);
        return 0;
    }
    const bool grabbed = ::ioctl(touchFd, EVIOCGRAB, 1) == 0;
    writeLog(QStringLiteral(
                 "touch ready x=%1..%2 y=%3..%4 grabbed=%5")
                 .arg(axisX.minimum)
                 .arg(axisX.maximum)
                 .arg(axisY.minimum)
                 .arg(axisY.maximum)
                 .arg(grabbed));

    int lastX = -1;
    int lastY = -1;
    int trackingId = -1;
    bool contactSeen = false;
    const auto deadline =
        std::chrono::steady_clock::now() +
        std::chrono::seconds(SelectionWindowSeconds);

    while (std::chrono::steady_clock::now() < deadline) {
        const auto remaining =
            std::chrono::duration_cast<std::chrono::milliseconds>(
                deadline - std::chrono::steady_clock::now());
        pollfd ready{
            .fd = touchFd,
            .events = POLLIN,
            .revents = 0,
        };
        const int timeout =
            static_cast<int>(std::clamp<long long>(
                remaining.count(), 1, 250));
        const int result = ::poll(&ready, 1, timeout);
        if (result < 0) {
            if (errno == EINTR)
                continue;
            break;
        }
        if (result == 0 || !(ready.revents & POLLIN))
            continue;

        input_event events[32];
        const ssize_t count =
            ::read(touchFd, events, sizeof(events));
        if (count <= 0 ||
            count % static_cast<ssize_t>(sizeof(input_event)) != 0)
            continue;
        const size_t eventCount =
            static_cast<size_t>(count) / sizeof(input_event);
        for (size_t index = 0; index < eventCount; ++index) {
            const input_event &event = events[index];
            if (event.type == EV_ABS &&
                event.code == ABS_MT_POSITION_X) {
                lastX = event.value;
            } else if (event.type == EV_ABS &&
                       event.code == ABS_MT_POSITION_Y) {
                lastY = event.value;
            } else if (event.type == EV_ABS &&
                       event.code == ABS_MT_TRACKING_ID) {
                trackingId = event.value;
                if (trackingId >= 0)
                    contactSeen = true;
            } else if (event.type == EV_SYN &&
                       event.code == SYN_REPORT &&
                       contactSeen && trackingId < 0 &&
                       lastX >= 0 && lastY >= 0) {
                const QPoint point(
                    scaledAxis(lastX, axisX, imageWidth),
                    scaledAxis(lastY, axisY, imageHeight));
                writeLog(QStringLiteral(
                             "touch release raw=%1,%2 screen=%3,%4")
                             .arg(lastX)
                             .arg(lastY)
                             .arg(point.x())
                             .arg(point.y()));
                if (geometry.android.contains(point)) {
                    if (grabbed)
                        ::ioctl(touchFd, EVIOCGRAB, 0);
                    ::close(touchFd);
                    return 0;
                }
                if (geometry.stock.contains(point)) {
                    if (grabbed)
                        ::ioctl(touchFd, EVIOCGRAB, 0);
                    ::close(touchFd);
                    return StockChoiceExitCode;
                }
                contactSeen = false;
            }
        }
    }

    if (grabbed)
        ::ioctl(touchFd, EVIOCGRAB, 0);
    ::close(touchFd);
    writeLog(QStringLiteral(
        "selection timeout; continuing Android"));
    return 0;
}

} // namespace

int main(int argc, char **argv)
{
    qputenv("LANG", QByteArrayLiteral("C.UTF-8"));
    qputenv("LC_ALL", QByteArrayLiteral("C.UTF-8"));
    qputenv("QT_QPA_PLATFORM", QByteArrayLiteral("offscreen"));
    qputenv("QT_QUICK_BACKEND", QByteArrayLiteral("software"));
    qputenv("XDG_RUNTIME_DIR",
            QByteArrayLiteral("/run/rm-boot-selector"));
    ::mkdir("/run/rm-boot-selector", 0700);
    ::chmod("/run/rm-boot-selector", 0700);

    QFile log(QString::fromLatin1(LogPath));
    if (log.open(QIODevice::WriteOnly | QIODevice::Append))
        logFile = &log;
    qInstallMessageHandler(qtMessageHandler);
    loadUiLocale();
    writeLog(QStringLiteral("boot selector start"));
    writeLog(QStringLiteral("UI locale=%1")
                 .arg(uiLanguageName()));

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
    if (framebuffer == nullptr) {
        writeLog(QStringLiteral(
            "EPFramebuffer unavailable; continuing stock OS"));
        return 0;
    }

    QImage &image = framebuffer->frameBuffer;
    if (image.isNull() || image.width() < 100 || image.height() < 100) {
        writeLog(QStringLiteral(
            "invalid framebuffer; continuing stock OS"));
        return 0;
    }
    const MenuGeometry geometry = paintBootMenu(image);
    QFlags<EPFramebuffer::UpdateFlag> flags(
        EPFramebuffer::UpdateFlag::FullUpdate);
    flags |= EPFramebuffer::UpdateFlag::UIUpdate;
    framebuffer->swapBuffers(
        image.rect(),
        EPContentType::Monochrome,
        EPScreenMode::Content,
        flags);
    framebuffer->sync();
    QCoreApplication::processEvents();
    writeLog(QStringLiteral("boot menu displayed width=%1 height=%2")
                 .arg(image.width())
                 .arg(image.height()));
    return waitForChoice(
        geometry,
        image.width(),
        image.height());
}

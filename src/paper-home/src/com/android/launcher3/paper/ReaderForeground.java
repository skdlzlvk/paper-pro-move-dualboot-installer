package com.android.launcher3.paper;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Tells the native display bridge whether a catalog reader application owns
 * the screen. The marker lets the bridge count page turns and schedule the
 * user's page-refresh policy only while a book is actually being read.
 *
 * Only the package identity of the foreground window is inspected; no window
 * title, document name or content is read or stored. The marker file contains
 * a constant.
 */
final class ReaderForeground {
    static final String MARKER_FILE = "paper-reader-active";
    private static Boolean marked;

    private ReaderForeground() {
    }

    static boolean isReaderPackage(CharSequence packageName) {
        if (packageName == null) {
            return false;
        }
        String name = packageName.toString();
        return RecommendedApps.PACKAGE_KINDLE.equals(name)
                || RecommendedApps.PACKAGE_LIBBY.equals(name)
                || RecommendedApps.PACKAGE_KOBO.equals(name)
                || RecommendedApps.PACKAGE_PLAY_BOOKS.equals(name)
                || RecommendedApps.PACKAGE_KOREADER.equals(name);
    }

    /**
     * Records whether {@code topPackage} is a reader. Idempotent: the marker
     * is touched only when the state changes, so callers may report the same
     * foreground package repeatedly without disk churn.
     */
    static synchronized void update(Context context, CharSequence topPackage,
            String tag) {
        boolean reader = isReaderPackage(topPackage);
        if (marked != null && marked == reader) {
            return;
        }
        File marker = new File(context.getFilesDir(), MARKER_FILE);
        if (reader) {
            try (FileOutputStream output = new FileOutputStream(marker, false)) {
                output.write("1\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (IOException error) {
                Log.w(tag, "could not mark reader foreground", error);
                return;
            }
        } else if (marker.exists() && !marker.delete()) {
            Log.w(tag, "could not clear reader foreground marker");
            return;
        }
        marked = reader;
        Log.i(tag, reader
                ? "reader app in foreground; page refresh policy active"
                : "reader app left foreground");
    }
}

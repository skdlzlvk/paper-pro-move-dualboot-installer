package com.android.launcher3.paper;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import com.android.launcher3.R;

import java.util.ArrayList;
import java.util.List;

/** Curated app identities only. Proprietary APKs are never bundled. */
public final class RecommendedApps {
    public static final String PACKAGE_KINDLE = "com.amazon.kindle";
    public static final String PACKAGE_LIBBY =
            "com.overdrive.mobile.android.libby";
    public static final String PACKAGE_KOBO = "com.kobobooks.android";
    public static final String PACKAGE_PLAY_BOOKS =
            "com.google.android.apps.books";
    public static final String PACKAGE_EVERNOTE = "com.evernote";
    public static final String PACKAGE_GOODNOTES =
            "com.goodnotes.android.app";
    public static final String PACKAGE_ONENOTE =
            "com.microsoft.office.onenote";
    public static final String PACKAGE_KOREADER = "org.koreader.launcher";

    public static final String KOREADER_RELEASE_URL =
            "https://github.com/koreader/koreader/releases/latest";

    private RecommendedApps() {
    }

    public static final class Entry {
        public final String packageName;
        public final String label;
        public final String detail;
        public final String mark;
        public final boolean openSource;
        public final boolean installed;
        public final Intent launchIntent;

        Entry(String packageName, String label, String detail, String mark,
              boolean openSource, boolean installed, Intent launchIntent) {
            this.packageName = packageName;
            this.label = label;
            this.detail = detail;
            this.mark = mark;
            this.openSource = openSource;
            this.installed = installed;
            this.launchIntent = launchIntent;
        }
    }

    public static List<Entry> query(Context context) {
        List<Entry> entries = new ArrayList<>();
        add(context, entries, PACKAGE_KINDLE, "Kindle",
                R.string.recommended_detail_kindle, "K", false);
        add(context, entries, PACKAGE_LIBBY, "Libby",
                R.string.recommended_detail_libby, "L", false);
        add(context, entries, PACKAGE_KOBO, "Kobo Books",
                R.string.recommended_detail_kobo, "K", false);
        add(context, entries, PACKAGE_PLAY_BOOKS, "Google Play Books",
                R.string.recommended_detail_play_books, "G", false);
        add(context, entries, PACKAGE_EVERNOTE, "Evernote",
                R.string.recommended_detail_evernote, "E", false);
        add(context, entries, PACKAGE_GOODNOTES, "Goodnotes",
                R.string.recommended_detail_goodnotes, "G", false);
        add(context, entries, PACKAGE_ONENOTE, "Microsoft OneNote",
                R.string.recommended_detail_onenote, "N", false);
        add(context, entries, PACKAGE_KOREADER, "KOReader",
                R.string.recommended_detail_koreader, "KO", true);
        return entries;
    }

    private static void add(Context context, List<Entry> entries,
                            String packageName, String label,
                            int detailResource, String mark,
                            boolean openSource) {
        PackageManager manager = context.getPackageManager();
        Intent launch = manager.getLaunchIntentForPackage(packageName);
        boolean installed = launch != null;
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        }
        entries.add(new Entry(packageName, label,
                context.getString(detailResource), mark, openSource,
                installed, launch));
    }
}

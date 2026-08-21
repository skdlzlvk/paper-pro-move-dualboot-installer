package com.android.launcher3.paper;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.android.launcher3.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** E-ink-friendly discovery of user-facing applications. */
public final class InstalledApps {
    public static final String PACKAGE_MILLIE =
            "kr.co.millie.millieshelf";
    public static final String PACKAGE_RIDI = "com.initialcoms.ridi";
    public static final String PACKAGE_PLAY_STORE = "com.android.vending";
    public static final String PACKAGE_AURORA = "com.aurora.store";
    public static final String PACKAGE_KINDLE = "com.amazon.kindle";
    public static final String PACKAGE_LIBBY =
            "com.overdrive.mobile.android.libby";
    public static final String PACKAGE_KOBO = "com.kobobooks.android";
    public static final String PACKAGE_EVERNOTE = "com.evernote";
    public static final String PACKAGE_ONENOTE =
            "com.microsoft.office.onenote";

    private InstalledApps() {
    }

    public static final class AppEntry {
        public final String packageName;
        public final String label;
        public final String detail;
        public final String mark;
        public final Intent launchIntent;

        AppEntry(String packageName, String label, String detail,
                 String mark, Intent launchIntent) {
            this.packageName = packageName;
            this.label = label;
            this.detail = detail;
            this.mark = mark;
            this.launchIntent = launchIntent;
        }
    }

    public static List<AppEntry> query(Context context, boolean includeRidi) {
        PackageManager manager = context.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = manager.queryIntentActivities(query, 0);
        List<AppEntry> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null ||
                    info.activityInfo.applicationInfo == null) {
                continue;
            }
            ApplicationInfo application = info.activityInfo.applicationInfo;
            String packageName = application.packageName;
            if (packageName == null || !application.enabled ||
                    !info.activityInfo.enabled || seen.contains(packageName) ||
                    packageName.equals(context.getPackageName())) {
                continue;
            }
            boolean userInstalled =
                    (application.flags & ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    (application.flags &
                            ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            if (!userInstalled &&
                    !PACKAGE_PLAY_STORE.equals(packageName)) {
                continue;
            }
            if (!includeRidi && PACKAGE_RIDI.equals(packageName)) {
                continue;
            }

            CharSequence loadedLabel = info.loadLabel(manager);
            String label = loadedLabel == null ||
                    loadedLabel.toString().trim().isEmpty()
                    ? packageName : loadedLabel.toString().trim();
            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(Intent.CATEGORY_LAUNCHER);
            launch.setComponent(new ComponentName(
                    packageName, info.activityInfo.name));
            /*
             * Paper Home is the device's HOME activity. External launcher
             * targets must leave the home task, just like Launcher3's normal
             * startActivitySafely() path does. Without NEW_TASK, apps such as
             * Naver Series start their process but their activity remains
             * behind the home task.
             */
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED |
                    Intent.FLAG_ACTIVITY_NO_ANIMATION);
            apps.add(new AppEntry(
                    packageName, label, detailFor(context, packageName),
                    markFor(context, packageName, label), launch));
            seen.add(packageName);
        }

        Locale locale = PaperLocale.current(context);
        Collections.sort(apps, (left, right) -> {
            int priority = Integer.compare(
                    priorityFor(context, left.packageName),
                    priorityFor(context, right.packageName));
            if (priority != 0) {
                return priority;
            }
            return left.label.toLowerCase(locale).compareTo(
                    right.label.toLowerCase(locale));
        });
        return apps;
    }

    private static int priorityFor(Context context, String packageName) {
        if (PaperLocale.isKorean(context)) {
            if (PACKAGE_MILLIE.equals(packageName)) {
                return 0;
            }
            if (PACKAGE_RIDI.equals(packageName)) {
                return 1;
            }
            if (PACKAGE_EVERNOTE.equals(packageName)
                    || PACKAGE_ONENOTE.equals(packageName)) {
                return 2;
            }
            if (PACKAGE_KINDLE.equals(packageName)
                    || PACKAGE_LIBBY.equals(packageName)
                    || PACKAGE_KOBO.equals(packageName)) {
                return 3;
            }
            if (PACKAGE_PLAY_STORE.equals(packageName)) {
                return 8;
            }
            if (PACKAGE_AURORA.equals(packageName)) {
                return 9;
            }
            return 10;
        }
        if (PACKAGE_KINDLE.equals(packageName)) {
            return 0;
        }
        if (PACKAGE_LIBBY.equals(packageName)) {
            return 1;
        }
        if (PACKAGE_KOBO.equals(packageName)) {
            return 2;
        }
        if (PACKAGE_EVERNOTE.equals(packageName)) {
            return 3;
        }
        if (PACKAGE_ONENOTE.equals(packageName)) {
            return 4;
        }
        if (PACKAGE_PLAY_STORE.equals(packageName)) {
            return 8;
        }
        if (PACKAGE_AURORA.equals(packageName)) {
            return 9;
        }
        return 10;
    }

    private static String detailFor(Context context, String packageName) {
        if (PACKAGE_MILLIE.equals(packageName)) {
            return context.getString(R.string.app_detail_millie);
        }
        if (PACKAGE_RIDI.equals(packageName)) {
            return context.getString(R.string.app_detail_ridi);
        }
        if (PACKAGE_PLAY_STORE.equals(packageName)) {
            return context.getString(R.string.app_detail_play_store);
        }
        if (PACKAGE_AURORA.equals(packageName)) {
            return context.getString(R.string.app_detail_aurora);
        }
        return context.getString(R.string.app_detail_installed);
    }

    private static String markFor(Context context, String packageName,
                                  String label) {
        if (PACKAGE_MILLIE.equals(packageName)) {
            return context.getString(R.string.app_mark_millie);
        }
        if (PACKAGE_RIDI.equals(packageName)) {
            return context.getString(R.string.app_mark_ridi);
        }
        if (PACKAGE_PLAY_STORE.equals(packageName)) {
            return "P";
        }
        if (PACKAGE_AURORA.equals(packageName)) {
            return "A";
        }
        if (label.isEmpty()) {
            return context.getString(R.string.app_mark_default);
        }
        int end = label.offsetByCodePoints(0, 1);
        return label.substring(0, end);
    }
}

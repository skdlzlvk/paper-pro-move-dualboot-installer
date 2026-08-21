package com.android.launcher3.paper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.LocaleList;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;

/** Applies the installer-selected locale through Android's persistent config. */
public final class LocaleSeedReceiver extends BroadcastReceiver {
    public static final String ACTION_SET_INITIAL_LOCALE =
            "com.android.launcher3.paper.action.SET_INITIAL_LOCALE";
    private static final String EXTRA_LOCALE = "locale";
    private static final String TAG = "PaperLocaleSeed";

    @Override
    public void onReceive(Context context, Intent intent) {
        setResultCode(Activity.RESULT_CANCELED);
        if (intent == null || !ACTION_SET_INITIAL_LOCALE.equals(intent.getAction())) {
            return;
        }

        String languageTag = intent.getStringExtra(EXTRA_LOCALE);
        if (!isSupported(languageTag)) {
            Log.e(TAG, "Rejected unsupported locale seed: " + languageTag);
            return;
        }

        try {
            Locale locale = Locale.forLanguageTag(languageTag);
            Class<?> localePicker = Class.forName("com.android.internal.app.LocalePicker");
            Method updateLocales = localePicker.getDeclaredMethod(
                    "updateLocales", LocaleList.class);
            updateLocales.setAccessible(true);
            updateLocales.invoke(null, new LocaleList(locale));
            setResultCode(Activity.RESULT_OK);
            setResultData(languageTag);
            Log.i(TAG, "Applied persistent Android locale: " + languageTag);
        } catch (ClassNotFoundException | NoSuchMethodException |
                IllegalAccessException | InvocationTargetException exception) {
            Log.e(TAG, "Unable to apply persistent Android locale", exception);
        }
    }

    private static boolean isSupported(String languageTag) {
        return "en-US".equals(languageTag)
                || "ko-KR".equals(languageTag)
                || "zh-CN".equals(languageTag);
    }
}

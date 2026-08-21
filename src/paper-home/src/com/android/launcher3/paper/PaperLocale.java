package com.android.launcher3.paper;

import android.content.Context;
import android.os.LocaleList;

import java.util.Locale;

/** Locale policy shared by Paper Home and its built-in tools. */
public final class PaperLocale {
    private PaperLocale() {
    }

    public static Locale current(Context context) {
        LocaleList locales =
                context.getResources().getConfiguration().getLocales();
        if (locales == null || locales.isEmpty()) {
            return Locale.ENGLISH;
        }
        return locales.get(0);
    }

    public static boolean isKorean(Context context) {
        return Locale.KOREAN.getLanguage().equals(
                current(context).getLanguage());
    }

    public static boolean isSimplifiedChinese(Context context) {
        Locale locale = current(context);
        if (!Locale.CHINESE.getLanguage().equals(locale.getLanguage())) {
            return false;
        }
        String script = locale.getScript();
        String country = locale.getCountry();
        return "Hans".equalsIgnoreCase(script)
                || "CN".equalsIgnoreCase(country)
                || "SG".equalsIgnoreCase(country)
                || (script.isEmpty() && country.isEmpty());
    }

    public static String nativeLanguageTag(Context context) {
        if (isKorean(context)) {
            return "ko-KR";
        }
        if (isSimplifiedChinese(context)) {
            return "zh-CN";
        }
        return "en-US";
    }
}

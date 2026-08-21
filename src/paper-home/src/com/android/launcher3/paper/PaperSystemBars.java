package com.android.launcher3.paper;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;

/** Keeps the e-ink canvas above Paper Home's real system navigation bar. */
public final class PaperSystemBars {
    private PaperSystemBars() {}

    /**
     * Paper screens always use a white navigation background. Explicitly ask
     * SystemUI for dark navigation icons as well; setting only the background
     * color leaves Android free to reuse the previous app's light icon mode,
     * which makes Back/Home/Refresh white-on-white and apparently missing.
     */
    public static void applyEinkSystemBarContrast(Activity activity) {
        activity.getWindow().setNavigationBarColor(
                android.graphics.Color.WHITE);
        View decor = activity.getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else if (Build.VERSION.SDK_INT >= 26) {
            decor.setSystemUiVisibility(
                    decor.getSystemUiVisibility()
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    public static void setContent(Activity activity, View content) {
        applyEinkSystemBarContrast(activity);
        final int minimumNavigationBottom = Math.round(
                48f * activity.getResources().getDisplayMetrics().density);
        FrameLayout root = new FrameLayout(activity);
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        contentParams.bottomMargin = minimumNavigationBottom;
        root.addView(content, contentParams);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int navigationBottom;
            if (Build.VERSION.SDK_INT >= 30) {
                navigationBottom = insets.getInsets(
                        WindowInsets.Type.navigationBars()).bottom;
            } else {
                navigationBottom = insets.getSystemWindowInsetBottom();
            }
            navigationBottom = Math.max(
                    navigationBottom, minimumNavigationBottom);
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) content.getLayoutParams();
            if (params.bottomMargin != navigationBottom) {
                params.bottomMargin = navigationBottom;
                content.setLayoutParams(params);
            }
            return insets;
        });
        activity.setContentView(root);
        root.post(root::requestApplyInsets);
    }
}

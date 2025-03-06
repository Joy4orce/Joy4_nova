// Copyright 2017 Archos SA
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.archos.mediacenter.video.utils;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.app.UiModeManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import static android.content.Context.UI_MODE_SERVICE;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.player.PlayerActivity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Set;

/**
 * Created by alexandre on 02/06/17.
 */

public class MiscUtils {

    private static final Logger log = LoggerFactory.getLogger(MiscUtils.class);

    public static boolean hasCutout = false;

    private static final Handler handler = new Handler();
    private static final int DELAY_MILLIS_NORMAL = 250; // Delay for applying relayout
    // AUTODIM_TIMEOUT_MS = 2250 in https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/SystemUI/src/com/android/systemui/navigationbar/views/NavigationBar.java
    private static final int DELAY_MILLIS_GESTURE_NAVIGATION = 2300; // Delay for applying relayout

    private static Runnable relayoutRunnable;

    public static boolean isGooglePlayServicesAvailable(Context context){
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo("com.google.android.gms", 0);
            return packageInfo!=null;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static boolean isOnTV(Context context) {
        return(context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK));
    }

    public static boolean isAndroidTV(Context context) {
        UiModeManager uiModeManager = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        // Check if the UiModeManager object is null
        if (uiModeManager != null) {
            return (uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION);
        } else {
            // UiModeManager is not available on this device
            return false;
        }
    }
    
    public static boolean isEmulator() {
        return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.PRODUCT.contains("sdk_google")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("sdk_x86")
                || Build.PRODUCT.contains("vbox86p")
                || Build.PRODUCT.contains("emulator")
                || Build.PRODUCT.contains("simulator");
    }

    public static void dumpBundle(Bundle bundle, String TAG, Boolean isDebug) {
        if (isDebug) {
            if (bundle != null) {
                Set<String> keys = bundle.keySet();
                Iterator<String> it = keys.iterator();
                log.info(TAG + " bundle dump start");
                while (it.hasNext()) {
                    String key = it.next();
                    log.info(TAG + " [" + key + "=" + bundle.get(key) + "]");
                }
                log.info(TAG + " bundle dump stop");
            }
        }
    }

    public static int dp2Px(int dp) {
        return (int) (dp * Resources.getSystem().getDisplayMetrics().density);
    }

    public static float px2Dp(float px) {
        return px / Resources.getSystem().getDisplayMetrics().density;
    }

    // retrieve activity from context
    public static Activity getActivityFromContext(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    public static int getNavigationBarHeight(Context context) {
        int navigationBarHeight = 0;
        Resources resources = context.getResources();
        int resourceIdNavBarHeight = resources.getIdentifier("navigation_bar_height", "dimen", "android");
        // check if navigation bar is displayed because chromeos reports a navigation_bar_height of 84 but there is none displayed
        if (resourceIdNavBarHeight > 0 && hasNavigationBar(resources))
            navigationBarHeight = resources.getDimensionPixelSize(resourceIdNavBarHeight);
        log.debug("getNavigationBarHeight: navigationBarHeight=" + navigationBarHeight);
        return navigationBarHeight;
    }

    public static boolean isGestureAreaDisplayed(Context context) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null) {
                WindowMetrics windowMetrics = windowManager.getCurrentWindowMetrics();
                Insets insets = windowMetrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemGestures());
                return insets.bottom > 0;
            }
        }
        return false;
    }

    public static int getGestureAreaHeight(Context context) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null) {
                WindowMetrics windowMetrics = windowManager.getCurrentWindowMetrics();
                Insets insets = windowMetrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemGestures());
                return insets.bottom;
            }
        }
        return 0;
    }

    public static int getStatusBarHeight(Context context) {
        int result = 0;
        int resourceId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0)
            result = context.getResources().getDimensionPixelSize(resourceId);
        return result;
    }

    private static boolean hasNavigationBar(Resources resources) {
        int navBarId = resources.getIdentifier("config_showNavigationBar", "bool", "android");
        log.debug("hasNavigationBar: navBarId=" + navBarId + ", hasNavBar=" + resources.getBoolean(navBarId));
        return navBarId > 0 && resources.getBoolean(navBarId);
    }

    public static boolean isNavBarAtBottom(Context context) {
        // detect navigation bar orientation https://stackoverflow.com/questions/21057035/detect-android-navigation-bar-orientation
        final boolean isNavAtBottom = (context.getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE)
                || (context.getResources().getConfiguration().smallestScreenWidthDp >= 600);
        log.debug("isNavBarAtBottom: NavBarAtBottom=" + isNavAtBottom);
        return isNavAtBottom;
    }

    public static boolean isSystemBarOnBottom(Context mContext) {
        Resources res=mContext.getResources();
        Configuration cfg=res.getConfiguration();
        DisplayMetrics dm=res.getDisplayMetrics();
        boolean canMove=(dm.widthPixels != dm.heightPixels &&
                cfg.smallestScreenWidthDp < 600);
        return(!canMove || dm.widthPixels < dm.heightPixels);
    }

    public static boolean isNavigationBarOnBottom(View rootView, Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Use WindowInsets API for API 30+
            WindowInsets insets = rootView.getRootWindowInsets();
            if (insets != null) {
                Insets navigationBarInsets = insets.getInsets(WindowInsets.Type.navigationBars());
                int bottomInset = navigationBarInsets.bottom;
                int rightInset = navigationBarInsets.right;
                int leftInset = navigationBarInsets.left;

                // Navigation bar is on the bottom if the bottom inset is greater than 0
                return bottomInset > 0;
            }
        } else {
            // Fallback for API < 30
            Resources resources = context.getResources();
            Configuration config = resources.getConfiguration();
            DisplayMetrics dm = resources.getDisplayMetrics();

            // Check if the navigation bar can move (e.g., on phones)
            boolean canMove = (dm.widthPixels != dm.heightPixels && config.smallestScreenWidthDp < 600);

            // Navigation bar is on the bottom if:
            // - It cannot move, or
            // - The screen is in portrait orientation
            return !canMove || dm.widthPixels < dm.heightPixels;
        }

        // Default to true if unable to determine
        return true;
    }

    public static boolean hasNavBar(Resources resources) {
        int id = resources.getIdentifier("config_showNavigationBar", "bool", "android");
        if (id > 0) return resources.getBoolean(id);
        else return false;
    }

    public static int getSystemBarHeight(Resources resources) {
        if (!hasNavBar(resources))
            return 0;
        int orientation = resources.getConfiguration().orientation;
        //Only phone between 0-599 has navigationbar can move
        boolean isSmartphone = resources.getConfiguration().smallestScreenWidthDp < 600;
        if (isSmartphone && Configuration.ORIENTATION_LANDSCAPE == orientation) return 0;
        int id = resources
                .getIdentifier(orientation == Configuration.ORIENTATION_PORTRAIT ? "navigation_bar_height" : "navigation_bar_height_landscape", "dimen", "android");
        if (id > 0) return resources.getDimensionPixelSize(id);
        return 0;
    }

    public static int getSystemBarWidth(Resources resources) {
        if (hasNavBar(resources)) return 0;
        int orientation = resources.getConfiguration().orientation;
        //Only phone between 0-599 has navigationbar can move
        boolean isSmartphone = resources.getConfiguration().smallestScreenWidthDp < 600;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE && isSmartphone) {
            int id = resources.getIdentifier("navigation_bar_width", "dimen", "android");
            if (id > 0) return resources.getDimensionPixelSize(id);
        }
        return 0;
    }

    public static int getActionBarHeight(Context context) {
        int actionBarHeight = 0;
        final TypedArray styledAttributes = context.getTheme().obtainStyledAttributes(
                new int[] { android.R.attr.actionBarSize }
        );
        actionBarHeight = (int) styledAttributes.getDimension(0, 0);
        styledAttributes.recycle();
        return actionBarHeight;
    }

    public static View getActionBarView(Window window) {
        View v = window.getDecorView();
        return v.findViewById(R.id.action_bar_container);
    }

    // additionalBottomMargin is the additional bottom margin to apply to the viewlayout for example on subtitles that needs to be shifted also on top of the playerController controlBar (seek + controls) if not already above with subtitleVposPixel
    // alreadyAppliedBottomMargin is the bottom margin already applied to viewlayout for example on subtitles to shift them up to take into account not to apply if subtitles are already above the intent layout

    public static void adjustViewLayoutForInsets(Context context, View rootView, View viewLayout, String viewName, boolean navigationBarShowing, boolean systemBarShowing, boolean actionBarShowing,
                                                 boolean controlBarShowing, boolean isNavBarOnBottom, boolean isGestureAreaShowing, int additionalBottomMargin, int alreadyAppliedBottomMargin,
                                                 boolean adjustLeft, boolean adjustTop, boolean adjustRight, boolean adjustBottom,
                                                 boolean applyCutoutLeft, boolean applyCutoutTop, boolean applyCutoutRight, boolean applyCutoutBottom) {
        log.debug("adjustViewLayoutForInsets: {} navigationBarShowing={}, systemBarShowing={}, actionBarShowing={}, controlBarShowing={}, isNavBarOnBottom={}, isGestureAreaShowing={}, additionalBottomMargin={}, alreadyAppliedBottomMargin={}",
                viewName, navigationBarShowing, systemBarShowing, actionBarShowing, controlBarShowing, isNavBarOnBottom, isGestureAreaShowing, additionalBottomMargin, alreadyAppliedBottomMargin);
        log.debug("adjustViewLayoutForInsets: {} getNavigationBarHeight()={}, getGestureAreaHeight()={}, getStatusBarHeight()={}, getActionBarHeight()={}, getSystemBarHeight()={}",
                viewName, MiscUtils.getNavigationBarHeight(context), MiscUtils.getGestureAreaHeight(context), MiscUtils.getStatusBarHeight(context), MiscUtils.getActionBarHeight(context), MiscUtils.getSystemBarHeight(context.getResources()));
        int left, top, right, bottom;
        left = top = right = bottom = 0;
        int rotation = (PlayerActivity.isRotationLocked() ? PlayerActivity.getLockedRotation(): ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets insets = rootView.getRootWindowInsets();
            if (insets == null) return;
            Insets systemBarsInsets = insets.getInsets(WindowInsets.Type.systemBars());
            Insets navigationBarInsets = insets.getInsets(WindowInsets.Type.navigationBars());
            Insets statusBarInsets = insets.getInsets(WindowInsets.Type.statusBars());
            DisplayCutout cutout = insets.getDisplayCutout();
            log.debug("adjustViewLayoutForInsets: {} LTRB systemBarsInsets=({},{},{},{}), navigationBarInsets=({},{},{},{}), statusBarInsets=({},{},{},{}), cutout=({},{},{},{})",
                    viewName, systemBarsInsets.left, systemBarsInsets.top, systemBarsInsets.right, systemBarsInsets.bottom,
                    navigationBarInsets.left, navigationBarInsets.top, navigationBarInsets.right, navigationBarInsets.bottom,
                    statusBarInsets.left, statusBarInsets.top, statusBarInsets.right, statusBarInsets.bottom,
                    (cutout == null ? 0 : cutout.getSafeInsetLeft()), (cutout == null ? 0 : cutout.getSafeInsetTop()), (cutout == null ? 0 : cutout.getSafeInsetRight()), (cutout == null ? 0 : cutout.getSafeInsetBottom()));
            if (cutout != null) {
                if (applyCutoutLeft) left = cutout.getSafeInsetLeft();
                if (applyCutoutTop) top = cutout.getSafeInsetTop();
                if (applyCutoutRight) right = cutout.getSafeInsetRight();
                if (applyCutoutBottom) bottom = cutout.getSafeInsetBottom();
            }
            if (adjustLeft && systemBarShowing) left += systemBarsInsets.left;
            if (adjustTop && systemBarShowing) top += statusBarInsets.top;
            if (adjustRight && systemBarShowing) right += systemBarsInsets.right;
            if (adjustBottom && navigationBarShowing && isNavBarOnBottom) bottom += systemBarsInsets.bottom; // bottom margin is 0 if no navigation bar
        } else {
            WindowInsets insets = rootView.getRootWindowInsets();
            if (insets == null) return;
            DisplayCutout cutout = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cutout = rootView.getRootWindowInsets().getDisplayCutout();
                if (cutout != null) {
                    if (applyCutoutLeft) left = cutout.getSafeInsetLeft();
                    if (applyCutoutTop) top = cutout.getSafeInsetTop();
                    if (applyCutoutRight) right = cutout.getSafeInsetRight();
                    if (applyCutoutBottom) bottom = cutout.getSafeInsetBottom();
                }
            }
            log.debug("adjustViewLayoutForInsets: {} LTRB insets=({},{},{},{}), cutout=({},{},{},{})", viewName, insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom(),
                    (cutout == null ? 0 : cutout.getSafeInsetLeft()), (cutout == null ? 0 : cutout.getSafeInsetTop()), (cutout == null ? 0 : cutout.getSafeInsetRight()), (cutout == null ? 0 : cutout.getSafeInsetBottom()));
            if (adjustLeft && systemBarShowing) left += insets.getSystemWindowInsetLeft();
            if (adjustTop && systemBarShowing) top += insets.getSystemWindowInsetTop();
            if (adjustRight && systemBarShowing) right += insets.getSystemWindowInsetRight();
            if (adjustBottom && navigationBarShowing && isNavBarOnBottom) bottom += insets.getSystemWindowInsetBottom(); // bottom margin is 0 if no navigation bar
        }
        ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) viewLayout.getLayoutParams();
        int prevLeft, prevTop, prevRight, prevBottom;
        prevLeft = layoutParams.leftMargin; prevTop = layoutParams.topMargin; prevRight = layoutParams.rightMargin; prevBottom = layoutParams.bottomMargin;
        log.debug("adjustViewLayoutForInsets, {} orientation is {}({}), isRotationLocked={}", viewName, PlayerActivity.getHumanReadableRotation(rotation), rotation, PlayerActivity.isRotationLocked());
        layoutParams.leftMargin = left;
        layoutParams.topMargin = top;
        layoutParams.rightMargin = right;
        // extra logic for subtitles handling that need to be shifted up above controlBar of playerController if the subtitleVposPixel is not shifting them already above
        int shiftBottom = bottom + additionalBottomMargin - alreadyAppliedBottomMargin;
        // Whether Subtitle minimum Vertical Position should be above the StatusBar or not. Only changes the Vertical Position if it is too low.
        layoutParams.bottomMargin = Math.max(shiftBottom, 0);
        log.debug("adjustViewLayoutForInsets: {} layoutParams ({},{},{},{})->({},{},{},{})",
                viewName, prevLeft, prevTop, prevRight, prevBottom,
                layoutParams.leftMargin, layoutParams.topMargin, layoutParams.rightMargin, layoutParams.bottomMargin);
        if (prevBottom > 0 && bottom == 0) {
            log.debug("adjustViewLayoutForInsets: Delaying relayout due to give time to navigation bar to fade out");
            // Schedule the delayed relayout
            if (relayoutRunnable != null) {
                log.debug("adjustViewLayoutForInsets: Cancel previous delayed relayout");
                handler.removeCallbacks(relayoutRunnable);
            }
            relayoutRunnable = () -> {
                viewLayout.setLayoutParams(layoutParams);
                viewLayout.forceLayout();
                viewLayout.requestLayout();
                log.debug("adjustViewLayoutForInsets: Delayed relayout applied");
            };
            // wait a little: avoid a glitch (subtitles being displayed under the system bar for x ms), note that gesture bar fades away slowly
            handler.postDelayed(relayoutRunnable, (isGestureAreaShowing ? DELAY_MILLIS_GESTURE_NAVIGATION : DELAY_MILLIS_NORMAL));
        } else {
            // Apply the relayout immediately
            viewLayout.setLayoutParams(layoutParams);
            viewLayout.forceLayout();
            viewLayout.requestLayout();
            log.debug("adjustViewLayoutForInsets: Immediate relayout applied");
        }
    }
}

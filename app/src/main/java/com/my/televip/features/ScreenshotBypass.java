package com.my.televip.features;

import android.view.WindowManager;

import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.logging.Logger;
import com.my.televip.utils.Utils;

import de.robv.android.xposed.XposedHelpers;

/**
 * ScreenshotBypass — allows screenshots and screen recording in any chat,
 * including secret chats and groups with protected content enabled.
 *
 * Telegram (and Android) uses WindowManager.LayoutParams.FLAG_SECURE to block
 * screenshots.  We hook android.view.Window.addFlags() — running inside the
 * Telegram process — and silently strip FLAG_SECURE before it is applied.
 * The window is never locked, so any screenshot tool works normally.
 */
public class ScreenshotBypass {

    public static boolean isEnable = false;

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;

                // Hook addFlags on the base Window class — covers every window
                // Telegram ever creates (main activity, secret chats, drawers).
                XposedHelpers.findAndHookMethod(
                    "android.view.Window",
                    Utils.classLoader,
                    "addFlags",
                    int.class,
                    new AbstractMethodHook() {
                        @Override
                        protected void beforeMethod(MethodHookParam param) {
                            if (!ConfigManager.screenshotBypass.isEnable()) return;
                            int flags = (int) param.args[0];
                            if ((flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                                // Strip FLAG_SECURE, leave all other flags intact
                                param.args[0] = flags & ~WindowManager.LayoutParams.FLAG_SECURE;
                            }
                        }
                    });

                // Also hook setFlags which is sometimes called directly
                XposedHelpers.findAndHookMethod(
                    "android.view.Window",
                    Utils.classLoader,
                    "setFlags",
                    int.class, int.class,
                    new AbstractMethodHook() {
                        @Override
                        protected void beforeMethod(MethodHookParam param) {
                            if (!ConfigManager.screenshotBypass.isEnable()) return;
                            int flags = (int) param.args[0];
                            int mask  = (int) param.args[1];
                            if ((flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                                param.args[0] = flags & ~WindowManager.LayoutParams.FLAG_SECURE;
                            }
                            if ((mask & WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                                param.args[1] = mask & ~WindowManager.LayoutParams.FLAG_SECURE;
                            }
                        }
                    });
            }
        } catch (Throwable e) { Logger.e(e); }
    }
}

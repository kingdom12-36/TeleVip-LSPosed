package com.my.televip.features;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * IgnoreBlocked — receive messages from users you have blocked.
 *
 * Strategy:
 *  1. Hook MessagesController.isUserBlocked(long) by name (kept in most forks).
 *  2. AutomationResolver fallback.
 *  3. Brute-force scan: any boolean(long/int) method with "block" in the name.
 */
public class IgnoreBlocked {

    public static boolean isEnable = false;

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;
                Class<?> mcClass = ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER);
                if (mcClass == null) return;

                // ── Strategy 1: hook by well-known name ──────────────────────────
                try {
                    XposedHelpers.findAndHookMethod(mcClass, "isUserBlocked", long.class,
                        new AbstractMethodHook() {
                            @Override
                            protected void beforeMethod(MethodHookParam param) {
                                if (ConfigManager.ignoreBlocked != null
                                        && ConfigManager.ignoreBlocked.isEnable())
                                    param.setResult(false);
                            }
                        });
                } catch (Throwable ignored) {}

                // ── Strategy 2: AutomationResolver ──────────────────────────────
                try {
                    HMethod.hookMethod(mcClass,
                        AutomationResolver.resolve("MessagesController", "isUserBlocked",
                            AutomationResolver.ResolverType.Method),
                        long.class,
                        new AbstractMethodHook() {
                            @Override
                            protected void beforeMethod(MethodHookParam param) {
                                if (ConfigManager.ignoreBlocked != null
                                        && ConfigManager.ignoreBlocked.isEnable())
                                    param.setResult(false);
                            }
                        });
                } catch (Throwable ignored) {}

                // ── Strategy 3: brute-force scan ────────────────────────────────
                try {
                    for (Method m : mcClass.getDeclaredMethods()) {
                        if (m.getReturnType() == boolean.class
                                && m.getParameterCount() == 1
                                && (m.getParameterTypes()[0] == long.class
                                    || m.getParameterTypes()[0] == int.class)
                                && m.getName().toLowerCase().contains("block")) {
                            XposedBridge.hookMethod(m, new AbstractMethodHook() {
                                @Override
                                protected void beforeMethod(MethodHookParam param) {
                                    if (ConfigManager.ignoreBlocked != null
                                            && ConfigManager.ignoreBlocked.isEnable())
                                        param.setResult(false);
                                }
                            });
                        }
                    }
                } catch (Throwable t) { Logger.e(t); }
            }
        } catch (Throwable e) { Logger.e(e); }
    }
}

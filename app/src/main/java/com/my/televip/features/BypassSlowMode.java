package com.my.televip.features;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;

public class BypassSlowMode {

    public static boolean isEnable = false;

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;

                if (ClassLoad.getClass(ClassNames.CHAT_ACTIVITY) != null) {

                    // Bypass the slow mode countdown timer — prevents it from blocking the send button
                    HMethod.hookMethod(
                            ClassLoad.getClass(ClassNames.CHAT_ACTIVITY),
                            AutomationResolver.resolve("ChatActivity", "updateSlowModeTimer", AutomationResolver.ResolverType.Method),
                            new AbstractMethodHook() {
                                @Override
                                protected void beforeMethod(MethodHookParam param) {
                                    if (ConfigManager.bypassSlowMode.isEnable()) {
                                        param.setResult(null);
                                    }
                                }
                            });

                    // Force getSlowModeTimer to return 0 — no wait time reported to UI
                    HMethod.hookMethod(
                            ClassLoad.getClass(ClassNames.CHAT_ACTIVITY),
                            AutomationResolver.resolve("ChatActivity", "getSlowModeTimer", AutomationResolver.ResolverType.Method),
                            new AbstractMethodHook() {
                                @Override
                                protected void beforeMethod(MethodHookParam param) {
                                    if (ConfigManager.bypassSlowMode.isEnable()) {
                                        param.setResult(0);
                                    }
                                }
                            });

                    // Bypass checkSlowMode — tells ChatActivity slow mode is not active
                    HMethod.hookMethod(
                            ClassLoad.getClass(ClassNames.CHAT_ACTIVITY),
                            AutomationResolver.resolve("ChatActivity", "checkSlowMode", AutomationResolver.ResolverType.Method),
                            new AbstractMethodHook() {
                                @Override
                                protected void beforeMethod(MethodHookParam param) {
                                    if (ConfigManager.bypassSlowMode.isEnable()) {
                                        param.setResult(false);
                                    }
                                }
                            });
                }
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }
}

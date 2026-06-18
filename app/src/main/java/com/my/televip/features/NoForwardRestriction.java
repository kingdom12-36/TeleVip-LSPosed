package com.my.televip.features;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;

import de.robv.android.xposed.XposedHelpers;

public class NoForwardRestriction {

    public static boolean isEnable = false;

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;

                // ── Approach 1: clear noforwards on TLRPC$Chat when ChatActivity reads it ──
                if (ClassLoad.getClass(ClassNames.CHAT_ACTIVITY) != null) {

                    // Hook the method that sets up the message action bar (forward button etc.)
                    // When enabled, clear noForwards on the current chat object
                    try {
                        HMethod.hookMethod(
                            ClassLoad.getClass(ClassNames.CHAT_ACTIVITY),
                            AutomationResolver.resolve("ChatActivity", "updateBottomOverlay",
                                AutomationResolver.ResolverType.Method),
                            new AbstractMethodHook() {
                                @Override
                                protected void beforeMethod(MethodHookParam param) {
                                    clearNoForwardsOnChat(param.thisObject);
                                }
                            });
                    } catch (Throwable ignored) {}

                    // Also hook updateActionBar / updateHeader as fallback
                    for (String m : new String[]{"updateActionBar", "updateTitle", "onResume"}) {
                        try {
                            HMethod.hookMethod(
                                ClassLoad.getClass(ClassNames.CHAT_ACTIVITY),
                                AutomationResolver.resolve("ChatActivity", m, AutomationResolver.ResolverType.Method),
                                new AbstractMethodHook() {
                                    @Override
                                    protected void beforeMethod(MethodHookParam param) {
                                        clearNoForwardsOnChat(param.thisObject);
                                    }
                                });
                        } catch (Throwable ignored) {}
                    }
                }

                // ── Approach 2: hook any method in TLRPC$Chat that returns the noforwards flag ──
                if (ClassLoad.getClass(ClassNames.TLRPC_CHAT) != null) {
                    try {
                        for (java.lang.reflect.Method m :
                                ClassLoad.getClass(ClassNames.TLRPC_CHAT).getDeclaredMethods()) {
                            if (m.getReturnType() == boolean.class && m.getParameterCount() == 0) {
                                String name = m.getName().toLowerCase();
                                if (name.contains("forward") || name.contains("noforward")) {
                                    HMethod.hookMethod(m, new AbstractMethodHook() {
                                        @Override
                                        protected void afterMethod(MethodHookParam param) {
                                            if (ConfigManager.noForwardRestriction.isEnable())
                                                param.setResult(false); // false = no restriction
                                        }
                                    });
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable e) { Logger.e(e); }
    }

    private static void clearNoForwardsOnChat(Object chatActivityInstance) {
        if (!ConfigManager.noForwardRestriction.isEnable()) return;
        try {
            Object currentChat = XposedHelpers.getObjectField(chatActivityInstance,
                AutomationResolver.resolve("ChatActivity", "currentChat", AutomationResolver.ResolverType.Field));
            if (currentChat == null) return;
            // Try both resolved and raw field names
            try {
                XposedHelpers.setBooleanField(currentChat,
                    AutomationResolver.resolve("TLRPC$Chat", "noforwards", AutomationResolver.ResolverType.Field), false);
            } catch (Throwable t) {
                try { XposedHelpers.setBooleanField(currentChat, "noforwards", false); }
                catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }
}

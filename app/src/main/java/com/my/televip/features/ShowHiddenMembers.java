package com.my.televip.features;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedHelpers;

/**
 * ShowHiddenMembers — bypasses the can_view_participants restriction in groups/channels.
 *
 * When a group/channel hides its member list, TLRPC.ChatFull.can_view_participants = false.
 * ProfileActivity reads this flag to decide whether to show the Members row.
 *
 * Strategy:
 * 1. Hook all methods in ProfileActivity that receive or store a ChatFull object —
 *    force can_view_participants = true before the UI reads the field.
 * 2. Hook MessagesController methods that deal with ChatFull (getChatFull / putChatFull)
 *    so the flag is true from the moment the object enters the local cache.
 */
public class ShowHiddenMembers {

    public static boolean isEnable = false;

    public static void init() {
        if (isEnable) return;
        isEnable = true;
        hookProfileActivity();
        hookMessagesController();
    }

    // ── ProfileActivity ───────────────────────────────────────────────────────

    private static void hookProfileActivity() {
        try {
            Class<?> profileClass = ClassLoad.getClass(ClassNames.PROFILE_ACTIVITY);
            if (profileClass == null) return;

            AbstractMethodHook hook = new AbstractMethodHook() {
                @Override
                protected void beforeMethod(MethodHookParam param) {
                    if (!ConfigManager.showHiddenMembers.isEnable()) return;
                    // Patch any ChatFull passed as an argument
                    if (param.args != null) {
                        for (Object arg : param.args) {
                            patchChatFull(arg);
                        }
                    }
                    // Also patch the chatInfo field stored on the activity instance
                    for (String fieldName : new String[]{"chatInfo", "info"}) {
                        try {
                            String resolved = AutomationResolver.resolve(
                                "ProfileActivity", fieldName, AutomationResolver.ResolverType.Field);
                            Object chatInfo = null;
                            try {
                                chatInfo = XposedHelpers.getObjectField(param.thisObject, resolved);
                            } catch (Throwable ignored) {
                                chatInfo = XposedHelpers.getObjectField(param.thisObject, fieldName);
                            }
                            patchChatFull(chatInfo);
                            break;
                        } catch (Throwable ignored) {}
                    }
                }
            };

            // Hook every method in ProfileActivity whose parameter list includes a ChatFull type
            for (Method m : profileClass.getDeclaredMethods()) {
                boolean hasChatFull = false;
                for (Class<?> p : m.getParameterTypes()) {
                    if (p.getName().contains("ChatFull")) {
                        hasChatFull = true;
                        break;
                    }
                }
                if (hasChatFull) {
                    try { HMethod.hookMethod(m, hook); } catch (Throwable ignored) {}
                }
            }

            // Also hook the layout-refresh method (called whenever info changes)
            String updateRows = AutomationResolver.resolve(
                "ProfileActivity", "updateListAnimated", AutomationResolver.ResolverType.Method);
            for (Method m : profileClass.getDeclaredMethods()) {
                if (m.getName().equals(updateRows) || m.getName().equals("updateListAnimated")
                        || m.getName().equals("updateRows")) {
                    try { HMethod.hookMethod(m, hook); } catch (Throwable ignored) {}
                    break;
                }
            }

        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    // ── MessagesController ────────────────────────────────────────────────────

    private static void hookMessagesController() {
        try {
            Class<?> mcClass = ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER);
            if (mcClass == null) return;

            AbstractMethodHook chatFullHook = new AbstractMethodHook() {
                @Override
                protected void afterMethod(MethodHookParam param) {
                    if (!ConfigManager.showHiddenMembers.isEnable()) return;
                    // getChatFull-style: patch return value
                    Object result = param.getResult();
                    if (result != null) {
                        patchChatFull(result);
                        return;
                    }
                    // putChatFull-style: patch argument
                    if (param.args != null) {
                        for (Object arg : param.args) {
                            patchChatFull(arg);
                        }
                    }
                }
            };

            String getChatFull = AutomationResolver.resolve(
                "MessagesController", "getChatFull", AutomationResolver.ResolverType.Method);
            String putChatFull = AutomationResolver.resolve(
                "MessagesController", "putChatFull", AutomationResolver.ResolverType.Method);

            for (Method m : mcClass.getDeclaredMethods()) {
                String name = m.getName();
                boolean match = name.equals(getChatFull) || name.equals("getChatFull")
                    || name.equals(putChatFull) || name.equals("putChatFull");

                if (!match) {
                    // Also catch by return type or any param whose class name contains ChatFull
                    if (m.getReturnType().getName().contains("ChatFull")) {
                        match = true;
                    } else {
                        for (Class<?> p : m.getParameterTypes()) {
                            if (p.getName().contains("ChatFull")) { match = true; break; }
                        }
                    }
                }

                if (match) {
                    try { HMethod.hookMethod(m, chatFullHook); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Sets can_view_participants = true on any TLRPC.ChatFull instance.
     * Silently ignores nulls or wrong types.
     */
    private static void patchChatFull(Object obj) {
        if (obj == null) return;
        if (!obj.getClass().getName().contains("ChatFull")) return;
        try {
            XposedHelpers.setBooleanField(obj, "can_view_participants", true);
        } catch (Throwable ignored) {}
    }
}

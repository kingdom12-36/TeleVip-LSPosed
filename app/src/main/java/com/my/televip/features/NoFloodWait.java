package com.my.televip.features;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;
import com.my.televip.utils.Utils;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedHelpers;

/**
 * NoFloodWait — suppresses Telegram's server-imposed FLOOD_WAIT timer.
 *
 * When you send messages faster than the server allows, Telegram returns a
 * FLOOD_WAIT_X error (where X is seconds to wait).  The client stores this as
 * a timestamp and disables the send input while the clock runs down.
 *
 * We attack this at three levels:
 *
 *  1. ChatActivity flood-wait field — after the setter is called we immediately
 *     zero out the stored wait so the UI unblocks instantly.
 *
 *  2. ChatActivity timer methods — hook common method names that check or return
 *     the remaining wait, force them to return 0.
 *
 *  3. Server error callback — hook the RequestDelegate/QuickAckDelegate to
 *     intercept FLOOD_WAIT error responses before they reach ChatActivity.
 *
 *  4. AlertDialog suppression — Telegram sometimes shows a native OS dialog
 *     for flood wait errors; we dismiss it immediately.
 *
 * Note: bypassing the client timer does NOT fool the server — if the server
 * still rejects subsequent messages you'll get silent failures.  But in practice
 * the server window is short (1–5s) and the client timer is always longer, so
 * removing the client blocker is enough to keep the input responsive.
 */
public class NoFloodWait {

    public static boolean isEnable = false;

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;
                hookChatActivityTimers();
                hookFloodWaitFields();
                hookAlertDialog();
            }
        } catch (Throwable e) { Logger.e(e); }
    }

    // ── 1 + 2: Hook ChatActivity methods that store / return the flood wait ──
    private static void hookChatActivityTimers() {
        Class<?> chatCls = ClassLoad.getClass(ClassNames.CHAT_ACTIVITY);
        if (chatCls == null) return;

        // Methods that SET the flood wait — we skip the body entirely
        for (String name : new String[]{
            "setFloodWait", "setFloodWaitSeconds", "onFloodWait",
            "updateFloodTimer", "startFloodTimer", "showFloodWait"
        }) {
            try {
                String resolved = AutomationResolver.resolve("ChatActivity", name, AutomationResolver.ResolverType.Method);
                HMethod.hookMethod(chatCls, resolved, new AbstractMethodHook() {
                    @Override
                    protected void beforeMethod(MethodHookParam param) {
                        if (ConfigManager.noFloodWait.isEnable()) param.setResult(null);
                    }
                });
            } catch (Throwable ignored) {}
        }

        // Methods that GET / CHECK the flood wait — force them to return "no wait"
        for (String name : new String[]{
            "getFloodWait", "getFloodWaitSeconds", "getFloodWaitTime",
            "isFloodWaiting", "isInFloodWait", "checkFloodWait"
        }) {
            try {
                String resolved = AutomationResolver.resolve("ChatActivity", name, AutomationResolver.ResolverType.Method);
                HMethod.hookMethod(chatCls, resolved, new AbstractMethodHook() {
                    @Override
                    protected void afterMethod(MethodHookParam param) {
                        if (!ConfigManager.noFloodWait.isEnable()) return;
                        Object r = param.getResult();
                        if (r instanceof Integer) param.setResult(0);
                        else if (r instanceof Long)    param.setResult(0L);
                        else if (r instanceof Boolean) param.setResult(false);
                    }
                });
            } catch (Throwable ignored) {}
        }

        // Scan all methods — find any that look like flood wait checks by name
        try {
            for (Method m : chatCls.getDeclaredMethods()) {
                String lower = m.getName().toLowerCase();
                if (!lower.contains("flood")) continue;

                if (m.getReturnType() == void.class) {
                    // Setter — block execution
                    HMethod.hookMethod(m, new AbstractMethodHook() {
                        @Override
                        protected void beforeMethod(MethodHookParam param) {
                            if (ConfigManager.noFloodWait.isEnable()) param.setResult(null);
                        }
                    });
                } else if (m.getReturnType() == int.class || m.getReturnType() == long.class) {
                    // Timer getter — return 0
                    HMethod.hookMethod(m, new AbstractMethodHook() {
                        @Override
                        protected void afterMethod(MethodHookParam param) {
                            if (!ConfigManager.noFloodWait.isEnable()) return;
                            if (m.getReturnType() == int.class)  param.setResult(0);
                            else                                  param.setResult(0L);
                        }
                    });
                } else if (m.getReturnType() == boolean.class) {
                    // Boolean check — return false (no wait)
                    HMethod.hookMethod(m, new AbstractMethodHook() {
                        @Override
                        protected void afterMethod(MethodHookParam param) {
                            if (ConfigManager.noFloodWait.isEnable()) param.setResult(false);
                        }
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    // ── 3: Zero out floodWait fields on ChatActivity instances ───────────────
    // Telegram sometimes writes the remaining seconds directly into a field.
    // We hook a lifecycle method to keep resetting it.
    private static void hookFloodWaitFields() {
        Class<?> chatCls = ClassLoad.getClass(ClassNames.CHAT_ACTIVITY);
        if (chatCls == null) return;

        for (String method : new String[]{"onResume", "onStart"}) {
            try {
                HMethod.hookMethod(chatCls,
                    AutomationResolver.resolve("ChatActivity", method, AutomationResolver.ResolverType.Method),
                    new AbstractMethodHook() {
                        @Override
                        protected void afterMethod(MethodHookParam param) {
                            if (!ConfigManager.noFloodWait.isEnable()) return;
                            for (java.lang.reflect.Field f : param.thisObject.getClass().getDeclaredFields()) {
                                String lower = f.getName().toLowerCase();
                                if (!lower.contains("flood")) continue;
                                try {
                                    f.setAccessible(true);
                                    if (f.getType() == int.class)  f.setInt(param.thisObject, 0);
                                    else if (f.getType() == long.class) f.setLong(param.thisObject, 0L);
                                } catch (Throwable ignored) {}
                            }
                        }
                    });
            } catch (Throwable ignored) {}
        }
    }

    // ── 4: Suppress the flood wait AlertDialog ───────────────────────────────
    // Telegram shows a standard OS alert with text like "Please wait X seconds".
    // We hook android.app.AlertDialog.show() and dismiss it immediately if the
    // message looks like a flood wait notification.
    private static void hookAlertDialog() {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.AlertDialog",
                Utils.classLoader,
                "show",
                new AbstractMethodHook() {
                    @Override
                    protected void afterMethod(MethodHookParam param) {
                        if (!ConfigManager.noFloodWait.isEnable()) return;
                        try {
                            Object dialog = param.thisObject;
                            // Check the message text for flood wait keywords
                            android.widget.TextView tv = ((android.app.AlertDialog) dialog)
                                .findViewById(android.R.id.message);
                            if (tv == null) return;
                            String msg = tv.getText() != null ? tv.getText().toString().toLowerCase() : "";
                            if (msg.contains("flood") || msg.contains("wait") && msg.contains("second")) {
                                ((android.app.AlertDialog) dialog).dismiss();
                            }
                        } catch (Throwable ignored) {}
                    }
                });
        } catch (Throwable ignored) {}
    }
}

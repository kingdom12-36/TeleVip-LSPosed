package com.my.televip.features;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;
import com.my.televip.utils.Utils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * MessageSchedulerBypass — removes client-side limits on how many messages
 * you can have scheduled in a single chat.
 *
 * Telegram enforces a cap (100 scheduled messages per chat) both server-side
 * and client-side.  The server will still reject pushes beyond its hard cap,
 * but the CLIENT blocks the UI from even trying once it thinks the limit is
 * reached.  This feature removes that client-side gate.
 *
 * Attack surface:
 *  1. ScheduledMessagesController — patches limit/count checks.
 *  2. ChatActivity permission checks — gates the "Schedule Message" option.
 *  3. AlertDialog suppression — dismisses "Too many scheduled messages".
 *  4. Broad method scan — any "scheduled" boolean/int method is patched.
 */
public class MessageSchedulerBypass {

    public static boolean isEnable = false;

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;
                hookScheduledMessagesController();
                hookChatActivityScheduler();
                hookAlertDialog();
            }
        } catch (Throwable e) { Logger.e(e); }
    }

    // ── 1. ScheduledMessagesController ───────────────────────────────────────
    private static void hookScheduledMessagesController() {
        for (String cls : new String[]{
            "org.telegram.messenger.ScheduledMessagesController",
            "org.telegram.messenger.scheduled.ScheduledMessagesController"
        }) {
            try {
                Class<?> c = XposedHelpers.findClassIfExists(cls, Utils.classLoader);
                if (c == null) continue;

                for (Method m : c.getDeclaredMethods()) {
                    String lower = m.getName().toLowerCase();
                    boolean isLimitCheck = lower.contains("limit") || lower.contains("maxcount")
                        || lower.contains("max_count") || lower.contains("isreached")
                        || lower.contains("isfull") || lower.contains("canschedule");
                    boolean isCountGetter = (lower.contains("count") || lower.contains("size"))
                        && lower.contains("schedul");

                    if (isLimitCheck && m.getReturnType() == boolean.class) {
                        HMethod.hookMethod(m, new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                if (ConfigManager.messageSchedulerBypass.isEnable())
                                    param.setResult(false);
                            }
                        });
                    } else if (isCountGetter && (m.getReturnType() == int.class || m.getReturnType() == long.class)) {
                        HMethod.hookMethod(m, new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                if (!ConfigManager.messageSchedulerBypass.isEnable()) return;
                                if (m.getReturnType() == int.class) param.setResult(0);
                                else param.setResult(0L);
                            }
                        });
                    }
                }

                // Hook all constructors via XposedBridge to avoid capture type issues
                for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                    try {
                        XposedBridge.hookMethod(ctor, new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                // placeholder — field zeroing can be added here if needed
                            }
                        });
                    } catch (Throwable ignored) {}
                }

                // Hook canScheduleMessage by known names
                for (String name : new String[]{"canScheduleMessage", "canAddScheduled", "checkScheduleLimit"}) {
                    try {
                        HMethod.hookMethod(c, name, new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                if (ConfigManager.messageSchedulerBypass.isEnable())
                                    param.setResult(true);
                            }
                        });
                    } catch (Throwable ignored) {}
                }

                break;
            } catch (Throwable ignored) {}
        }
    }

    // ── 2. ChatActivity schedule-related checks ───────────────────────────────
    private static void hookChatActivityScheduler() {
        Class<?> chatCls = ClassLoad.getClass(ClassNames.CHAT_ACTIVITY);
        if (chatCls == null) return;

        for (String name : new String[]{
            "isScheduleLimitReached", "canScheduleMessage", "checkScheduleLimit",
            "isScheduledFull", "maxScheduledMessages", "getScheduleLimit"
        }) {
            try {
                HMethod.hookMethod(chatCls,
                    AutomationResolver.resolve("ChatActivity", name, AutomationResolver.ResolverType.Method),
                    new AbstractMethodHook() {
                        @Override
                        protected void afterMethod(MethodHookParam param) {
                            if (!ConfigManager.messageSchedulerBypass.isEnable()) return;
                            Object r = param.getResult();
                            if (r instanceof Boolean) {
                                String n = ((Method) param.method).getName().toLowerCase();
                                boolean isNegative = n.contains("limit") || n.contains("full")
                                    || n.contains("reached");
                                param.setResult(!isNegative);
                            } else if (r instanceof Integer) {
                                param.setResult(Integer.MAX_VALUE);
                            }
                        }
                    });
            } catch (Throwable ignored) {}
        }

        // Broad scan
        try {
            for (Method m : chatCls.getDeclaredMethods()) {
                String lower = m.getName().toLowerCase();
                if (!lower.contains("schedul")) continue;
                try {
                    if (m.getReturnType() == boolean.class) {
                        HMethod.hookMethod(m, new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                if (!ConfigManager.messageSchedulerBypass.isEnable()) return;
                                String n = m.getName().toLowerCase();
                                if (n.contains("limit") || n.contains("full") || n.contains("reached"))
                                    param.setResult(false);
                                else if (n.contains("can") || n.contains("allow"))
                                    param.setResult(true);
                            }
                        });
                    } else if (m.getReturnType() == int.class && m.getParameterCount() == 0) {
                        HMethod.hookMethod(m, new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                if (ConfigManager.messageSchedulerBypass.isEnable())
                                    param.setResult(Integer.MAX_VALUE);
                            }
                        });
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    // ── 3. Suppress "Too many scheduled messages" AlertDialog ─────────────────
    private static void hookAlertDialog() {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.AlertDialog",
                Utils.classLoader,
                "show",
                new AbstractMethodHook() {
                    @Override
                    protected void afterMethod(MethodHookParam param) {
                        if (!ConfigManager.messageSchedulerBypass.isEnable()) return;
                        try {
                            android.app.AlertDialog dialog = (android.app.AlertDialog) param.thisObject;
                            android.widget.TextView tv = dialog.findViewById(android.R.id.message);
                            if (tv == null) return;
                            String msg = tv.getText() != null ? tv.getText().toString().toLowerCase() : "";
                            if (msg.contains("scheduled") && (msg.contains("too many") || msg.contains("limit"))) {
                                dialog.dismiss();
                            }
                        } catch (Throwable ignored) {}
                    }
                });
        } catch (Throwable ignored) {}
    }
}

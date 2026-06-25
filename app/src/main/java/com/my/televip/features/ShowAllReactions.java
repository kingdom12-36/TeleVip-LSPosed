package com.my.televip.features;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * ShowAllReactions — forces TLRPC.MessageReactions.can_see_list = true so that
 * tapping a reaction bubble always opens the "who reacted" list, even in groups
 * and channels where the admin has disabled reaction visibility.
 *
 * Strategy:
 * 1. Hook ChatMessageCell.setMessageObject() — force can_see_list = true on
 *    the message's reactions before the cell renders (so the bubble is tappable).
 * 2. Hook MessagesController.processNewMessages() — patch reactions as they
 *    arrive from the server so any cached copy also has the flag set.
 */
public class ShowAllReactions {

    public static boolean isEnable = false;

    public static void init() {
        if (isEnable) return;
        isEnable = true;
        hookSetMessageObject();
        hookMessagesController();
    }

    // ── ChatMessageCell ───────────────────────────────────────────────────────

    private static void hookSetMessageObject() {
        try {
            Class<?> cellClass = ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL);
            if (cellClass == null) return;

            String methodName = AutomationResolver.resolve(
                "ChatMessageCell", "setMessageObject", AutomationResolver.ResolverType.Method);

            AbstractMethodHook hook = new AbstractMethodHook() {
                @Override
                protected void beforeMethod(MethodHookParam param) {
                    if (!ConfigManager.showAllReactions.isEnable()) return;
                    if (param.args == null || param.args.length == 0 || param.args[0] == null)
                        return;
                    try {
                        patchReactionsOnMessageObject(param.args[0]);
                    } catch (Throwable t) {
                        Logger.e(t);
                    }
                }
            };

            boolean hooked = false;
            for (Method m : cellClass.getDeclaredMethods()) {
                if (m.getName().equals(methodName) || m.getName().equals("setMessageObject")) {
                    if (m.getParameterCount() >= 1) {
                        try { HMethod.hookMethod(m, hook); hooked = true; }
                        catch (Throwable ignored) {}
                    }
                }
            }
            if (!hooked) {
                Logger.e(new Exception("[ShowAllReactions] Could not hook setMessageObject"));
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    // ── MessagesController ────────────────────────────────────────────────────

    /**
     * Patch reactions as soon as new messages arrive from the server,
     * before the UI ever touches them.
     */
    private static void hookMessagesController() {
        try {
            Class<?> mcClass = ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER);
            if (mcClass == null) return;

            AbstractMethodHook hook = new AbstractMethodHook() {
                @Override
                protected void afterMethod(MethodHookParam param) {
                    if (!ConfigManager.showAllReactions.isEnable()) return;
                    if (param.args == null) return;
                    try {
                        for (Object arg : param.args) {
                            if (arg instanceof List) {
                                for (Object item : (List<?>) arg) {
                                    patchReactionsOnMessageObject(item);
                                }
                            } else {
                                patchReactionsOnMessageObject(arg);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            };

            String processNew = AutomationResolver.resolve(
                "MessagesController", "processNewMessages", AutomationResolver.ResolverType.Method);
            for (Method m : mcClass.getDeclaredMethods()) {
                if (m.getName().equals(processNew) || m.getName().equals("processNewMessages")) {
                    try { HMethod.hookMethod(m, hook); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Given a MessageObject (wrapper) or a raw TLRPC.Message, extracts the
     * reactions field and sets can_see_list = true.
     */
    private static void patchReactionsOnMessageObject(Object obj) {
        if (obj == null) return;
        try {
            Object message = unwrapMessage(obj);
            if (message == null) return;

            Object reactions = getReactions(message);
            if (reactions == null) return;

            // Force the flag
            try {
                XposedHelpers.setBooleanField(reactions, "can_see_list", true);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    /** Unwraps a MessageObject to its underlying TLRPC.Message if needed. */
    private static Object unwrapMessage(Object obj) {
        if (obj == null) return null;
        String cn = obj.getClass().getName();
        // Raw TLRPC.Message / TL_message — return as-is
        if (cn.contains("TL_message") || cn.contains("tgnet")) return obj;
        // MessageObject wrapper — pull messageOwner
        try {
            return XposedHelpers.getObjectField(obj,
                AutomationResolver.resolve("MessageObject", "messageOwner",
                    AutomationResolver.ResolverType.Field));
        } catch (Throwable ignored) {
            try { return XposedHelpers.getObjectField(obj, "messageOwner"); }
            catch (Throwable ignored2) { return obj; }
        }
    }

    /** Reads the reactions field from a TLRPC.Message, trying resolved and raw names. */
    private static Object getReactions(Object message) {
        try {
            return XposedHelpers.getObjectField(message,
                AutomationResolver.resolve("Message", "reactions",
                    AutomationResolver.ResolverType.Field));
        } catch (Throwable ignored) {
            try { return XposedHelpers.getObjectField(message, "reactions"); }
            catch (Throwable ignored2) { return null; }
        }
    }
}

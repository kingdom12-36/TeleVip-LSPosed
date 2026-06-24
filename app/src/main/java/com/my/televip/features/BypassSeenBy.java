package com.my.televip.features;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;

import de.robv.android.xposed.XposedHelpers;

/**
 * BypassSeenBy — shows "Seen by …" in the long-press menu for ALL group sizes.
 *
 * ROOT CAUSE of breakage: the showMessageSeen condition in ChatActivity checks
 * chatInfo.participants_count (TLRPC.ChatFull) against chatReadMarkSizeThreshold.
 * The old version patched currentChat (TLRPC.Chat) which has NO effect on that check.
 *
 * Fix: in beforeMethod we get the chatInfo field from ChatActivity and set its
 * participants_count to 1. We also patch the message date so the time-window
 * condition passes. Both are restored in afterMethod.
 */
public class BypassSeenBy {

    public static boolean isEnable = false;

    private static int    savedParticipantsCount = -1;
    private static Object patchedChatInfo        = null;
    private static String patchedField           = null;

    private static int    savedMessageDate = -1;
    private static Object patchedMessage   = null;

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;
                if (ClassLoad.getClass(ClassNames.CHAT_ACTIVITY) == null) return;

                Object[] args = AutomationResolver.merge(
                    AutomationResolver.resolveObject("fillMessageMenu", new Class[]{
                        ClassLoad.getClass(ClassNames.MESSAGE_OBJECT),
                        java.util.ArrayList.class,
                        java.util.ArrayList.class,
                        java.util.ArrayList.class
                    }),
                    new AbstractMethodHook() {

                        @Override
                        protected void beforeMethod(MethodHookParam param) {
                            if (!ConfigManager.bypassSeenBy.isEnable()) return;
                            try {
                                patchedChatInfo = null; patchedField = null; savedParticipantsCount = -1;
                                patchedMessage = null; savedMessageDate = -1;

                                Object chatActivity = param.thisObject;

                                // ── 1. Patch chatInfo.participants_count (TLRPC.ChatFull) ─────────
                                // This is the field checked by showMessageSeen:
                                //   chatInfo.participants_count <= chatReadMarkSizeThreshold
                                Object chatInfo = null;
                                try {
                                    chatInfo = XposedHelpers.getObjectField(chatActivity,
                                        AutomationResolver.resolve("ChatActivity", "chatInfo",
                                            AutomationResolver.ResolverType.Field));
                                } catch (Throwable ignored) {
                                    try { chatInfo = XposedHelpers.getObjectField(chatActivity, "chatInfo"); }
                                    catch (Throwable ignored2) {}
                                }

                                if (chatInfo != null) {
                                    for (String f : new String[]{"participants_count", "membersCount"}) {
                                        try {
                                            savedParticipantsCount = XposedHelpers.getIntField(chatInfo, f);
                                            XposedHelpers.setIntField(chatInfo, f, 1);
                                            patchedChatInfo = chatInfo;
                                            patchedField = f;
                                            break;
                                        } catch (Throwable ignored) {}
                                    }
                                }

                                // ── 2. Patch message date so the time-expiry condition passes ──────
                                if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                                    try {
                                        Object msgOwner = XposedHelpers.getObjectField(param.args[0],
                                            AutomationResolver.resolve("MessageObject", "messageOwner",
                                                AutomationResolver.ResolverType.Field));
                                        if (msgOwner != null) {
                                            savedMessageDate = XposedHelpers.getIntField(msgOwner, "date");
                                            int now = (int) (System.currentTimeMillis() / 1000L);
                                            XposedHelpers.setIntField(msgOwner, "date", now);
                                            patchedMessage = msgOwner;
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            } catch (Throwable t) { Logger.e(t); }
                        }

                        @Override
                        protected void afterMethod(MethodHookParam param) {
                            try {
                                if (patchedChatInfo != null && patchedField != null && savedParticipantsCount >= 0)
                                    XposedHelpers.setIntField(patchedChatInfo, patchedField, savedParticipantsCount);
                                if (patchedMessage != null && savedMessageDate >= 0)
                                    XposedHelpers.setIntField(patchedMessage, "date", savedMessageDate);
                            } catch (Throwable t) { Logger.e(t); }
                            finally {
                                patchedChatInfo = null; patchedField = null; savedParticipantsCount = -1;
                                patchedMessage = null; savedMessageDate = -1;
                            }
                        }
                    });

                HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.CHAT_ACTIVITY),
                    AutomationResolver.resolve("ChatActivity", "fillMessageMenu",
                        AutomationResolver.ResolverType.Method),
                    args);
            }
        } catch (Throwable e) { Logger.e(e); }
    }
}

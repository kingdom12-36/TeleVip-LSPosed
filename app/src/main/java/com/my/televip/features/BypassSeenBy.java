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
 * BypassSeenBy — makes "Seen by ..." appear in the long-press menu even in
 * groups where Telegram hides it (large groups, old messages).
 *
 * Strategy: Telegram's fillMessageMenu checks currentChat.participants_count
 * against a threshold (≤ 50 in most builds) before adding the native "Seen by"
 * item.  We hook fillMessageMenu with a before+after pair:
 *
 *   before → save real participants_count, set it to 1 (always qualifies)
 *   after  → restore the real value
 *
 * This piggybacks on Telegram's own "Seen by" bottom-sheet, so all the name
 * resolution, avatar loading, and read-date formatting is handled natively.
 * Works for regular groups; channels never expose read participants server-side.
 */
public class BypassSeenBy {

    public static boolean isEnable = false;

    // We stash the saved count here so before/after can share it without
    // needing a thread-local (fillMessageMenu always runs on the main thread).
    private static int savedParticipantsCount = -1;
    private static Object patchedChat = null;
    private static String patchedField = null;

    // Some builds also time-gate "Seen by" — save/patch the message date too.
    private static int savedMessageDate = -1;
    private static Object patchedMessage = null;

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

                        // ── BEFORE: shrink participants_count so Telegram adds its own "Seen by" ──
                        @Override
                        protected void beforeMethod(MethodHookParam param) {
                            if (!ConfigManager.bypassSeenBy.isEnable()) return;
                            try {
                                patchedChat = null;
                                patchedField = null;
                                savedParticipantsCount = -1;
                                patchedMessage = null;
                                savedMessageDate = -1;

                                Object chatActivity = param.thisObject;

                                // ── 1. Patch participants_count on currentChat ────────────────
                                Object chat = null;
                                try {
                                    chat = XposedHelpers.getObjectField(chatActivity,
                                        AutomationResolver.resolve("ChatActivity", "currentChat", AutomationResolver.ResolverType.Field));
                                } catch (Throwable ignored) {
                                    try { chat = XposedHelpers.getObjectField(chatActivity, "currentChat"); }
                                    catch (Throwable ignored2) {}
                                }

                                if (chat != null) {
                                    for (String field : new String[]{
                                        AutomationResolver.resolve("TLRPC$Chat", "participants_count", AutomationResolver.ResolverType.Field),
                                        "participants_count",
                                        "membersCount"
                                    }) {
                                        try {
                                            savedParticipantsCount = XposedHelpers.getIntField(chat, field);
                                            XposedHelpers.setIntField(chat, field, 1);
                                            patchedChat = chat;
                                            patchedField = field;
                                            break;
                                        } catch (Throwable ignored) {}
                                    }
                                }

                                // ── 2. Patch message date so time-gate check passes (set to now) ─
                                if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                                    Object msgObj = param.args[0];
                                    try {
                                        Object msg = XposedHelpers.getObjectField(msgObj,
                                            AutomationResolver.resolve("MessageObject", "messageOwner", AutomationResolver.ResolverType.Field));
                                        if (msg != null) {
                                            savedMessageDate = XposedHelpers.getIntField(msg, "date");
                                            int now = (int) (System.currentTimeMillis() / 1000L);
                                            XposedHelpers.setIntField(msg, "date", now);
                                            patchedMessage = msg;
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            } catch (Throwable t) { Logger.e(t); }
                        }

                        // ── AFTER: restore the real values ───────────────────────────────────
                        @Override
                        protected void afterMethod(MethodHookParam param) {
                            try {
                                if (patchedChat != null && patchedField != null && savedParticipantsCount >= 0) {
                                    XposedHelpers.setIntField(patchedChat, patchedField, savedParticipantsCount);
                                }
                                if (patchedMessage != null && savedMessageDate >= 0) {
                                    XposedHelpers.setIntField(patchedMessage, "date", savedMessageDate);
                                }
                            } catch (Throwable t) { Logger.e(t); }
                            finally {
                                patchedChat = null; patchedField = null; savedParticipantsCount = -1;
                                patchedMessage = null; savedMessageDate = -1;
                            }
                        }
                    });

                HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.CHAT_ACTIVITY),
                    AutomationResolver.resolve("ChatActivity", "fillMessageMenu", AutomationResolver.ResolverType.Method),
                    args);
            }
        } catch (Throwable e) { Logger.e(e); }
    }
}

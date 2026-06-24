package com.my.televip.features;

import android.view.View;

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
 * UnlockGroupInput — shows the chat input box and attempts to allow sending
 * in groups/channels where you have been muted or where "Send Messages" is
 * restricted for all members.
 *
 * What this CAN do (client-side only):
 *   • Unhide the text input bar that Telegram hides when "you can't write here"
 *   • Bypass the client-side permission checks so the send button stays active
 *   • For soft mutes applied only through Telegram's UI (not server-enforced
 *     bot mutes) the message may actually go through
 *
 * What this CANNOT do:
 *   • Override server-side bans / mutes — the server will reject those sends
 *   • Fake admin permissions — bot commands like /kick still fail server-side
 *
 * Hooks:
 *  1. updateBottomOverlay / showRestrictedPanel — skip painting the "you can't
 *     write" banner, leaving the normal input visible
 *  2. checkSendPermission / canSendMessage / isCanSendMessage — force true/0
 *  3. TLRPC.TL_chatBannedRights — zero out the banned_rights flags so
 *     ChatActivity thinks you have full permissions
 *  4. Scan all ChatActivity methods for send-permission patterns
 */
public class UnlockGroupInput {

    public static boolean isEnable = false;

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;
                hookChatActivityPermissions();
                hookBannedRights();
            }
        } catch (Throwable e) { Logger.e(e); }
    }

    private static void hookChatActivityPermissions() {
        Class<?> chatCls = ClassLoad.getClass(ClassNames.CHAT_ACTIVITY);
        if (chatCls == null) return;

        // ── Methods that SHOW the "you can't send" overlay — block them ──────
        for (String name : new String[]{
            "updateBottomOverlay", "showRestrictedPanel", "showSendRestriction",
            "showMutedPanel", "showCannotWritePanel", "showCantSendMessage",
            "updateRestrictedSend", "onSendRestricted"
        }) {
            try {
                HMethod.hookMethod(chatCls,
                    AutomationResolver.resolve("ChatActivity", name, AutomationResolver.ResolverType.Method),
                    new AbstractMethodHook() {
                        @Override
                        protected void beforeMethod(MethodHookParam param) {
                            if (ConfigManager.unlockGroupInput.isEnable()) param.setResult(null);
                        }
                    });
            } catch (Throwable ignored) {}
        }

        // ── Methods that CHECK if the user can send — force allowed ──────────
        for (String name : new String[]{
            "checkSendPermission", "canSendMessage", "isCanSendMessage",
            "isSendAllowed", "isSendDisabled", "isMuted", "isRestricted",
            "checkSendStickers", "checkSendGifs", "checkSendMedia",
            "checkSendVoice", "isChatSendAllowed"
        }) {
            try {
                HMethod.hookMethod(chatCls,
                    AutomationResolver.resolve("ChatActivity", name, AutomationResolver.ResolverType.Method),
                    new AbstractMethodHook() {
                        @Override
                        protected void afterMethod(MethodHookParam param) {
                            if (!ConfigManager.unlockGroupInput.isEnable()) return;
                            Object r = param.getResult();
                            if (r instanceof Boolean) {
                                // "is muted" / "is restricted" → false; "can send" → true
                                String nm = ((Method) param.method).getName().toLowerCase();
                                boolean isNegative = nm.contains("muted") || nm.contains("restrict")
                                    || nm.contains("disabled") || nm.contains("cant") || nm.contains("cannot");
                                param.setResult(isNegative ? false : true);
                            } else if (r instanceof Integer) {
                                param.setResult(0); // 0 = no restriction
                            }
                        }
                    });
            } catch (Throwable ignored) {}
        }

        // ── Broad scan — any method whose name contains "restrict" / "mute" ──
        try {
            for (Method m : chatCls.getDeclaredMethods()) {
                String lower = m.getName().toLowerCase();
                if (!lower.contains("restrict") && !lower.contains("muted") && !lower.contains("canwrite")
                    && !lower.contains("cansend") && !lower.contains("slowmode")) continue;
                try {
                    HMethod.hookMethod(m, new AbstractMethodHook() {
                        @Override
                        protected void afterMethod(MethodHookParam param) {
                            if (!ConfigManager.unlockGroupInput.isEnable()) return;
                            Object r = param.getResult();
                            if (r instanceof Boolean) param.setResult(false);
                            else if (r instanceof Integer) param.setResult(0);
                            else if (r == null && m.getReturnType() == void.class) {}
                        }
                    });
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    // ── Zero out banned_rights on TLRPC$TL_chatBannedRights ─────────────────
    // Telegram stores the "what you can't do in this chat" flags in a
    // TL_chatBannedRights object.  If we zero the flags field the client
    // believes you have every permission.
    private static void hookBannedRights() {
        for (String cls : new String[]{
            "org.telegram.tgnet.TLRPC$TL_chatBannedRights",
            "org.telegram.tgnet.tl.TL_chats$chatBannedRights"
        }) {
            try {
                Class<?> c = XposedHelpers.findClassIfExists(cls, Utils.classLoader);
                if (c == null) continue;

                // Hook all constructors so newly created objects start clean
                for (java.lang.reflect.Constructor<?> ctor : c.getDeclaredConstructors()) {
                    try {
                        de.robv.android.xposed.XposedBridge.hookMethod(ctor, new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                if (!ConfigManager.unlockGroupInput.isEnable()) return;
                                try { XposedHelpers.setIntField(param.thisObject, "flags", 0); } catch (Throwable ignored) {}
                            }
                        });
                    } catch (Throwable ignored) {}
                }

                // Hook readParams (called when deserialising from server) — zero flags after read
                for (String method : new String[]{"readParams", "readFrom", "deserializeResponse"}) {
                    try {
                        XposedHelpers.findAndHookMethod(c, method, "org.telegram.tgnet.AbstractSerializedData", int.class, boolean.class,
                            new AbstractMethodHook() {
                                @Override
                                protected void afterMethod(MethodHookParam param) {
                                    if (!ConfigManager.unlockGroupInput.isEnable()) return;
                                    try { XposedHelpers.setIntField(param.thisObject, "flags", 0); } catch (Throwable ignored) {}
                                }
                            });
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
    }
}


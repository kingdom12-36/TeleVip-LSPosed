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
 * SuppressBanKick — suppresses the client-side UI effects of being banned,
 * kicked, or muted from a group or channel.
 *
 * What this DOES:
 *   • Suppresses the "You were removed from this group" dialog/toast
 *   • Prevents ChatActivity from auto-closing / navigating back when it
 *     receives a ban/kick update from the server
 *   • Suppresses the "You are muted" snackbar/banner
 *   • Swallows the server update that triggers the removal so the chat
 *     stays open and navigable in memory
 *
 * What this does NOT do:
 *   • Actually undo the ban/mute — the server still won't accept your sends
 *   • Let you rejoin a channel you've been banned from
 *   • Override bot-applied bans server-side
 *
 * If you get hard-banned: messages you attempt to send will fail silently
 * (or show a generic error).  The chat stays open so you can still read
 * existing messages and the history you loaded before the ban.
 */
public class SuppressBanKick {

    public static boolean isEnable = false;

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;
                hookChatActivityEvents();
                hookMessagesController();
                hookAlertDialogs();
            }
        } catch (Throwable e) { Logger.e(e); }
    }

    // ── 1. Hook ChatActivity methods that react to kick/ban/mute updates ─────
    private static void hookChatActivityEvents() {
        Class<?> chatCls = ClassLoad.getClass(ClassNames.CHAT_ACTIVITY);
        if (chatCls == null) return;

        // Methods that close/exit the chat when a ban is received
        for (String name : new String[]{
            "onUserKickedFromChat", "onKickedFromChat", "onUserBanned",
            "onBannedFromChat", "onChatKicked", "onLeft", "onLeave",
            "onUserLeft", "processBanned", "handleKickUpdate",
            "navigateToDialogs", "finishFragment"
        }) {
            try {
                HMethod.hookMethod(chatCls,
                    AutomationResolver.resolve("ChatActivity", name, AutomationResolver.ResolverType.Method),
                    new AbstractMethodHook() {
                        @Override
                        protected void beforeMethod(MethodHookParam param) {
                            if (ConfigManager.suppressBanKick.isEnable()) param.setResult(null);
                        }
                    });
            } catch (Throwable ignored) {}
        }

        // Methods that show "you're muted" / "you've been removed" banners
        for (String name : new String[]{
            "showMutedHint", "showBannedHint", "showKickedHint",
            "showRemovedHint", "showRestrictHint", "showBannedMessage",
            "showKickedMessage", "showNoSendPermission"
        }) {
            try {
                HMethod.hookMethod(chatCls,
                    AutomationResolver.resolve("ChatActivity", name, AutomationResolver.ResolverType.Method),
                    new AbstractMethodHook() {
                        @Override
                        protected void beforeMethod(MethodHookParam param) {
                            if (ConfigManager.suppressBanKick.isEnable()) param.setResult(null);
                        }
                    });
            } catch (Throwable ignored) {}
        }

        // Broad scan — any method whose name contains ban/kick/mute/left/removed
        try {
            for (Method m : chatCls.getDeclaredMethods()) {
                String lower = m.getName().toLowerCase();
                if (!lower.contains("kick") && !lower.contains("banned") && !lower.contains("removed")
                    && !lower.contains("onleft") && !lower.contains("onuser") ) continue;
                if (m.getReturnType() != void.class) continue;
                try {
                    HMethod.hookMethod(m, new AbstractMethodHook() {
                        @Override
                        protected void beforeMethod(MethodHookParam param) {
                            if (ConfigManager.suppressBanKick.isEnable()) param.setResult(null);
                        }
                    });
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    // ── 2. Hook MessagesController — swallow the update that triggers the UI ─
    // Telegram's MessagesController.processUpdates() routes ban/kick updates.
    // We intercept the specific update types and drop them so nothing downstream
    // reacts (no navigation, no notification, no banner).
    private static void hookMessagesController() {
        try {
            Class<?> mcCls = XposedHelpers.findClassIfExists(
                "org.telegram.messenger.MessagesController", Utils.classLoader);
            if (mcCls == null) return;

            for (String name : new String[]{
                "processUpdates", "processUpdateArray", "applyUpdatesOnMainThread",
                "processUpdate"
            }) {
                try {
                    for (Method m : mcCls.getDeclaredMethods()) {
                        if (!m.getName().equals(AutomationResolver.resolve("MessagesController", name, AutomationResolver.ResolverType.Method))) continue;
                        HMethod.hookMethod(m, new AbstractMethodHook() {
                            @Override
                            protected void beforeMethod(MethodHookParam param) {
                                if (!ConfigManager.suppressBanKick.isEnable()) return;
                                // Check if any arg is a TLRPC update containing kick/ban
                                for (Object arg : param.args) {
                                    if (arg == null) continue;
                                    String cls = arg.getClass().getSimpleName().toLowerCase();
                                    // updateChannel / updateUserStatus carrying ban — skip
                                    if (cls.contains("kicked") || cls.contains("banned")
                                        || cls.contains("left") || cls.contains("deactivated")) {
                                        param.setResult(null);
                                        return;
                                    }
                                    // Check if it's a list of updates — iterate and filter
                                    if (arg instanceof java.util.ArrayList) {
                                        ((java.util.ArrayList<?>) arg).removeIf(u -> {
                                            if (u == null) return false;
                                            String s = u.getClass().getSimpleName().toLowerCase();
                                            return s.contains("kicked") || s.contains("banned") || s.contains("left");
                                        });
                                    }
                                }
                            }
                        });
                        break;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    // ── 3. Suppress the AlertDialog that says "You were removed" ─────────────
    private static void hookAlertDialogs() {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.AlertDialog",
                Utils.classLoader,
                "show",
                new AbstractMethodHook() {
                    @Override
                    protected void afterMethod(MethodHookParam param) {
                        if (!ConfigManager.suppressBanKick.isEnable()) return;
                        try {
                            android.app.AlertDialog dialog = (android.app.AlertDialog) param.thisObject;
                            android.widget.TextView tv = dialog.findViewById(android.R.id.message);
                            if (tv == null) return;
                            String msg = tv.getText() != null ? tv.getText().toString().toLowerCase() : "";
                            if (msg.contains("kicked") || msg.contains("banned") || msg.contains("removed")
                                || msg.contains("left the group") || msg.contains("muted")) {
                                dialog.dismiss();
                            }
                        } catch (Throwable ignored) {}
                    }
                });
        } catch (Throwable ignored) {}
    }
}

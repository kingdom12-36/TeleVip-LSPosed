package com.my.televip.features;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;
import com.my.televip.virtuals.messenger.MessageObject;

import de.robv.android.xposed.XposedHelpers;

/**
 * BypassReactionsView — force the "see who reacted" button to always appear.
 *
 * Group admins can restrict reaction viewer lists via the can_see_all_peers flag
 * on TL_messageReactions. This feature hooks ChatMessageCell.setMessageObject()
 * and forces that flag to true before the cell renders, making Telegram's native
 * reactions chip always tappable to reveal reactors.
 */
public class BypassReactionsView {

    public static boolean isEnable = false;

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;

                if (ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL) == null) return;

                // Hook setMessageObject — called every time a message cell is bound
                HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL),
                    AutomationResolver.resolve("ChatMessageCell", "setMessageObject", AutomationResolver.ResolverType.Method),
                    AutomationResolver.merge(
                        AutomationResolver.resolveObject("setMessageObject", new Class[]{
                            ClassLoad.getClass(ClassNames.MESSAGE_OBJECT), boolean.class, boolean.class, boolean.class
                        }),
                        new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                if (!ConfigManager.bypassReactionsView.isEnable()) return;
                                try {
                                    Object msgObjRaw = param.args[0];
                                    if (msgObjRaw == null) return;

                                    MessageObject mo = new MessageObject(msgObjRaw);
                                    if (mo.getMessageOwner() == null) return;

                                    Object reactions = getReactions(mo.getMessageOwner().message);
                                    if (reactions == null) return;

                                    // Force can_see_all_peers = true so the native
                                    // "see who reacted" chip is always tappable
                                    forceCanSeeAllPeers(reactions);
                                } catch (Throwable ignored) {}
                            }
                        }));
            }
        } catch (Throwable e) { Logger.e(e); }
    }

    private static Object getReactions(Object message) {
        try {
            return XposedHelpers.getObjectField(message,
                AutomationResolver.resolve("TLRPC$Message", "reactions", AutomationResolver.ResolverType.Field));
        } catch (Throwable t) {
            try { return XposedHelpers.getObjectField(message, "reactions"); }
            catch (Throwable ignored) { return null; }
        }
    }

    private static void forceCanSeeAllPeers(Object reactions) {
        // Try resolved field name first, then common raw names
        for (String field : new String[]{
            AutomationResolver.resolve("TLRPC$TL_messageReactions", "can_see_all_peers", AutomationResolver.ResolverType.Field),
            "can_see_all_peers",
            "canSeeAllPeers"
        }) {
            try {
                XposedHelpers.setBooleanField(reactions, field, true);
                return;
            } catch (Throwable ignored) {}
        }

        // Fallback: look for any boolean field that starts with "can"
        try {
            for (java.lang.reflect.Field f : reactions.getClass().getFields()) {
                if (f.getType() == boolean.class) {
                    String name = f.getName().toLowerCase();
                    if (name.contains("can") && name.contains("peer")) {
                        f.setAccessible(true);
                        f.setBoolean(reactions, true);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }
}

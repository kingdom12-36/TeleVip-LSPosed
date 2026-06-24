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
 * BypassReactionsView — always show the "who reacted" tappable chip in messages.
 *
 * ROOT CAUSE of breakage: the old code tried to set can_see_all_peers / canSeeAllPeers
 * but the actual field in TLRPC.MessageReactions (since layer 185+) is can_see_list.
 * Setting the wrong field had zero effect — the chip stayed non-tappable.
 *
 * Fix: set can_see_list = true. This is the flag checked by isReactionsViewAvailable:
 *   primaryMessage.messageOwner.reactions.can_see_list
 *
 * Additional fix: the old hook resolved setMessageObject with (MESSAGE_OBJECT, bool, bool, bool)
 * but the actual signature is (MessageObject, GroupedMessages, bool, bool, bool[, bool]).
 * We now scan ALL declared methods named "setMessageObject" and hook every overload.
 */
public class BypassReactionsView {

    public static boolean isEnable = false;

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;

                Class<?> cellClass = ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL);
                if (cellClass == null) return;

                String targetName = AutomationResolver.resolve(
                    "ChatMessageCell", "setMessageObject", AutomationResolver.ResolverType.Method);

                AbstractMethodHook hook = new AbstractMethodHook() {
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

                            forceCanSeeList(reactions);
                        } catch (Throwable ignored) {}
                    }
                };

                // Hook all overloads of setMessageObject — avoids parameter-type mismatches
                int hooked = 0;
                for (java.lang.reflect.Method m : cellClass.getDeclaredMethods()) {
                    if (m.getName().equals(targetName)) {
                        try {
                            HMethod.hookMethod(m, hook);
                            hooked++;
                        } catch (Throwable ignored) {}
                    }
                }
                if (hooked == 0) {
                    // Fallback: hook by name only using XposedHelpers
                    XposedHelpers.findAndHookMethod(cellClass, targetName,
                        ClassLoad.getClass(ClassNames.MESSAGE_OBJECT), hook);
                }
            }
        } catch (Throwable e) { Logger.e(e); }
    }

    private static Object getReactions(Object message) {
        if (message == null) return null;
        try {
            return XposedHelpers.getObjectField(message,
                AutomationResolver.resolve("TLRPC$Message", "reactions",
                    AutomationResolver.ResolverType.Field));
        } catch (Throwable t) {
            try { return XposedHelpers.getObjectField(message, "reactions"); }
            catch (Throwable ignored) { return null; }
        }
    }

    /**
     * Set the field that controls reaction-list visibility.
     * Telegram 12.7+ : can_see_list  (was: can_see_all_peers in older builds)
     */
    private static void forceCanSeeList(Object reactions) {
        for (String field : new String[]{
            "can_see_list",       // Current field name (layer 185+)
            "can_see_all_peers",  // Older builds fallback
            "canSeeAllPeers"      // Obfuscated variant
        }) {
            try {
                XposedHelpers.setBooleanField(reactions, field, true);
                return;
            } catch (Throwable ignored) {}
        }
        // Last resort: flip any boolean field whose name contains "see" or "list"
        try {
            for (java.lang.reflect.Field f : reactions.getClass().getFields()) {
                if (f.getType() == boolean.class) {
                    String n = f.getName().toLowerCase();
                    if ((n.contains("see") || n.contains("list")) && n.contains("can")) {
                        f.setAccessible(true);
                        f.setBoolean(reactions, true);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }
}

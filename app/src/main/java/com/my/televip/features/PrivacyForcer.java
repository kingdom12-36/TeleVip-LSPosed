package com.my.televip.features;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;
import com.my.televip.utils.Utils;

import java.util.ArrayList;

import de.robv.android.xposed.XposedHelpers;

/**
 * PrivacyForcer — auto-enforces maximum privacy for sensitive account settings.
 *
 * Intercepts outgoing TL_account_setPrivacy requests and, for sensitive
 * privacy keys, replaces the rules list with [TL_privacyValueDisallowAll].
 * This ensures phone number, last seen, calls, and group invites are always
 * locked to "nobody" — and works for ALL accounts, not just Telegram Premium
 * (the "hide phone" Premium gate is purely a client-side UI lock).
 *
 * Enforced keys:
 *   • TL_privacyKeyPhoneNumber    — who can see your phone number
 *   • TL_privacyKeyStatusTimestamp — last seen / online status
 *   • TL_privacyKeyPhoneCall      — who can call you via Telegram
 *   • TL_privacyKeyChatInvite     — who can add you to groups/channels
 *   • TL_privacyKeyForwards       — forwarded-messages attribution
 *
 * Hook point: ConnectionsManager.sendRequestInternal — same entry used by GhostMode.
 */
public class PrivacyForcer {

    public static boolean isEnable = false;

    private static final String[] SENSITIVE_KEYS = {
        "TL_privacyKeyPhoneNumber",
        "TL_privacyKeyStatusTimestamp",
        "TL_privacyKeyPhoneCall",
        "TL_privacyKeyChatInvite",
        "TL_privacyKeyForwards",
        "privacyKeyPhoneNumber",
        "privacyKeyStatusTimestamp",
        "privacyKeyPhoneCall",
        "privacyKeyChatInvite",
        "privacyKeyForwards",
    };

    private static final String[] DISALLOW_ALL_CLASSES = {
        "org.telegram.tgnet.TLRPC$TL_privacyValueDisallowAll",
        "org.telegram.tgnet.tl.TL_account$TL_privacyValueDisallowAll",
    };

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;
                if (ClassLoad.getClass(ClassNames.CONNECTIONS_MANAGER) == null) return;

                HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.CONNECTIONS_MANAGER),
                    AutomationResolver.resolve("ConnectionsManager", "sendRequestInternal",
                        AutomationResolver.ResolverType.Method),
                    AutomationResolver.merge(
                        AutomationResolver.resolveObject("sendRequestInternal", new Class[]{
                            ClassLoad.getClass(ClassNames.TL_OBJECT),
                            ClassLoad.getClass(ClassNames.REQUEST_DELEGATE),
                            ClassLoad.getClass(ClassNames.REQUEST_DELEGATE_TIMESTAMP),
                            ClassLoad.getClass(ClassNames.QUICK_ACK_DELEGATE),
                            ClassLoad.getClass(ClassNames.WRITE_TO_SOCKET_DELEGATE),
                            int.class, int.class, int.class, boolean.class, int.class
                        }),
                        new AbstractMethodHook() {
                            @Override
                            protected void beforeMethod(MethodHookParam param) {
                                if (!ConfigManager.privacyForcer.isEnable()) return;
                                try {
                                    Object req = param.args[0];
                                    if (req == null) return;
                                    String cls = req.getClass().getName();
                                    if (!cls.contains("setPrivacy") && !cls.contains("SetPrivacy")) return;
                                    enforceDisallowAll(req);
                                } catch (Throwable ignored) {}
                            }
                        }
                    )
                );
            }
        } catch (Throwable e) { Logger.e(e); }
    }

    private static void enforceDisallowAll(Object req) {
        try {
            Object key = XposedHelpers.getObjectField(req, "key");
            if (key == null) return;
            String keySimple = key.getClass().getSimpleName();

            boolean isSensitive = false;
            for (String k : SENSITIVE_KEYS) {
                if (keySimple.equals(k) || keySimple.contains(k)) { isSensitive = true; break; }
            }
            if (!isSensitive) return;

            // Try to instantiate TL_privacyValueDisallowAll
            Class<?> disallowAll = null;
            for (String name : DISALLOW_ALL_CLASSES) {
                disallowAll = XposedHelpers.findClassIfExists(name, Utils.classLoader);
                if (disallowAll != null) break;
            }
            // Fallback: scan all inner classes of the enclosing TL namespace
            if (disallowAll == null) {
                Class<?> outer = req.getClass().getEnclosingClass();
                if (outer != null) {
                    for (Class<?> inner : outer.getDeclaredClasses()) {
                        String s = inner.getSimpleName();
                        if (s.contains("DisallowAll") || s.contains("disallowAll") || s.contains("privacyValueDisallowAll")) {
                            disallowAll = inner;
                            break;
                        }
                    }
                }
            }

            if (disallowAll != null) {
                Object instance = disallowAll.newInstance();
                ArrayList<Object> rules = new ArrayList<>();
                rules.add(instance);
                XposedHelpers.setObjectField(req, "rules", rules);
            }
        } catch (Throwable ignored) {}
    }
}

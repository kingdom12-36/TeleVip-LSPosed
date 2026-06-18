package com.my.televip.features;

import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;
import com.my.televip.utils.Utils;

import de.robv.android.xposed.XposedHelpers;

public class AntiPhoneCall {

    public static boolean isEnable = false;

    private static final String[] VOIP_CLASSES = {
        "org.telegram.messenger.voip.VoIPService",
        "org.telegram.messenger.voip.VoIPBaseService",
        "org.telegram.messenger.voip.TelegramVoIPService",
        "org.telegram.ui.VoIPFragment",
    };

    private static final String[] RING_METHODS = {
        "startRinging",
        "startRingtoneAndVibration",
        "onReceiveRinging",
        "onIncomingCall",
        "playRingtone",
    };

    private static final String[] DECLINE_METHODS = {
        "declineIncomingCall",
        "hangUp",
        "stopSelf",
        "endCall",
    };

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;

                for (String className : VOIP_CLASSES) {
                    Class<?> clazz = XposedHelpers.findClassIfExists(className, Utils.classLoader);
                    if (clazz == null) continue;

                    boolean hooked = false;
                    for (String method : RING_METHODS) {
                        try {
                            HMethod.hookMethod(clazz, method, new AbstractMethodHook() {
                                @Override
                                protected void beforeMethod(MethodHookParam param) {
                                    if (!ConfigManager.antiPhoneCall.isEnable()) return;
                                    param.setResult(null);
                                    // Try to cleanly discard the call
                                    for (String dm : DECLINE_METHODS) {
                                        try {
                                            XposedHelpers.callMethod(param.thisObject, dm);
                                            return;
                                        } catch (Throwable ignored) {}
                                    }
                                }
                            });
                            hooked = true;
                        } catch (Throwable ignored) {}
                    }

                    if (!hooked) {
                        // Fallback: look for any 0-arg void or boolean method containing "ring"
                        try {
                            for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                                if (m.getName().toLowerCase().contains("ring") && m.getParameterCount() == 0) {
                                    HMethod.hookMethod(m, new AbstractMethodHook() {
                                        @Override
                                        protected void beforeMethod(MethodHookParam param) {
                                            if (ConfigManager.antiPhoneCall.isEnable())
                                                param.setResult(null);
                                        }
                                    });
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable e) { Logger.e(e); }
    }
}

package com.my.televip.features;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;

import java.lang.reflect.Method;
import java.util.ArrayList;

public class NoSponsoredMessages {

    public static boolean isEnable = false;

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;
                if (ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER) == null) return;

                // Block the method that loads sponsored messages from the server
                try {
                    HMethod.hookMethod(
                        ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER),
                        AutomationResolver.resolve("MessagesController", "loadSponsoredMessages",
                            AutomationResolver.ResolverType.Method),
                        new AbstractMethodHook() {
                            @Override
                            protected void beforeMethod(MethodHookParam param) {
                                if (ConfigManager.noSponsoredMessages.isEnable())
                                    param.setResult(null);
                            }
                        });
                } catch (Throwable ignored) {}

                // Block retrieval of the next sponsored message to display
                try {
                    HMethod.hookMethod(
                        ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER),
                        AutomationResolver.resolve("MessagesController", "getSponsoredMessage",
                            AutomationResolver.ResolverType.Method),
                        new AbstractMethodHook() {
                            @Override
                            protected void beforeMethod(MethodHookParam param) {
                                if (ConfigManager.noSponsoredMessages.isEnable())
                                    param.setResult(null);
                            }
                        });
                } catch (Throwable ignored) {}

                // Fallback: find any method accepting a long (dialogId) that mentions "sponsored"
                // by iterating declared methods and matching by name pattern
                try {
                    for (Method m : ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER).getDeclaredMethods()) {
                        String name = m.getName().toLowerCase();
                        if (name.contains("sponsored") && m.getParameterCount() <= 2) {
                            HMethod.hookMethod(m, new AbstractMethodHook() {
                                @Override
                                protected void beforeMethod(MethodHookParam param) {
                                    if (ConfigManager.noSponsoredMessages.isEnable())
                                        param.setResult(null);
                                }
                            });
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable e) { Logger.e(e); }
    }
}

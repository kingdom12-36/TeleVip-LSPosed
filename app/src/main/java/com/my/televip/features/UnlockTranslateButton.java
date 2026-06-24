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
 * UnlockTranslateButton — force-shows the "Translate" option on every message,
 * regardless of language, user language settings, or Premium status.
 *
 * Hooks at three levels:
 *  1. ChatMessageCell / MessageObject — shouldShowTranslate / canTranslate forced true.
 *  2. ChatActivity context menu — translate menu item always visible.
 *  3. TranslatorHelper / TranslateAlert — language-match guard and Premium gate bypassed.
 */
public class UnlockTranslateButton {

    public static boolean isEnable = false;

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;
                hookChatMessageCellTranslate();
                hookChatActivityMenu();
                hookTranslatorHelper();
            }
        } catch (Throwable e) { Logger.e(e); }
    }

    // ── 1. ChatMessageCell / MessageObject — force canTranslate = true ────────
    private static void hookChatMessageCellTranslate() {
        Class<?> cellCls = ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL);
        if (cellCls != null) {
            for (String name : new String[]{
                "shouldShowTranslate", "canTranslate", "isTranslateVisible",
                "showTranslateButton", "needTranslateButton", "isTranslateEnabled",
                "getTranslateVisibility"
            }) {
                try {
                    HMethod.hookMethod(cellCls,
                        AutomationResolver.resolve("ChatMessageCell", name, AutomationResolver.ResolverType.Method),
                        new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                if (ConfigManager.unlockTranslateButton.isEnable())
                                    param.setResult(true);
                            }
                        });
                } catch (Throwable ignored) {}
            }

            // Broad scan on ChatMessageCell for translate booleans
            try {
                for (Method m : cellCls.getDeclaredMethods()) {
                    String lower = m.getName().toLowerCase();
                    if (!lower.contains("translat")) continue;
                    if (m.getReturnType() == boolean.class) {
                        HMethod.hookMethod(m, new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                if (ConfigManager.unlockTranslateButton.isEnable())
                                    param.setResult(true);
                            }
                        });
                    } else if (m.getReturnType() == int.class && m.getParameterCount() == 0) {
                        HMethod.hookMethod(m, new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                if (ConfigManager.unlockTranslateButton.isEnable())
                                    param.setResult(0); // 0 = View.VISIBLE
                            }
                        });
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Also patch MessageObject if present
        for (String cls : new String[]{
            "org.telegram.messenger.MessageObject",
            "org.telegram.ui.Cells.ChatMessageCell"
        }) {
            try {
                Class<?> c = XposedHelpers.findClassIfExists(cls, Utils.classLoader);
                if (c == null) continue;
                for (Method m : c.getDeclaredMethods()) {
                    String lower = m.getName().toLowerCase();
                    if (!lower.contains("translat")) continue;
                    if (m.getReturnType() == boolean.class) {
                        HMethod.hookMethod(m, new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                if (ConfigManager.unlockTranslateButton.isEnable())
                                    param.setResult(true);
                            }
                        });
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    // ── 2. ChatActivity context menu — always include translate action ─────────
    private static void hookChatActivityMenu() {
        Class<?> chatCls = ClassLoad.getClass(ClassNames.CHAT_ACTIVITY);
        if (chatCls == null) return;

        for (String name : new String[]{
            "showTranslateOption", "addTranslateButton", "createTranslateItem",
            "isTranslateOptionVisible", "showTranslateItem", "buildMessageMenu",
            "onCreateContextMenu", "showMessageMenu", "processSelectedOption"
        }) {
            try {
                HMethod.hookMethod(chatCls,
                    AutomationResolver.resolve("ChatActivity", name, AutomationResolver.ResolverType.Method),
                    new AbstractMethodHook() {
                        @Override
                        protected void afterMethod(MethodHookParam param) {
                            if (!ConfigManager.unlockTranslateButton.isEnable()) return;
                            Object r = param.getResult();
                            if (r instanceof Boolean) param.setResult(true);
                            else if (r instanceof Integer) param.setResult(0);
                        }
                    });
            } catch (Throwable ignored) {}
        }

        // Broad scan: any ChatActivity method referencing "translat"
        try {
            for (Method m : chatCls.getDeclaredMethods()) {
                String lower = m.getName().toLowerCase();
                if (!lower.contains("translat")) continue;
                if (m.getReturnType() == boolean.class) {
                    HMethod.hookMethod(m, new AbstractMethodHook() {
                        @Override
                        protected void afterMethod(MethodHookParam param) {
                            if (ConfigManager.unlockTranslateButton.isEnable())
                                param.setResult(true);
                        }
                    });
                } else if (m.getReturnType() == int.class) {
                    HMethod.hookMethod(m, new AbstractMethodHook() {
                        @Override
                        protected void afterMethod(MethodHookParam param) {
                            if (ConfigManager.unlockTranslateButton.isEnable())
                                param.setResult(0);
                        }
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    // ── 3. TranslatorHelper / TranslateAlert — bypass language-match guard ────
    private static void hookTranslatorHelper() {
        for (String cls : new String[]{
            "org.telegram.ui.Components.TranslateAlert",
            "org.telegram.ui.Components.TranslateAlert$TranslatorHelper",
            "org.telegram.messenger.TranslatorHelper",
            "org.telegram.ui.TranslateAlert"
        }) {
            try {
                Class<?> c = XposedHelpers.findClassIfExists(cls, Utils.classLoader);
                if (c == null) continue;

                for (Method m : c.getDeclaredMethods()) {
                    String lower = m.getName().toLowerCase();

                    // "same language?" → always false so translate always runs
                    if ((lower.contains("same") || lower.contains("match") || lower.contains("equal"))
                        && lower.contains("lang") && m.getReturnType() == boolean.class) {
                        HMethod.hookMethod(m, new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                if (ConfigManager.unlockTranslateButton.isEnable())
                                    param.setResult(false);
                            }
                        });
                    }

                    // isPremium checks inside translate flow — force true
                    if (lower.contains("premium") && m.getReturnType() == boolean.class) {
                        HMethod.hookMethod(m, new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                if (ConfigManager.unlockTranslateButton.isEnable())
                                    param.setResult(true);
                            }
                        });
                    }

                    // isTranslateEnabled / canShowTranslate
                    if ((lower.contains("translat") || lower.contains("showtranslate"))
                        && m.getReturnType() == boolean.class) {
                        HMethod.hookMethod(m, new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                if (ConfigManager.unlockTranslateButton.isEnable())
                                    param.setResult(true);
                            }
                        });
                    }
                }
            } catch (Throwable ignored) {}
        }
    }
}

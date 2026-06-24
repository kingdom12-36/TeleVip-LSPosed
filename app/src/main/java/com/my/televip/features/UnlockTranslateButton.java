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
 * Telegram only offers translate on messages written in a language different
 * from your interface language, and it requires Premium for some clients.
 * This hooks the visibility logic at three levels:
 *
 *  1. ChatMessageCell / MessageObject — the shouldShowTranslate() / canTranslate()
 *     checks that decide whether to include "Translate" in the long-press menu.
 *     We force them to return true.
 *
 *  2. ChatActivity context menu builder — methods that assemble the popup menu
 *     for a long-pressed message.  We scan for any method that adds or checks
 *     a "translate" action and make it always visible.
 *
 *  3. TranslateAlert / TranslatorHelper — the class that performs the actual
 *     translation.  We hook the language-match guard so it skips the "same
 *     language, nothing to translate" early-return.
 *
 *  4. Premium paywall hook — Telegram may gate translate behind isPremium();
 *     we force that check to return true for the translate flow only by hooking
 *     the enclosing translate-entry-point method.
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
        // Hook on ChatMessageCell
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
                        XposedHelpers.hookMethod(m, new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                if (ConfigManager.unlockTranslateButton.isEnable())
                                    param.setResult(true);
                            }
                        });
                    } else if (m.getReturnType() == int.class && m.getParameterCount() == 0) {
                        // visibility int: 0 = VISIBLE, 8 = GONE — force VISIBLE
                        XposedHelpers.hookMethod(m, new AbstractMethodHook() {
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

        // Also patch on MessageObject (used when building the action sheet)
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
                        XposedHelpers.hookMethod(m, new AbstractMethodHook() {
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

        // Methods that decide what shows in the long-press popup
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
                            else if (r instanceof Integer) param.setResult(0); // VISIBLE
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
                    XposedHelpers.hookMethod(m, new AbstractMethodHook() {
                        @Override
                        protected void afterMethod(MethodHookParam param) {
                            if (ConfigManager.unlockTranslateButton.isEnable())
                                param.setResult(true);
                        }
                    });
                } else if (m.getReturnType() == int.class) {
                    XposedHelpers.hookMethod(m, new AbstractMethodHook() {
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
    // When you tap Translate, Telegram checks if src-lang == dst-lang and skips.
    // Hook the comparison so it always proceeds.
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

                    // Language-sameness / "nothing to translate" check — always say no
                    if ((lower.contains("same") || lower.contains("match") || lower.contains("equal"))
                        && lower.contains("lang") && m.getReturnType() == boolean.class) {
                        XposedHelpers.hookMethod(m, new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                if (ConfigManager.unlockTranslateButton.isEnable())
                                    param.setResult(false); // "same language?" → no, always translate
                            }
                        });
                    }

                    // isPremium checks inside the translate flow — force true
                    if (lower.contains("premium") && m.getReturnType() == boolean.class) {
                        XposedHelpers.hookMethod(m, new AbstractMethodHook() {
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
                        XposedHelpers.hookMethod(m, new AbstractMethodHook() {
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

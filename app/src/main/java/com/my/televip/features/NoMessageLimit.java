package com.my.televip.features;

import android.text.InputFilter;
import android.widget.EditText;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;
import com.my.televip.utils.Utils;

import de.robv.android.xposed.XposedHelpers;

/**
 * NoMessageLimit — removes Telegram's 4096-character limit on the chat input.
 *
 * Telegram enforces this limit in two places:
 *   1. The chat EditText has an InputFilter.LengthFilter(4096) applied.
 *   2. ChatActivity validates the text length before calling sendMessage().
 *
 * We hook EditText.setFilters() inside the Telegram process and strip any
 * LengthFilter whose limit is ≤ 4096, effectively making the field unlimited.
 * We also hook ChatActivity's character counter / send validation to prevent
 * it from blocking sends.
 *
 * Note: Telegram's server still splits messages that exceed ~4096 chars and
 * sends them as multiple messages.  This feature just removes the client-side
 * blocker so you can type/paste long texts without Telegram cutting you off.
 */
public class NoMessageLimit {

    public static boolean isEnable = false;

    private static final int TG_MAX_LENGTH = 4096;

    public static void init() {
        try {
            if (!isEnable) {
                isEnable = true;

                // ── Hook 1: Strip LengthFilter from any EditText in Telegram ──────
                // This fires whenever Telegram sets the filters on the chat input.
                XposedHelpers.findAndHookMethod(
                    EditText.class,
                    "setFilters",
                    InputFilter[].class,
                    new AbstractMethodHook() {
                        @Override
                        protected void beforeMethod(MethodHookParam param) {
                            if (!ConfigManager.noMessageLimit.isEnable()) return;
                            try {
                                InputFilter[] original = (InputFilter[]) param.args[0];
                                if (original == null || original.length == 0) return;

                                boolean hasLengthFilter = false;
                                for (InputFilter f : original) {
                                    if (f instanceof InputFilter.LengthFilter) {
                                        hasLengthFilter = true;
                                        break;
                                    }
                                }
                                if (!hasLengthFilter) return;

                                // Rebuild the filter array without any LengthFilter
                                java.util.List<InputFilter> kept = new java.util.ArrayList<>();
                                for (InputFilter f : original) {
                                    if (!(f instanceof InputFilter.LengthFilter)) kept.add(f);
                                }
                                param.args[0] = kept.toArray(new InputFilter[0]);
                            } catch (Throwable ignored) {}
                        }
                    });

                // ── Hook 2: Bypass ChatActivity character-limit validation ─────────
                // Some builds have a dedicated method that checks the input length
                // before allowing the send action.  We hook common names for it.
                if (ClassLoad.getClass(ClassNames.CHAT_ACTIVITY) != null) {
                    for (String method : new String[]{
                        "checkTextForErrors",
                        "isSendLengthValid",
                        "isTextTooLong",
                        "checkMessageLength"
                    }) {
                        try {
                            String resolved = AutomationResolver.resolve("ChatActivity", method, AutomationResolver.ResolverType.Method);
                            HMethod.hookMethod(ClassLoad.getClass(ClassNames.CHAT_ACTIVITY), resolved,
                                new AbstractMethodHook() {
                                    @Override
                                    protected void afterMethod(MethodHookParam param) {
                                        if (!ConfigManager.noMessageLimit.isEnable()) return;
                                        // If the method signals "too long", override to allow
                                        if (param.getResult() instanceof Boolean && (boolean) param.getResult()) {
                                            param.setResult(false);
                                        }
                                    }
                                });
                        } catch (Throwable ignored) {}
                    }

                    // ── Hook 3: Neutralise getMaxMessageLength / getTextLimit ──────
                    for (String method : new String[]{
                        "getMaxMessageLength",
                        "getTextLimit",
                        "getMessageMaxLength"
                    }) {
                        try {
                            String resolved = AutomationResolver.resolve("ChatActivity", method, AutomationResolver.ResolverType.Method);
                            HMethod.hookMethod(ClassLoad.getClass(ClassNames.CHAT_ACTIVITY), resolved,
                                new AbstractMethodHook() {
                                    @Override
                                    protected void afterMethod(MethodHookParam param) {
                                        if (!ConfigManager.noMessageLimit.isEnable()) return;
                                        if (param.getResult() instanceof Integer) {
                                            int limit = (int) param.getResult();
                                            if (limit <= TG_MAX_LENGTH) {
                                                param.setResult(Integer.MAX_VALUE);
                                            }
                                        }
                                    }
                                });
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable e) { Logger.e(e); }
    }
}

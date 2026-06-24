package com.my.televip.features;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.Toast;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.language.Keys;
import com.my.televip.language.Translator;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;
import com.my.televip.virtuals.Theme;
import com.my.televip.virtuals.messenger.UserConfig;

import de.robv.android.xposed.XposedHelpers;

/**
 * ShowOthersPhone — reveals locally-cached phone numbers on profile pages.
 *
 * Hook: ProfileActivity.updateProfileData(boolean) afterMethod.
 * We read the phone from the MessagesController.users cache and append it
 * to onlineTextView[1] (the profile header subtitle / status row).
 *
 * Robustness fixes vs older version:
 *  - userId is read directly from the private field (with getArguments() fallback)
 *  - onlineTextView text is set via XposedHelpers.callMethod to avoid SimpleTextView
 *    wrapper issues with ClickableSmallTextView subclass
 *  - Hooks all overloads of updateProfileData via method scan (name match)
 */
public class ShowOthersPhone {

    public static boolean isEnable = false;

    public static void init(Context context) {
        try {
            if (!isEnable) {
                isEnable = true;
                if (ClassLoad.getClass(ClassNames.PROFILE_ACTIVITY) == null) return;

                String targetMethod = AutomationResolver.resolve(
                    "ProfileActivity", "updateProfileData", AutomationResolver.ResolverType.Method);

                AbstractMethodHook hook = new AbstractMethodHook() {
                    @Override
                    protected void afterMethod(MethodHookParam param) {
                        if (!ConfigManager.showOthersPhone.isEnable()) return;
                        try {
                            long userId = resolveUserId(param.thisObject);
                            if (userId <= 0) return;
                            // Don't show for own account
                            if (userId == UserConfig.getSelectedAccount()) return;

                            String phone = getCachedPhone(userId);
                            if (phone == null || phone.isEmpty()) return;

                            // Get onlineTextView array directly — it's a SimpleTextView[4] in 12.8.x
                            Object[] textViews = null;
                            try {
                                textViews = (Object[]) XposedHelpers.getObjectField(param.thisObject,
                                    AutomationResolver.resolve("ProfileActivity", "onlineTextView",
                                        AutomationResolver.ResolverType.Field));
                            } catch (Throwable t) {
                                try { textViews = (Object[]) XposedHelpers.getObjectField(param.thisObject, "onlineTextView"); }
                                catch (Throwable ignored) {}
                            }
                            if (textViews == null || textViews.length < 2 || textViews[1] == null) return;

                            Object stv = textViews[1]; // visible subtitle text view
                            String display = "📞 +" + phone;

                            // Append to existing text (last seen / online / etc.)
                            CharSequence existing = null;
                            try { existing = (CharSequence) XposedHelpers.callMethod(stv, "getText"); }
                            catch (Throwable ignored) {}

                            SpannableStringBuilder sb = new SpannableStringBuilder();
                            if (existing != null && existing.length() > 0)
                                sb.append(existing).append("\n");
                            int start = sb.length();
                            sb.append(display);
                            sb.setSpan(new ForegroundColorSpan(Theme.getTextColor()),
                                start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                            // setText via reflection — works with any SimpleTextView subclass
                            try { XposedHelpers.callMethod(stv, "setText", sb, true); }
                            catch (Throwable t) {
                                try { XposedHelpers.callMethod(stv, "setText", (CharSequence) sb); }
                                catch (Throwable ignored) {}
                            }

                            // Tap to copy
                            final String fullPhone = "+" + phone;
                            try {
                                ((android.view.View) stv).setOnClickListener(v -> {
                                    ClipboardManager cm =
                                        (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("phone", fullPhone));
                                    Toast.makeText(context,
                                        Translator.get(Keys.Copied, fullPhone), Toast.LENGTH_SHORT).show();
                                });
                            } catch (Throwable ignored) {}
                        } catch (Throwable t) { Logger.e(t); }
                    }
                };

                // Hook ALL overloads of updateProfileData (name scan)
                boolean hooked = false;
                for (java.lang.reflect.Method m :
                        ClassLoad.getClass(ClassNames.PROFILE_ACTIVITY).getDeclaredMethods()) {
                    if (m.getName().equals(targetMethod)) {
                        try { HMethod.hookMethod(m, hook); hooked = true; }
                        catch (Throwable ignored) {}
                    }
                }
                if (!hooked) {
                    HMethod.hookMethod(
                        ClassLoad.getClass(ClassNames.PROFILE_ACTIVITY), targetMethod, hook);
                }
            }
        } catch (Throwable e) { Logger.e(e); }
    }

    /**
     * Get the userId being displayed on this ProfileActivity.
     * Tries the private field first, then the arguments Bundle as fallback.
     */
    private static long resolveUserId(Object profileActivity) {
        // 1. Direct field (private long userId at line 456 in current Telegram source)
        for (String field : new String[]{
            AutomationResolver.resolve("ProfileActivity", "userId", AutomationResolver.ResolverType.Field),
            "userId", "user_id"
        }) {
            try { return XposedHelpers.getLongField(profileActivity, field); }
            catch (Throwable ignored) {}
            try { return (long) XposedHelpers.getIntField(profileActivity, field); }
            catch (Throwable ignored) {}
        }
        // 2. Arguments bundle fallback
        try {
            Bundle args = (Bundle) XposedHelpers.callMethod(profileActivity, "getArguments");
            if (args != null) return args.getLong("user_id", 0);
        } catch (Throwable ignored) {}
        return 0;
    }

    private static String getCachedPhone(long userId) {
        try {
            int account = UserConfig.getSelectedAccount();
            Object mc = XposedHelpers.callStaticMethod(
                ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER),
                AutomationResolver.resolve("MessagesController", "getInstance",
                    AutomationResolver.ResolverType.Method),
                account);
            if (mc == null) return null;

            Object users;
            try {
                users = XposedHelpers.getObjectField(mc,
                    AutomationResolver.resolve("MessagesController", "users",
                        AutomationResolver.ResolverType.Field));
            } catch (Throwable t) {
                users = XposedHelpers.getObjectField(mc, "users");
            }
            if (users == null) return null;

            Object user = ((android.util.LongSparseArray<?>) users).get(userId);
            if (user == null) return null;

            for (String field : new String[]{
                AutomationResolver.resolve("User", "phone", AutomationResolver.ResolverType.Field),
                "phone"
            }) {
                try { return (String) XposedHelpers.getObjectField(user, field); }
                catch (Throwable ignored) {}
            }
            return null;
        } catch (Throwable t) { Logger.e(t); return null; }
    }
}

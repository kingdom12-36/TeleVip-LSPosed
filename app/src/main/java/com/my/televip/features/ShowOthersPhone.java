package com.my.televip.features;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
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
import com.my.televip.virtuals.ActionBar.SimpleTextView;
import com.my.televip.virtuals.Theme;
import com.my.televip.virtuals.messenger.UserConfig;
import com.my.televip.virtuals.ui.ProfileActivity;

import de.robv.android.xposed.XposedHelpers;

/**
 * ShowOthersPhone — reveals cached phone numbers on user profile pages.
 *
 * Telegram stores the phone number locally if the user is in your contacts or
 * if you have recently interacted. The number is shown below the last-seen text
 * and can be tapped to copy it.
 */
public class ShowOthersPhone {

    public static boolean isEnable = false;

    public static void init(Context context) {
        try {
            if (!isEnable) {
                isEnable = true;
                HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.PROFILE_ACTIVITY),
                    AutomationResolver.resolve("ProfileActivity", "updateProfileData", AutomationResolver.ResolverType.Method),
                    AutomationResolver.merge(
                        AutomationResolver.resolveObject("updateProfileData", new Class[]{boolean.class}),
                        new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                if (!ConfigManager.showOthersPhone.isEnable()) return;
                                try {
                                    ProfileActivity profile = new ProfileActivity(param.thisObject);
                                    long userId = profile.getUserId();
                                    if (userId <= 0) return;

                                    String phone = getCachedPhone(userId);
                                    if (phone == null || phone.isEmpty()) return;

                                    Object[] arr = profile.getOnlineTextView();
                                    if (arr == null || arr.length < 2) return;

                                    SimpleTextView stv = new SimpleTextView(arr[1]);
                                    if (stv.getSimpleTextView() == null) return;

                                    CharSequence existing = stv.getText();
                                    SpannableStringBuilder sb = new SpannableStringBuilder();
                                    if (existing != null) sb.append(existing).append("\n");

                                    int start = sb.length();
                                    String display = "📞 +" + phone;
                                    sb.append(display);
                                    sb.setSpan(new RelativeSizeSpan(1.0f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                    sb.setSpan(new ForegroundColorSpan(Theme.getTextColor()), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                                    stv.setMaxLines(3);
                                    stv.setText(sb, true);
                                    stv.getSimpleTextView().setOnClickListener(v -> {
                                        String full = "+" + phone;
                                        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                                        cm.setPrimaryClip(ClipData.newPlainText("phone", full));
                                        Toast.makeText(context, Translator.get(Keys.Copied, full), Toast.LENGTH_SHORT).show();
                                    });
                                } catch (Throwable t) { Logger.e(t); }
                            }
                        }));
            }
        } catch (Throwable e) { Logger.e(e); }
    }

    private static String getCachedPhone(long userId) {
        try {
            int account = UserConfig.getSelectedAccount();
            Object mc = XposedHelpers.callStaticMethod(
                ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER),
                AutomationResolver.resolve("MessagesController", "getInstance", AutomationResolver.ResolverType.Method),
                account);
            if (mc == null) return null;

            Object users;
            try {
                users = XposedHelpers.getObjectField(mc,
                    AutomationResolver.resolve("MessagesController", "users", AutomationResolver.ResolverType.Field));
            } catch (Throwable t) {
                users = XposedHelpers.getObjectField(mc, "users");
            }
            if (users == null) return null;

            Object user = ((android.util.LongSparseArray<?>) users).get(userId);
            if (user == null) return null;

            try {
                return (String) XposedHelpers.getObjectField(user,
                    AutomationResolver.resolve("User", "phone", AutomationResolver.ResolverType.Field));
            } catch (Throwable t) {
                return (String) XposedHelpers.getObjectField(user, "phone");
            }
        } catch (Throwable t) {
            Logger.e(t);
            return null;
        }
    }
}

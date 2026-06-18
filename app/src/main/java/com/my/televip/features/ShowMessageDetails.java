package com.my.televip.features;

import android.content.Context;
import android.widget.ScrollView;
import android.widget.TextView;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.ClientChecker;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.language.Keys;
import com.my.televip.language.Translator;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;
import com.my.televip.virtuals.ActionBar.AlertDialog;
import com.my.televip.virtuals.SettingsIconResolver;
import com.my.televip.virtuals.Theme;
import com.my.televip.virtuals.messenger.MessageObject;
import com.my.televip.virtuals.tgnet.TLRPC;
import com.my.televip.virtuals.ui.ChatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XposedHelpers;

public class ShowMessageDetails {

    public static boolean isEnable = false;
    private static final int OPTION_ID = 8354001;

    public static void init(Context context) {
        try {
            if (!isEnable) {
                isEnable = true;

                if (ClassLoad.getClass(ClassNames.CHAT_ACTIVITY) == null) return;

                // Add "Message Details" to the long-press context menu
                HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.CHAT_ACTIVITY),
                    AutomationResolver.resolve("ChatActivity", "fillMessageMenu", AutomationResolver.ResolverType.Method),
                    AutomationResolver.merge(
                        AutomationResolver.resolveObject("fillMessageMenu", new Class[]{
                            ClassLoad.getClass(ClassNames.MESSAGE_OBJECT),
                            ArrayList.class, ArrayList.class, ArrayList.class
                        }),
                        new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                if (!ConfigManager.showMessageDetails.isEnable()) return;
                                try {
                                    ChatActivity chatActivity = new ChatActivity(param.thisObject);
                                    MessageObject messageObject = chatActivity.getSelectedObject();
                                    if (messageObject == null || messageObject.getMessageOwner() == null) return;

                                    ArrayList<Integer> icons;
                                    ArrayList<CharSequence> items;
                                    ArrayList<Integer> options;

                                    if (ClientChecker.check(ClientChecker.ClientType.Telegraph)) {
                                        icons   = (ArrayList<Integer>)     param.args[2];
                                        items   = (ArrayList<CharSequence>) param.args[3];
                                        options = (ArrayList<Integer>)     param.args[4];
                                    } else {
                                        icons   = (ArrayList<Integer>)     param.args[1];
                                        items   = (ArrayList<CharSequence>) param.args[2];
                                        options = (ArrayList<Integer>)     param.args[3];
                                    }

                                    items.add(Translator.get(Keys.MessageDetails));
                                    options.add(OPTION_ID);
                                    if (!ClientChecker.check(ClientChecker.ClientType.Nagram))
                                        icons.add(SettingsIconResolver.getIconSettings());

                                } catch (Throwable t) { Logger.e(t); }
                            }
                        }));

                // Handle the click on "Message Details"
                HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.CHAT_ACTIVITY),
                    AutomationResolver.resolve("ChatActivity", "processSelectedOption", AutomationResolver.ResolverType.Method),
                    AutomationResolver.merge(
                        AutomationResolver.resolveObject("processSelectedOption", new Class[]{int.class}),
                        new AbstractMethodHook() {
                            @Override
                            protected void beforeMethod(MethodHookParam param) {
                                if (!ConfigManager.showMessageDetails.isEnable()) return;
                                int option = (int) param.args[0];
                                if (option != OPTION_ID) return;
                                try {
                                    param.setResult(null);
                                    ChatActivity chatActivity = new ChatActivity(param.thisObject);
                                    MessageObject messageObject = chatActivity.getSelectedObject();
                                    if (messageObject == null) return;
                                    TLRPC.Message message = messageObject.getMessageOwner();
                                    if (message == null) return;
                                    showDetailsDialog(context, message);
                                } catch (Throwable t) { Logger.e(t); }
                            }
                        }));
            }
        } catch (Throwable e) { Logger.e(e); }
    }

    /** Try AutomationResolver first, fall back to direct field name. */
    private static int getIntSafe(Object obj, String fieldName) {
        try {
            return XposedHelpers.getIntField(obj,
                AutomationResolver.resolve("TLRPC$Message", fieldName, AutomationResolver.ResolverType.Field));
        } catch (Throwable t) {
            try { return XposedHelpers.getIntField(obj, fieldName); }
            catch (Throwable ignored) { return 0; }
        }
    }

    private static void showDetailsDialog(Context context, TLRPC.Message message) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault());
            StringBuilder sb = new StringBuilder();

            // Message ID
            int id = message.getID();
            if (id != 0) {
                sb.append(Translator.get(Keys.MessageID)).append(":  ").append(id).append("\n\n");
            }

            // Send time
            int date = getIntSafe(message.message, "date");
            if (date != 0) {
                sb.append(Translator.get(Keys.MessageSentAt)).append(":  ")
                  .append(sdf.format(new Date((long) date * 1000L))).append("\n\n");
            }

            // Edit time
            int editDate = getIntSafe(message.message, "edit_date");
            if (editDate != 0) {
                sb.append(Translator.get(Keys.MessageEditedAt)).append(":  ")
                  .append(sdf.format(new Date((long) editDate * 1000L))).append("\n\n");
            }

            // Views (channels)
            int views = getIntSafe(message.message, "views");
            if (views > 0) {
                sb.append(Translator.get(Keys.MessageViews)).append(":  ").append(views).append("\n\n");
            }

            // Forwards
            int forwards = getIntSafe(message.message, "forwards");
            if (forwards > 0) {
                sb.append(Translator.get(Keys.MessageForwards)).append(":  ").append(forwards).append("\n\n");
            }

            String text = sb.toString().trim();
            if (text.isEmpty()) return;

            AlertDialog dialog = new AlertDialog(context);
            dialog.setTitle(Translator.get(Keys.MessageDetails));

            TextView tv = new TextView(context);
            tv.setText(text);
            tv.setPadding(48, 32, 48, 32);
            tv.setTextSize(15f);
            tv.setTextColor(Theme.getTextColor());
            tv.setLineSpacing(6f, 1.2f);
            tv.setTextIsSelectable(true);

            ScrollView sv = new ScrollView(context);
            sv.addView(tv);
            dialog.setView(sv);
            dialog.setPositiveButton(Translator.get(Keys.Done), null);
            dialog.show();

        } catch (Throwable t) { Logger.e(t); }
    }
}

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
import com.my.televip.virtuals.messenger.UserConfig;
import com.my.televip.virtuals.tgnet.TLRPC;
import com.my.televip.virtuals.ui.ChatActivity;

import java.util.ArrayList;

import de.robv.android.xposed.XposedHelpers;

public class ShowReactionsList {

    public static boolean isEnable = false;
    private static final int OPTION_ID = 8354002;

    public static void init(Context context) {
        try {
            if (!isEnable) {
                isEnable = true;
                if (ClassLoad.getClass(ClassNames.CHAT_ACTIVITY) == null) return;

                HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.CHAT_ACTIVITY),
                    AutomationResolver.resolve("ChatActivity", "fillMessageMenu",
                        AutomationResolver.ResolverType.Method),
                    AutomationResolver.merge(
                        AutomationResolver.resolveObject("fillMessageMenu", new Class[]{
                            ClassLoad.getClass(ClassNames.MESSAGE_OBJECT),
                            ArrayList.class, ArrayList.class, ArrayList.class
                        }),
                        new AbstractMethodHook() {
                            @Override
                            protected void afterMethod(MethodHookParam param) {
                                if (!ConfigManager.showReactionsList.isEnable()) return;
                                try {
                                    // selectedObject field — direct access avoids wrapper failures
                                    Object msgObjRaw = null;
                                    try {
                                        msgObjRaw = XposedHelpers.getObjectField(param.thisObject,
                                            AutomationResolver.resolve("ChatActivity", "selectedObject",
                                                AutomationResolver.ResolverType.Field));
                                    } catch (Throwable t) {
                                        try { msgObjRaw = XposedHelpers.getObjectField(param.thisObject, "selectedObject"); }
                                        catch (Throwable ignored) {}
                                    }
                                    if (msgObjRaw == null) return;

                                    MessageObject mo = new MessageObject(msgObjRaw);
                                    if (mo.getMessageOwner() == null) return;
                                    if (!hasReactions(mo.getMessageOwner())) return;

                                    // args: [0]=primaryMessage, [1]=icons, [2]=items, [3]=options
                                    // Telegraph adds one extra leading arg — shift indices
                                    int base = ClientChecker.check(ClientChecker.ClientType.Telegraph) ? 1 : 0;
                                    ArrayList<Integer>     icons   = (ArrayList<Integer>)     param.args[base + 1];
                                    ArrayList<CharSequence> items  = (ArrayList<CharSequence>) param.args[base + 2];
                                    ArrayList<Integer>     options = (ArrayList<Integer>)     param.args[base + 3];

                                    items.add(Translator.get(Keys.ShowReactionsList));
                                    options.add(OPTION_ID);
                                    if (!ClientChecker.check(ClientChecker.ClientType.Nagram))
                                        icons.add(SettingsIconResolver.getIconSettings());
                                } catch (Throwable t) { Logger.e(t); }
                            }
                        }));

                HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.CHAT_ACTIVITY),
                    AutomationResolver.resolve("ChatActivity", "processSelectedOption",
                        AutomationResolver.ResolverType.Method),
                    AutomationResolver.merge(
                        AutomationResolver.resolveObject("processSelectedOption", new Class[]{int.class}),
                        new AbstractMethodHook() {
                            @Override
                            protected void beforeMethod(MethodHookParam param) {
                                if (!ConfigManager.showReactionsList.isEnable()) return;
                                if ((int) param.args[0] != OPTION_ID) return;
                                try {
                                    param.setResult(null);
                                    Object msgObjRaw = null;
                                    try {
                                        msgObjRaw = XposedHelpers.getObjectField(param.thisObject,
                                            AutomationResolver.resolve("ChatActivity", "selectedObject",
                                                AutomationResolver.ResolverType.Field));
                                    } catch (Throwable t) {
                                        try { msgObjRaw = XposedHelpers.getObjectField(param.thisObject, "selectedObject"); }
                                        catch (Throwable ignored) {}
                                    }
                                    if (msgObjRaw == null) return;

                                    MessageObject mo = new MessageObject(msgObjRaw);
                                    TLRPC.Message msg = mo.getMessageOwner();
                                    if (msg == null) return;
                                    showReactionsDialog(context, msg);
                                } catch (Throwable t) { Logger.e(t); }
                            }
                        }));
            }
        } catch (Throwable e) { Logger.e(e); }
    }

    private static boolean hasReactions(TLRPC.Message message) {
        try {
            Object r = getReactions(message);
            if (r == null) return false;
            // results field holds the reaction counts
            ArrayList<?> results = (ArrayList<?>) XposedHelpers.getObjectField(r, "results");
            return results != null && !results.isEmpty();
        } catch (Throwable ignored) { return false; }
    }

    private static Object getReactions(TLRPC.Message message) {
        try {
            return XposedHelpers.getObjectField(message.message,
                AutomationResolver.resolve("TLRPC$Message", "reactions",
                    AutomationResolver.ResolverType.Field));
        } catch (Throwable t) {
            try { return XposedHelpers.getObjectField(message.message, "reactions"); }
            catch (Throwable ignored) { return null; }
        }
    }

    private static String getUserName(long userId) {
        try {
            Object mc = XposedHelpers.callStaticMethod(
                ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER),
                AutomationResolver.resolve("MessagesController", "getInstance",
                    AutomationResolver.ResolverType.Method),
                UserConfig.getSelectedAccount());
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

            String firstName = "", lastName = "", username = "";
            try { firstName = (String) XposedHelpers.getObjectField(user, "first_name"); } catch (Throwable ignored) {}
            try { lastName  = (String) XposedHelpers.getObjectField(user, "last_name");  } catch (Throwable ignored) {}
            try { username  = (String) XposedHelpers.getObjectField(user, "username");   } catch (Throwable ignored) {}

            StringBuilder name = new StringBuilder();
            if (firstName != null && !firstName.isEmpty()) name.append(firstName.trim());
            if (lastName  != null && !lastName.isEmpty())  { if (name.length() > 0) name.append(" "); name.append(lastName.trim()); }
            if (username  != null && !username.isEmpty())   name.append(" (@").append(username.trim()).append(")");
            return name.length() > 0 ? name.toString().trim() : null;
        } catch (Throwable t) { Logger.e(t); return null; }
    }

    private static void showReactionsDialog(Context context, TLRPC.Message message) {
        try {
            Object reactions = getReactions(message);
            if (reactions == null) return;

            StringBuilder sb = new StringBuilder();

            // Reaction counts
            try {
                ArrayList<Object> results = (ArrayList<Object>) XposedHelpers.getObjectField(reactions, "results");
                if (results != null) {
                    for (Object rc : results) {
                        try {
                            int count = XposedHelpers.getIntField(rc, "count");
                            String emoji = extractEmoticon(rc);
                            if (!emoji.isEmpty()) sb.append(emoji).append("  ×  ").append(count).append("\n");
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}

            // Recent reactors — ROOT CAUSE FIX: field is recent_reactions not recentReactions
            try {
                ArrayList<Object> recent = null;
                // Try current field name first, then older name
                for (String field : new String[]{"recent_reactions", "recentReactions"}) {
                    try {
                        recent = (ArrayList<Object>) XposedHelpers.getObjectField(reactions, field);
                        if (recent != null) break;
                    } catch (Throwable ignored) {}
                }
                if (recent != null && !recent.isEmpty()) {
                    if (sb.length() > 0) sb.append("\n─────────────\n");
                    for (Object pr : recent) {
                        try {
                            String emoji = "";
                            try {
                                Object reactionObj = XposedHelpers.getObjectField(pr, "reaction");
                                emoji = (String) XposedHelpers.getObjectField(reactionObj, "emoticon");
                            } catch (Throwable ignored) {}

                            Object peer = null;
                            try { peer = XposedHelpers.getObjectField(pr, "peer_id"); } catch (Throwable ignored) {}
                            if (peer == null) try { peer = XposedHelpers.getObjectField(pr, "peer"); } catch (Throwable ignored) {}

                            String who = "?";
                            if (peer != null) {
                                long uid = 0, cid = 0, gid = 0;
                                try { uid = XposedHelpers.getLongField(peer, "user_id");    } catch (Throwable ignored) {}
                                try { cid = XposedHelpers.getLongField(peer, "channel_id"); } catch (Throwable ignored) {}
                                try { gid = XposedHelpers.getLongField(peer, "chat_id");    } catch (Throwable ignored) {}
                                if (uid != 0) { String n = getUserName(uid); who = n != null ? n : "User " + uid; }
                                else if (cid != 0) who = "Channel " + cid;
                                else if (gid != 0) who = "Group " + gid;
                            }
                            sb.append(emoji.isEmpty() ? "•" : emoji).append("  ").append(who).append("\n");
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}

            String text = sb.toString().trim();
            if (text.isEmpty()) text = Translator.get(Keys.NoReactionsAvailable);

            AlertDialog dialog = new AlertDialog(context);
            dialog.setTitle(Translator.get(Keys.ShowReactionsList));
            TextView tv = new TextView(context);
            tv.setText(text);
            tv.setPadding(48, 32, 48, 32);
            tv.setTextSize(15f);
            tv.setTextColor(Theme.getTextColor());
            tv.setLineSpacing(4f, 1.3f);
            tv.setTextIsSelectable(true);
            ScrollView sv = new ScrollView(context);
            sv.addView(tv);
            dialog.setView(sv);
            dialog.setPositiveButton(Translator.get(Keys.Done), null);
            dialog.show();
        } catch (Throwable t) { Logger.e(t); }
    }

    private static String extractEmoticon(Object obj) {
        try {
            Object reaction = XposedHelpers.getObjectField(obj, "reaction");
            return (String) XposedHelpers.getObjectField(reaction, "emoticon");
        } catch (Throwable ignored) {}
        try { return (String) XposedHelpers.getObjectField(obj, "emoticon"); } catch (Throwable ignored) {}
        return "";
    }
}

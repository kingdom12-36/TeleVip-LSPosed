package com.my.televip.features;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

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
import com.my.televip.virtuals.SettingsIconResolver;
import com.my.televip.virtuals.messenger.MessageObject;
import com.my.televip.virtuals.messenger.UserConfig;
import com.my.televip.virtuals.ui.ChatActivity;

import java.util.ArrayList;

import de.robv.android.xposed.XposedHelpers;

/**
 * SaveToSavedMessages — silently forwards any message to your Saved Messages,
 * bypassing no-forward (protected content) restrictions.
 *
 * Works by:
 *  1. Temporarily clearing the noforwards flag on the source chat object.
 *  2. Calling MessagesController.forwardMessages() targeting the current user's
 *     own dialog (Telegram's "Saved Messages" peer).
 *  3. Restoring the flag afterwards.
 */
public class SaveToSavedMessages {

    public static boolean isEnable = false;
    private static final int OPTION_ID = 8354020;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    public static void init(Context context) {
        try {
            if (!isEnable) {
                isEnable = true;
                if (ClassLoad.getClass(ClassNames.CHAT_ACTIVITY) == null) return;

                // ── Add menu item ─────────────────────────────────────────────
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
                                if (!ConfigManager.saveToSavedMessages.isEnable()) return;
                                try {
                                    ChatActivity chat = new ChatActivity(param.thisObject);
                                    MessageObject mo = chat.getSelectedObject();
                                    if (mo == null || mo.getMessageOwner() == null) return;

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
                                    items.add(Translator.get(Keys.SaveToSavedMessages));
                                    options.add(OPTION_ID);
                                    if (!ClientChecker.check(ClientChecker.ClientType.Nagram))
                                        icons.add(SettingsIconResolver.getIconSettings());
                                } catch (Throwable t) { Logger.e(t); }
                            }
                        }));

                // ── Handle click ─────────────────────────────────────────────
                HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.CHAT_ACTIVITY),
                    AutomationResolver.resolve("ChatActivity", "processSelectedOption", AutomationResolver.ResolverType.Method),
                    AutomationResolver.merge(
                        AutomationResolver.resolveObject("processSelectedOption", new Class[]{int.class}),
                        new AbstractMethodHook() {
                            @Override
                            protected void beforeMethod(MethodHookParam param) {
                                if (!ConfigManager.saveToSavedMessages.isEnable()) return;
                                if ((int) param.args[0] != OPTION_ID) return;
                                try {
                                    param.setResult(null);
                                    ChatActivity chat = new ChatActivity(param.thisObject);
                                    MessageObject mo = chat.getSelectedObject();
                                    if (mo == null) return;
                                    forwardToSaved(context, param.thisObject, mo);
                                } catch (Throwable t) { Logger.e(t); }
                            }
                        }));
            }
        } catch (Throwable e) { Logger.e(e); }
    }

    private static void forwardToSaved(Context ctx, Object chatActivityObj, MessageObject mo) {
        try {
            int account = UserConfig.getSelectedAccount();

            // Saved Messages = own user ID as a positive dialog id
            long selfId = getSelfId(chatActivityObj, account);
            if (selfId <= 0) {
                showToast(ctx, "❌ " + Translator.get(Keys.SaveToSavedMessagesFail));
                return;
            }

            // Source dialog id
            long fromDialogId = 0;
            for (String field : new String[]{"dialog_id", "dialogId"}) {
                try {
                    fromDialogId = XposedHelpers.getLongField(chatActivityObj,
                        AutomationResolver.resolve("ChatActivity", field, AutomationResolver.ResolverType.Field));
                    if (fromDialogId != 0) break;
                } catch (Throwable ignored) {
                    try { fromDialogId = XposedHelpers.getLongField(chatActivityObj, field); if (fromDialogId != 0) break; }
                    catch (Throwable ignored2) {}
                }
            }
            // Fallback: use MessageObject's own dialog id
            if (fromDialogId == 0) {
                try { fromDialogId = mo.getDialogId(); } catch (Throwable ignored) {}
            }

            // Message id list
            int msgId = mo.getMessageOwner().getID();
            ArrayList<Integer> ids = new ArrayList<>();
            ids.add(msgId);

            // ── Temporarily strip noforwards from source chat ──────────────
            Object currentChat = null;
            Boolean savedNoForwards = null;
            try {
                currentChat = XposedHelpers.getObjectField(chatActivityObj,
                    AutomationResolver.resolve("ChatActivity", "currentChat", AutomationResolver.ResolverType.Field));
            } catch (Throwable ignored) {
                try { currentChat = XposedHelpers.getObjectField(chatActivityObj, "currentChat"); } catch (Throwable ignored2) {}
            }
            if (currentChat != null) {
                for (String field : new String[]{
                    AutomationResolver.resolve("TLRPC$Chat", "noforwards", AutomationResolver.ResolverType.Field),
                    "noforwards"
                }) {
                    try {
                        savedNoForwards = XposedHelpers.getBooleanField(currentChat, field);
                        XposedHelpers.setBooleanField(currentChat, field, false);
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            // ── Call MessagesController.forwardMessages ────────────────────
            Object mc = XposedHelpers.callStaticMethod(
                ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER),
                AutomationResolver.resolve("MessagesController", "getInstance", AutomationResolver.ResolverType.Method),
                account);

            boolean forwarded = false;
            String fwdMethod = AutomationResolver.resolve("MessagesController", "forwardMessages", AutomationResolver.ResolverType.Method);

            // Try signatures from most to least specific (Telegram versions differ)
            for (Object[] args : new Object[][]{
                // (toDid, fromDid, msgIds, messages, replyMsg, notify, scheduleDate)
                {selfId, fromDialogId, ids, null, null, true, 0},
                // (toDid, fromDid, msgIds, messages, notify, scheduleDate)
                {selfId, fromDialogId, ids, null, true, 0},
                // (toDid, fromDid, msgIds, notify, scheduleDate)
                {selfId, fromDialogId, ids, true, 0},
                // (toDid, fromDid, msgIds)
                {selfId, fromDialogId, ids},
            }) {
                try {
                    XposedHelpers.callMethod(mc, fwdMethod, args);
                    forwarded = true;
                    break;
                } catch (Throwable ignored) {}
            }
            if (!forwarded) {
                // Raw fallback without AutomationResolver alias
                for (Object[] args : new Object[][]{
                    {"forwardMessages", selfId, fromDialogId, ids, null, null, true, 0},
                    {"forwardMessages", selfId, fromDialogId, ids, null, true, 0},
                    {"forwardMessages", selfId, fromDialogId, ids, true, 0},
                }) {
                    try {
                        String m = (String) args[0];
                        Object[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
                        XposedHelpers.callMethod(mc, m, rest);
                        forwarded = true;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            // ── Restore noforwards flag ───────────────────────────────────
            if (currentChat != null && savedNoForwards != null) {
                final Object chat = currentChat;
                final boolean orig = savedNoForwards;
                for (String field : new String[]{
                    AutomationResolver.resolve("TLRPC$Chat", "noforwards", AutomationResolver.ResolverType.Field),
                    "noforwards"
                }) {
                    try { XposedHelpers.setBooleanField(chat, field, orig); break; }
                    catch (Throwable ignored) {}
                }
            }

            showToast(ctx, forwarded
                ? "✅ " + Translator.get(Keys.SaveToSavedMessagesOk)
                : "❌ " + Translator.get(Keys.SaveToSavedMessagesFail));

        } catch (Throwable e) {
            Logger.e(e);
            showToast(ctx, "❌ " + Translator.get(Keys.SaveToSavedMessagesFail));
        }
    }

    private static long getSelfId(Object chatActivityObj, int account) {
        // 1. Get from UserConfig
        try {
            Object uc = XposedHelpers.callStaticMethod(
                ClassLoad.getClass(ClassNames.USER_CONFIG),
                AutomationResolver.resolve("UserConfig", "getInstance", AutomationResolver.ResolverType.Method),
                account);
            if (uc != null) {
                return XposedHelpers.getLongField(uc,
                    AutomationResolver.resolve("UserConfig", "clientUserId", AutomationResolver.ResolverType.Field));
            }
        } catch (Throwable ignored) {}
        // 2. Fallback: getClientUserId via BaseFragment
        try {
            return (long) XposedHelpers.callMethod(
                XposedHelpers.callMethod(chatActivityObj, "getUserConfig"),
                "getClientUserId");
        } catch (Throwable ignored) {}
        return 0;
    }

    private static void showToast(Context ctx, String msg) {
        UI.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
    }
}

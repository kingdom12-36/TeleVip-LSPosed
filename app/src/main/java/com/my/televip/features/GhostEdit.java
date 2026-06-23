package com.my.televip.features;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
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
import com.my.televip.utils.Utils;
import com.my.televip.virtuals.SettingsIconResolver;
import com.my.televip.virtuals.messenger.UserConfig;
import com.my.televip.virtuals.tgnet.TLRPC;
import com.my.televip.virtuals.ui.ChatActivity;

import java.util.ArrayList;

import de.robv.android.xposed.XposedHelpers;

/**
 * GhostEdit — edit your own messages without leaving the "Edited" stamp.
 *
 * Instead of using Telegram's normal edit API (which always sets edit_date on
 * the server), this feature deletes the original message and sends a new one
 * with the updated text. The result is a fresh message with no edit history.
 *
 * Note: replies to the original message will lose their reference.
 */
public class GhostEdit {

    public static boolean isEnable = false;
    private static final int OPTION_ID = 8354010;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    public static void init(Context context) {
        try {
            if (!isEnable) {
                isEnable = true;
                if (ClassLoad.getClass(ClassNames.CHAT_ACTIVITY) == null) return;

                // Add "Ghost Edit" to the long-press context menu on own text messages
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
                                if (!ConfigManager.ghostEdit.isEnable()) return;
                                try {
                                    ChatActivity chat = new ChatActivity(param.thisObject);
                                    com.my.televip.virtuals.messenger.MessageObject mo = chat.getSelectedObject();
                                    if (mo == null || mo.getMessageOwner() == null) return;

                                    // Only show for own outgoing text messages
                                    if (!isOwnMessage(mo)) return;
                                    String text = mo.getMessageOwner().getMessage();
                                    if (text == null || text.isEmpty()) return;

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
                                    items.add(Translator.get(Keys.GhostEdit));
                                    options.add(OPTION_ID);
                                    if (!ClientChecker.check(ClientChecker.ClientType.Nagram))
                                        icons.add(SettingsIconResolver.getIconSettings());
                                } catch (Throwable t) { Logger.e(t); }
                            }
                        }));

                // Handle the click
                HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.CHAT_ACTIVITY),
                    AutomationResolver.resolve("ChatActivity", "processSelectedOption", AutomationResolver.ResolverType.Method),
                    AutomationResolver.merge(
                        AutomationResolver.resolveObject("processSelectedOption", new Class[]{int.class}),
                        new AbstractMethodHook() {
                            @Override
                            protected void beforeMethod(MethodHookParam param) {
                                if (!ConfigManager.ghostEdit.isEnable()) return;
                                if ((int) param.args[0] != OPTION_ID) return;
                                try {
                                    param.setResult(null);
                                    ChatActivity chat = new ChatActivity(param.thisObject);
                                    com.my.televip.virtuals.messenger.MessageObject mo = chat.getSelectedObject();
                                    if (mo == null) return;
                                    showGhostEditDialog(context, param.thisObject, mo);
                                } catch (Throwable t) { Logger.e(t); }
                            }
                        }));
            }
        } catch (Throwable e) { Logger.e(e); }
    }

    private static void showGhostEditDialog(Context ctx, Object chatActivityObj, com.my.televip.virtuals.messenger.MessageObject mo) {
        UI.post(() -> {
            try {
                String currentText = mo.getMessageOwner().getMessage();
                int msgId = mo.getMessageOwner().getID();

                LinearLayout layout = new LinearLayout(ctx);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(40, 20, 40, 10);

                EditText input = new EditText(ctx);
                input.setText(currentText);
                input.setSelection(currentText != null ? currentText.length() : 0);
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                input.setLines(4);
                input.setGravity(Gravity.TOP);
                layout.addView(input);

                new AlertDialog.Builder(ctx)
                    .setTitle(Translator.get(Keys.GhostEdit) + "  👻")
                    .setView(layout)
                    .setPositiveButton(Translator.get(Keys.Done), (d, w) -> {
                        String newText = input.getText().toString().trim();
                        if (newText.isEmpty()) return;
                        performGhostEdit(ctx, chatActivityObj, msgId, newText);
                    })
                    .setNegativeButton(Translator.get(Keys.Cancel), null)
                    .show();
            } catch (Throwable t) { Logger.e(t); }
        });
    }

    private static void performGhostEdit(Context ctx, Object chatActivityObj, int msgId, String newText) {
        try {
            int account = UserConfig.getSelectedAccount();

            // ── Step 1: Get dialog_id from ChatActivity ───────────────────────
            long dialogId = 0;
            for (String field : new String[]{"dialog_id", "dialogId"}) {
                try {
                    dialogId = XposedHelpers.getLongField(chatActivityObj,
                        AutomationResolver.resolve("ChatActivity", field, AutomationResolver.ResolverType.Field));
                    if (dialogId != 0) break;
                } catch (Throwable ignored) {
                    try { dialogId = XposedHelpers.getLongField(chatActivityObj, field); if (dialogId != 0) break; }
                    catch (Throwable ignored2) {}
                }
            }

            // ── Step 2: Delete original message via MessagesController ────────
            Object mc = XposedHelpers.callStaticMethod(
                ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER),
                AutomationResolver.resolve("MessagesController", "getInstance", AutomationResolver.ResolverType.Method),
                account);

            ArrayList<Integer> ids = new ArrayList<>();
            ids.add(msgId);
            String deleteMethod = AutomationResolver.resolve("MessagesController", "deleteMessages", AutomationResolver.ResolverType.Method);

            boolean deleted = false;
            for (Object[] args : new Object[][]{
                {ids, null, null, dialogId, true, 0},
                {ids, null, null, dialogId, true},
                {ids, null, null, Math.abs(dialogId), true}
            }) {
                try {
                    XposedHelpers.callMethod(mc, deleteMethod, args);
                    deleted = true;
                    break;
                } catch (Throwable ignored) {}
            }
            if (!deleted) {
                try { XposedHelpers.callMethod(mc, "deleteMessages", ids, null, null, dialogId, true); deleted = true; }
                catch (Throwable ignored) {}
            }

            // ── Step 3: Send new message via SendMessagesHelper ───────────────
            final long finalDialogId = dialogId;
            boolean sent = false;
            try {
                Class<?> smh = XposedHelpers.findClassIfExists("org.telegram.messenger.SendMessagesHelper", Utils.classLoader);
                if (smh != null) {
                    String getInstance = AutomationResolver.resolve("SendMessagesHelper", "getInstance", AutomationResolver.ResolverType.Method);
                    Object helper;
                    try { helper = XposedHelpers.callStaticMethod(smh, getInstance, account); }
                    catch (Throwable t) { helper = XposedHelpers.callStaticMethod(smh, "getInstance", account); }

                    if (helper != null) {
                        String send = AutomationResolver.resolve("SendMessagesHelper", "sendMessage", AutomationResolver.ResolverType.Method);
                        for (Object[] args : new Object[][]{
                            {newText, finalDialogId, null, null, null, null, null, null, true, 0, null, false},
                            {newText, finalDialogId, null, null, null, null, null, null, true, 0},
                            {newText, finalDialogId, null, null, null, null, null, null, false, 0},
                            {newText, finalDialogId, null, null, true, 0}
                        }) {
                            try { XposedHelpers.callMethod(helper, send, args); sent = true; break; }
                            catch (Throwable ignored) {}
                        }
                        if (!sent) {
                            try { XposedHelpers.callMethod(helper, "sendMessage", newText, finalDialogId, null, null, null, null, null, null, true, 0); sent = true; }
                            catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable t) { Logger.e(t); }

            // ── Step 4: Fallback — set text in ChatActivity's input field ─────
            if (!sent) {
                setInputText(ctx, chatActivityObj, newText);
            }

        } catch (Throwable e) {
            Logger.e(e);
            copyToClipboard(ctx, newText);
        }
    }

    /** Try to set the chat's text input field directly. */
    private static void setInputText(Context ctx, Object chatActivityObj, String text) {
        boolean done = false;
        for (String field : new String[]{"chatEditText", "messageEditText", "editField", "messageField"}) {
            try {
                String resolved = AutomationResolver.resolve("ChatActivity", field, AutomationResolver.ResolverType.Field);
                Object et = null;
                try { et = XposedHelpers.getObjectField(chatActivityObj, resolved); } catch (Throwable ignored) {}
                if (et == null) try { et = XposedHelpers.getObjectField(chatActivityObj, field); } catch (Throwable ignored) {}
                if (et instanceof android.widget.EditText) {
                    final android.widget.EditText editText = (android.widget.EditText) et;
                    UI.post(() -> {
                        editText.setText(text);
                        editText.setSelection(text.length());
                    });
                    done = true;
                    break;
                }
            } catch (Throwable ignored) {}
        }
        if (!done) copyToClipboard(ctx, text);
    }

    private static void copyToClipboard(Context ctx, String text) {
        UI.post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("ghost_edit", text));
                Toast.makeText(ctx, Translator.get(Keys.GhostEditPasteHint), Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        });
    }

    private static boolean isOwnMessage(com.my.televip.virtuals.messenger.MessageObject mo) {
        try {
            return XposedHelpers.getBooleanField(mo.getMessageOwner().message, "out");
        } catch (Throwable t) {
            try {
                int flags = mo.getMessageOwner().getFlags();
                return (flags & 2) != 0;
            } catch (Throwable ignored) { return false; }
        }
    }
}

package com.my.televip.features;

import android.util.SparseArray;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;
import com.my.televip.messages.MessageStorage;
import com.my.televip.obfuscate.AutomationResolver;
import com.my.televip.utils.Utils;
import com.my.televip.virtuals.androidx.LongSparseArray;
import com.my.televip.virtuals.messenger.MessageObject;
import com.my.televip.virtuals.messenger.MessagesController;
import com.my.televip.virtuals.messenger.MessagesStorage;
import com.my.televip.virtuals.messenger.NotificationCenter;
import com.my.televip.virtuals.tgnet.TLRPC;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XposedHelpers;

public class ShowDeletedMessages {

    public static final int FLAG_DELETED = 1 << 31;

    /**
     * Reliable in-memory set of deleted message IDs.
     * Independent of Telegram field-name resolution — populated directly from
     * the delete update before any field lookup can fail.
     */
    public static final Set<Integer> deletedIds =
            Collections.synchronizedSet(new HashSet<>());

    private static boolean isDeleteMessage = false;

    public static boolean isEnable = false;

    public static void markMessagesDeletedForController(MessagesStorage messagesStorage, long dialogId, ArrayList<Integer> delMsg) {
        MessageStorage.markMessagesDeleted(messagesStorage, dialogId, delMsg);
    }

    /**
     * Prepend the trash-bin emoji (🗑) into the raw message text so the deleted
     * indicator appears INSIDE the message bubble — not only in the tiny timestamp.
     * Idempotent: skipped when the prefix is already present.
     */
    private static void markDeletedText(TLRPC.Message owner) {
        try {
            String txt = (String) XposedHelpers.getObjectField(owner.message, "message");
            if (txt == null) txt = "";
            if (!txt.startsWith("\uD83D\uDDD1")) {
                XposedHelpers.setObjectField(owner.message, "message", "\uD83D\uDDD1  " + txt);
            }
        } catch (Throwable ignored) {}
    }

    public static void init()
    {
        try {
            if (ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER) != null) {
                Method[] messagesControllerMethods = ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER).getDeclaredMethods();
                List<String> methodNames = new ArrayList<>();

                for (Method method : messagesControllerMethods)
                    if (method.getParameterCount() == 5 && method.getParameterTypes()[0] == ArrayList.class && method.getParameterTypes()[1] == ArrayList.class && method.getParameterTypes()[2] == ArrayList.class && method.getParameterTypes()[3] == boolean.class && method.getParameterTypes()[4] == int.class)
                        methodNames.add(method.getName());

                if (methodNames.size() != 1)
                    Logger.w("Failed to hook processUpdateArray! Reason: " + (methodNames.isEmpty() ? "No method found" : "Multiple methods found") + ", " + Utils.issue);
                else {
                    String methodName = methodNames.get(0);

                    HMethod.hookMethod(ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER), methodName, ArrayList.class, ArrayList.class, ArrayList.class, boolean.class, int.class, new AbstractMethodHook() {
                        @Override
                        protected void beforeMethod(MethodHookParam param) {
                            try {
                                MessagesController messagesController = new MessagesController(param.thisObject);
                                CopyOnWriteArrayList<Object> updates = new CopyOnWriteArrayList<>(Utils.castList(param.args[0], Object.class));
                                if (!updates.isEmpty()) {
                                    ArrayList<Object> newUpdates = new ArrayList<>();

                                    for (Object item : updates) {
                                        if (!item.getClass().equals(ClassLoad.getClass(ClassNames.TL_UPDATE_DELETE_CHANNEL_MESSAGES)) && !item.getClass().equals(ClassLoad.getClass(ClassNames.TL_UPDATE_DELETE_MESSAGES)))
                                            newUpdates.add(item);

                                        if (item.getClass().equals(ClassLoad.getClass(ClassNames.TL_UPDATE_DELETE_CHANNEL_MESSAGES))) {
                                            TLRPC.TL_updateDeleteChannelMessages channelMessages = new TLRPC.TL_updateDeleteChannelMessages(item);

                                            // ── Reliable: add IDs to static set first ──────────────
                                            ArrayList<Integer> msgIds = channelMessages.getMessages();
                                            if (msgIds != null) deletedIds.addAll(msgIds);

                                            LongSparseArray dialogMessage = messagesController.getDialogMessage();
                                            ArrayList<Object> dialogMessages = dialogMessage.get(-channelMessages.getChannelID());
                                            if (dialogMessages != null) {
                                                for (final Object msgObj : dialogMessages) {
                                                    TLRPC.Message owner = new MessageObject(msgObj).getMessageOwner();
                                                    if (msgIds != null && msgIds.contains(owner.getID())) {
                                                        owner.setFlags(owner.getFlags() | FLAG_DELETED);
                                                        markDeletedText(owner);
                                                    }
                                                }
                                            }
                                            markMessagesDeletedForController(messagesController.getMessagesStorage(), -channelMessages.getChannelID(), msgIds != null ? msgIds : new ArrayList<>());
                                        }

                                        if (item.getClass().equals(ClassLoad.getClass(ClassNames.TL_UPDATE_DELETE_MESSAGES))) {
                                            ArrayList<Integer> messages = new TLRPC.TL_updateDeleteMessages(item).getMessages();

                                            // ── Reliable: add IDs to static set first ──────────────
                                            if (messages != null) deletedIds.addAll(messages);

                                            // Try to set FLAG_DELETED in-memory via dialogMessage
                                            // (iterates all loaded dialogs — more complete than dialogMessagesByIds)
                                            try {
                                                LongSparseArray dialogMessage = messagesController.getDialogMessage();
                                                int size = dialogMessage.size();
                                                for (int di = 0; di < size; di++) {
                                                    try {
                                                        long key = (long) XposedHelpers.callMethod(dialogMessage.longSparseArray,
                                                                AutomationResolver.resolve("LongSparseArray", "keyAt", AutomationResolver.ResolverType.Method), di);
                                                        ArrayList<Object> dialogMsgs = dialogMessage.get(key);
                                                        if (dialogMsgs == null) continue;
                                                        for (Object msgObj : dialogMsgs) {
                                                            TLRPC.Message owner = new MessageObject(msgObj).getMessageOwner();
                                                            if (messages != null && messages.contains(owner.getID())) {
                                                                owner.setFlags(owner.getFlags() | FLAG_DELETED);
                                                                markDeletedText(owner);
                                                            }
                                                        }
                                                    } catch (Throwable ignored) {}
                                                }
                                            } catch (Throwable ignored) {}

                                            // Fallback: dialogMessagesByIds (last message per dialog)
                                            SparseArray<Object> dialogMessagesByIds = messagesController.getDialogMessagesByIds();
                                            if (messages != null) {
                                                for (int id : messages) {
                                                    Object msgObj = dialogMessagesByIds.get(id);
                                                    if (msgObj == null) continue; // fixed: was `break`
                                                    TLRPC.Message owner = new MessageObject(msgObj).getMessageOwner();
                                                    owner.setFlags(owner.getFlags() | FLAG_DELETED);
                                                    markDeletedText(owner);
                                                }
                                            }
                                            markMessagesDeletedForController(messagesController.getMessagesStorage(), 0, messages != null ? messages : new ArrayList<>());
                                        }
                                    }
                                    param.args[0] = newUpdates;
                                }
                            } catch (Throwable throwable) {
                                Logger.e(throwable);
                            }
                        }
                    });
                }
            }
        } catch (Throwable e){
            Logger.e(e);
        }
    }

    public static void initProcessing() {
        try {
            if (!isEnable) {
                isEnable = true;

                HMethod.hookMethod(
                        ClassLoad.getClass(ClassNames.MESSAGES_STORAGE),
                        AutomationResolver.resolve("MessagesStorage", "markMessagesAsDeleted", AutomationResolver.ResolverType.Method),
                        AutomationResolver.merge(AutomationResolver.resolveObject("markMessagesAsDeleted", new Class[]{long.class, java.util.ArrayList.class, boolean.class, boolean.class, int.class, int.class}),
                                new AbstractMethodHook() {
                                    @Override
                                    protected void beforeMethod(MethodHookParam param) {
                                        if (!isDeleteMessage) {
                                            param.setResult(null);
                                        }
                                    }
                                }
                        ));
            }

            Method removeDeletedMessagesFromNotifications = null;
            for (Method method : ClassLoad.getClass(ClassNames.NOTIFICATIONS_CONTROLLER).getDeclaredMethods())
                if (method.getName().equals(AutomationResolver.resolve("NotificationsController", "removeDeletedMessagesFromNotifications", AutomationResolver.ResolverType.Method)))
                    removeDeletedMessagesFromNotifications = method;

            if (removeDeletedMessagesFromNotifications == null)
                Logger.w("Failed to hook removeDeletedMessagesFromNotifications! Reason: No method found, " + Utils.issue);
            else
                HMethod.hookMethod(removeDeletedMessagesFromNotifications, new AbstractMethodHook() {
                    @Override
                    protected void beforeMethod(MethodHookParam param) {
                        if (ConfigManager.showDeletedMessages.isEnable()) {
                            param.setResult(null);
                        }
                    }
                });

            HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER),
                    AutomationResolver.resolve("MessagesController", "deleteMessages", AutomationResolver.ResolverType.Method),
                    AutomationResolver.merge(AutomationResolver.resolveObject("deleteMessages", new Class[]{java.util.ArrayList.class,
                                    java.util.ArrayList.class,
                                    ClassLoad.getClass(ClassNames.TLRPC_ENCRYPTED_CHAT),
                                    long.class,
                                    boolean.class,
                                    int.class,
                                    boolean.class,
                                    long.class,
                                    ClassLoad.getClass(ClassNames.TL_OBJECT),
                                    int.class,
                                    boolean.class,
                                    int.class}),
                            new AbstractMethodHook() {
                                @Override
                                protected void beforeMethod(MethodHookParam param) {
                                    isDeleteMessage = true;
                                }
                            }
                    ));

            HMethod.hookMethod(ClassLoad.getClass(ClassNames.NOTIFICATION_CENTER), AutomationResolver.resolve("NotificationCenter", "postNotificationName", AutomationResolver.ResolverType.Method), AutomationResolver.merge(AutomationResolver.resolveObject("postNotificationName", new Class[]{int.class, Object[].class}), new AbstractMethodHook() {
                @Override
                protected void beforeMethod(MethodHookParam param) {
                    if (!isDeleteMessage) {
                        int id = (int) param.args[0];
                        if (id == NotificationCenter.getMessagesDeleted()) {
                            param.setResult(null);
                        }
                    }
                }

                @Override
                protected void afterMethod(MethodHookParam param) {
                    isDeleteMessage = false;
                }
            }));

            ShowDeletedMessages.init();
            ShowDeletedMessages.initAutoDownload();
        } catch (Throwable e) {
            Logger.e(e);
        }

        if (ConfigManager.showDeletedMessages.isEnable() && !ChatMessageCell.isEnable)
            ChatMessageCell.init();
    }

    public static void initAutoDownload() {
        if (ClassLoad.getClass(ClassNames.DOWNLOAD_CONTROLLER) == null)
            return;
        HMethod.hookMethod(ClassLoad.getClass(ClassNames.DOWNLOAD_CONTROLLER), AutomationResolver.resolve("DownloadController", "canDownloadMedia", AutomationResolver.ResolverType.Method), ClassLoad.getClass(ClassNames.TL_MESSAGE), new AbstractMethodHook() {
            @Override
            protected void beforeMethod(MethodHookParam param) {
                TLRPC.Message message = new TLRPC.Message(param.args[0]);
                if ((message.getFlags() & FLAG_DELETED) != 0)
                    param.setResult(0);
            }
        });
    }
}
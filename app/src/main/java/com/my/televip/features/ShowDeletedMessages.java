package com.my.televip.features;

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
import com.my.televip.virtuals.ui.Cells.ChatMessageCell;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ShowDeletedMessages — keeps deleted messages visible in the chat with an X indicator.
 *
 * Architecture (mirrors NagramX / AyuGram logic adapted for Xposed):
 *
 *  1. deleteMessages hook (beforeMethod)
 *     — User pressed Delete.  Before the original runs, ALL msgIds are added directly
 *       to deletedIds (fix for multi-select wipe bug — dialogMessage only holds the
 *       preview/newest message per dialog, so scanning it missed older selected messages).
 *       Then FLAG_DELETED is set on any messages found in the in-memory lists.
 *       The original method then calls markMessagesAsDeleted + posts messagesDeleted —
 *       both are handled by hooks 2 and 3 below.
 *
 *  2. processUpdateArray hook (beforeMethod)
 *     — Server ack arrives.  Collect ALL deleted IDs from the update batch first
 *       (NagramX pattern), mark FLAG_DELETED on every match, write to our DB, then
 *       let ALL updates pass through unchanged so Telegram handles PTS / dialog sync.
 *
 *  3. markMessagesAsDeleted hook (beforeMethod)
 *     — Called by both deleteMessages and processUpdateArray.  Instead of blocking
 *       entirely, FILTER our preserved IDs out of the args list.  If nothing remains,
 *       block the call; otherwise let it through for any non-preserved IDs.
 *
 *  4. postNotificationName hook (beforeMethod) for messagesDeleted
 *     — Instead of blocking (which freezes selection mode for ~5 s), FILTER our
 *       preserved IDs from the notification args.  ChatActivity receives
 *       messagesDeleted([]) → exits selection mode immediately → removes nothing
 *       from the adapter.  Messages stay visible with FLAG_DELETED / X indicator.
 *
 *  5. removeDeletedMessagesFromNotifications hook — blocked while feature is on.
 *
 *  6. DownloadController.canDownloadMedia hook — blocks auto-download for FLAG_DELETED msgs.
 */
public class ShowDeletedMessages {

    /** Bit flag stored in message.flags to mark a message as "deleted but preserved". */
    public static final int FLAG_DELETED = 1 << 31;

    /**
     * IDs of messages we have preserved.  Downstream hooks use this to filter their
     * respective calls so Telegram never actually removes these messages.
     */
    public static final CopyOnWriteArrayList<Integer> deletedIds = new CopyOnWriteArrayList<>();

    public static boolean isEnable = false;

    // ── DB helper ─────────────────────────────────────────────────────────────

    public static void markMessagesDeletedForController(MessagesStorage messagesStorage,
                                                        long dialogId,
                                                        ArrayList<Integer> delMsg) {
        MessageStorage.markMessagesDeleted(messagesStorage, dialogId, delMsg);
    }

    // ── Core helpers ──────────────────────────────────────────────────────────

    /**
     * Scans Telegram's in-memory message lists and marks FLAG_DELETED on every message
     * whose ID appears in targetIds.
     *
     * dialogKey == 0  → non-channel message; we don't know which dialog it belongs to,
     *                   so scan ALL loaded dialogs (same as NagramX channel handling).
     * dialogKey != 0  → channel dialog; scan only that dialog's list.
     *
     * Found IDs are added to {@link #deletedIds} for downstream filtering.
     */
    private static void markFlagDeletedInDialogs(LongSparseArray dialogMessage,
                                                  long dialogKey,
                                                  ArrayList<Integer> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) return;

        if (dialogKey != 0) {
            // Known dialog (channel): scan only its message list
            ArrayList<Object> msgs = dialogMessage.get(dialogKey);
            if (msgs != null) markInList(msgs, targetIds);
        } else {
            // Unknown dialog: scan every loaded dialog
            for (int di = 0; di < dialogMessage.size(); di++) {
                ArrayList<Object> msgs = dialogMessage.get(dialogMessage.keyAt(di));
                if (msgs != null) markInList(msgs, targetIds);
            }
        }
    }

    private static void markInList(ArrayList<Object> msgs, ArrayList<Integer> targetIds) {
        for (Object msgObj : msgs) {
            try {
                TLRPC.Message owner = new MessageObject(msgObj).getMessageOwner();
                int msgId = owner.getID();
                if (targetIds.contains(msgId)) {
                    owner.setFlags(owner.getFlags() | FLAG_DELETED);
                    if (!deletedIds.contains(msgId)) {
                        deletedIds.add(msgId);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Removes every ID in {@link #deletedIds} from the given list, in-place.
     * Uses Iterator.remove() for compatibility with all API levels.
     *
     * @return true if the list is empty after filtering
     */
    private static boolean filterPreservedIds(ArrayList<Integer> ids) {
        if (ids == null) return true;
        Iterator<Integer> iter = ids.iterator();
        while (iter.hasNext()) {
            if (deletedIds.contains(iter.next())) {
                iter.remove();
            }
        }
        return ids.isEmpty();
    }

    // ── Hook 2: processUpdateArray ────────────────────────────────────────────

    /**
     * Installed by {@link #init()}.
     * Intercepts the server-side update that carries TL_updateDeleteMessages /
     * TL_updateDeleteChannelMessages, collects all IDs in a single pass (NagramX
     * pattern), then marks FLAG_DELETED and writes to our DB.  ALL updates are left
     * in param.args[0] so Telegram can do its own PTS / dialog housekeeping normally.
     */
    public static void init() {
        try {
            if (ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER) == null) return;

            // Find processUpdateArray by signature: (ArrayList, ArrayList, ArrayList, boolean, int)
            List<String> candidates = new ArrayList<>();
            for (Method m : ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER).getDeclaredMethods()) {
                if (m.getParameterCount() == 5
                        && m.getParameterTypes()[0] == ArrayList.class
                        && m.getParameterTypes()[1] == ArrayList.class
                        && m.getParameterTypes()[2] == ArrayList.class
                        && m.getParameterTypes()[3] == boolean.class
                        && m.getParameterTypes()[4] == int.class) {
                    candidates.add(m.getName());
                }
            }

            if (candidates.size() != 1) {
                Logger.w("ShowDeletedMessages: processUpdateArray hook failed — "
                        + (candidates.isEmpty() ? "no match" : "ambiguous") + ". " + Utils.issue);
                return;
            }

            HMethod.hookMethod(
                ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER),
                candidates.get(0),
                ArrayList.class, ArrayList.class, ArrayList.class, boolean.class, int.class,
                new AbstractMethodHook() {
                    @Override
                    protected void beforeMethod(MethodHookParam param) {
                        if (!ConfigManager.showDeletedMessages.isEnable()) return;
                        try {
                            List<Object> updates = Utils.castList(param.args[0], Object.class);
                            if (updates == null || updates.isEmpty()) return;

                            MessagesController mc = new MessagesController(param.thisObject);

                            // ── Step 1: Collect deleted IDs from the entire update batch ──
                            // key = 0  for non-channel (TL_updateDeleteMessages)
                            // key = -channelId for channels (TL_updateDeleteChannelMessages)
                            HashMap<Long, ArrayList<Integer>> pendingDeletes = new HashMap<>();

                            for (Object item : updates) {
                                Class<?> cls = item.getClass();

                                if (cls.equals(ClassLoad.getClass(ClassNames.TL_UPDATE_DELETE_MESSAGES))) {
                                    ArrayList<Integer> ids =
                                            new TLRPC.TL_updateDeleteMessages(item).getMessages();
                                    if (ids != null && !ids.isEmpty()) {
                                        ArrayList<Integer> bucket = pendingDeletes.get(0L);
                                        if (bucket == null) {
                                            bucket = new ArrayList<>();
                                            pendingDeletes.put(0L, bucket);
                                        }
                                        bucket.addAll(ids);
                                    }

                                } else if (cls.equals(ClassLoad.getClass(ClassNames.TL_UPDATE_DELETE_CHANNEL_MESSAGES))) {
                                    TLRPC.TL_updateDeleteChannelMessages cm =
                                            new TLRPC.TL_updateDeleteChannelMessages(item);
                                    long key = -cm.getChannelID();
                                    ArrayList<Integer> ids = cm.getMessages();
                                    if (ids != null && !ids.isEmpty()) {
                                        ArrayList<Integer> bucket = pendingDeletes.get(key);
                                        if (bucket == null) {
                                            bucket = new ArrayList<>();
                                            pendingDeletes.put(key, bucket);
                                        }
                                        bucket.addAll(ids);
                                    }
                                }
                            }

                            if (pendingDeletes.isEmpty()) return;

                            // ── Step 2: Mark FLAG_DELETED + write to our DB ───────────────
                            LongSparseArray dialogMessage = mc.getDialogMessage();

                            for (Map.Entry<Long, ArrayList<Integer>> entry : pendingDeletes.entrySet()) {
                                long dialogKey = entry.getKey();
                                ArrayList<Integer> ids = entry.getValue();

                                markFlagDeletedInDialogs(dialogMessage, dialogKey, ids);
                                markMessagesDeletedForController(mc.getMessagesStorage(), dialogKey, ids);
                            }

                            // ── Step 3: Leave param.args[0] unchanged ─────────────────────
                            // Telegram processes the updates normally (PTS, dialog sync, etc.).
                            // Hooks 3 & 4 below strip our preserved IDs from
                            // markMessagesAsDeleted and the messagesDeleted notification.

                        } catch (Throwable t) {
                            Logger.e(t);
                        }
                    }
                }
            );
        } catch (Throwable e) {
            Logger.e(e);
        }
    }

    // ── Hooks 1, 3, 4, 5, 6 ──────────────────────────────────────────────────

    public static void initProcessing() {
        try {
            if (!isEnable) {
                isEnable = true;

                // ── Hook 3: markMessagesAsDeleted — filter, don't block ──────────
                // signature: (long dialogKey, ArrayList<Integer> ids, boolean, boolean, int, int)
                HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.MESSAGES_STORAGE),
                    AutomationResolver.resolve("MessagesStorage", "markMessagesAsDeleted",
                            AutomationResolver.ResolverType.Method),
                    AutomationResolver.merge(
                        AutomationResolver.resolveObject("markMessagesAsDeleted",
                            new Class[]{long.class, java.util.ArrayList.class,
                                        boolean.class, boolean.class, int.class, int.class}),
                        new AbstractMethodHook() {
                            @Override
                            protected void beforeMethod(MethodHookParam param) {
                                if (!ConfigManager.showDeletedMessages.isEnable()) return;
                                try {
                                    @SuppressWarnings("unchecked")
                                    ArrayList<Integer> ids = (ArrayList<Integer>) param.args[1];
                                    // Remove preserved IDs; block the call only if nothing is left.
                                    if (filterPreservedIds(ids)) {
                                        param.setResult(null);
                                    }
                                } catch (Throwable t) {
                                    Logger.e(t);
                                    param.setResult(null);
                                }
                            }
                        }
                    )
                );
            }

            // ── Hook 5: removeDeletedMessagesFromNotifications ───────────────────
            Method removeFromNotif = null;
            for (Method m : ClassLoad.getClass(ClassNames.NOTIFICATIONS_CONTROLLER).getDeclaredMethods()) {
                if (m.getName().equals(AutomationResolver.resolve("NotificationsController",
                        "removeDeletedMessagesFromNotifications",
                        AutomationResolver.ResolverType.Method))) {
                    removeFromNotif = m;
                    break;
                }
            }
            if (removeFromNotif == null) {
                Logger.w("ShowDeletedMessages: removeDeletedMessagesFromNotifications not found. "
                        + Utils.issue);
            } else {
                final Method finalRemove = removeFromNotif;
                HMethod.hookMethod(finalRemove, new AbstractMethodHook() {
                    @Override
                    protected void beforeMethod(MethodHookParam param) {
                        if (ConfigManager.showDeletedMessages.isEnable()) {
                            param.setResult(null);
                        }
                    }
                });
            }

            // ── Hook 1: deleteMessages — mark FLAG_DELETED before original runs ──
            // The original method will call markMessagesAsDeleted (filtered by hook 3)
            // and post messagesDeleted (filtered by hook 4).  Both hooks together ensure
            // messages stay in the adapter and DB while selection mode clears instantly.
            HMethod.hookMethod(
                ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER),
                AutomationResolver.resolve("MessagesController", "deleteMessages",
                        AutomationResolver.ResolverType.Method),
                AutomationResolver.merge(
                    AutomationResolver.resolveObject("deleteMessages", new Class[]{
                        java.util.ArrayList.class,
                        java.util.ArrayList.class,
                        ClassLoad.getClass(ClassNames.TLRPC_ENCRYPTED_CHAT),
                        long.class, boolean.class, int.class, boolean.class,
                        long.class, ClassLoad.getClass(ClassNames.TL_OBJECT),
                        int.class, boolean.class, int.class
                    }),
                    new AbstractMethodHook() {
                        @Override
                        protected void beforeMethod(MethodHookParam param) {
                            if (ConfigManager.showDeletedMessages == null
                                    || !ConfigManager.showDeletedMessages.isEnable()) return;
                            try {
                                ArrayList<Integer> msgIds =
                                        Utils.castList(param.args[0], Integer.class);
                                if (msgIds == null || msgIds.isEmpty()) return;

                                // ── FIX: Register ALL selected IDs into deletedIds immediately ──
                                // Root cause of the multi-select wipe bug:
                                // dialogMessage (LongSparseArray) only holds the dialog-list
                                // PREVIEW entry — one representative message per dialog (the newest).
                                // When the user selects [A, B, C], scanning dialogMessage finds only
                                // C (newest), so deletedIds = {C} only.
                                // Hook 4 then filters messagesDeleted([A,B,C]) → removes only C →
                                // fires messagesDeleted([A,B]) → ChatActivity wipes A and B.
                                // Fix: add every selected ID to deletedIds here, BEFORE the original
                                // runs. Hook 4 will then filter ALL of them → ChatActivity receives
                                // messagesDeleted([]) → exits selection mode cleanly → removes nothing.
                                // ChatMessageCell.isDeleted() already has deletedIds.contains() as a
                                // fallback, so the X indicator shows on all preserved messages even
                                // when FLAG_DELETED could not be set via in-memory scanning.
                                for (Integer id : msgIds) {
                                    if (!deletedIds.contains(id)) {
                                        deletedIds.add(id);
                                    }
                                }

                                MessagesController mc = new MessagesController(param.thisObject);

                                // Best-effort: set FLAG_DELETED on messages that ARE found in
                                // the in-memory lists (so they draw the X via the flags path too).
                                // dialogMessage holds per-dialog loaded message lists (not just
                                // the preview), so this covers whatever is currently in memory.
                                markFlagDeletedInDialogs(mc.getDialogMessage(), 0L, msgIds);

                            } catch (Throwable t) {
                                Logger.e(t);
                            }
                            // The original deleteMessages runs next.
                            // Hook 3 filters markMessagesAsDeleted.
                            // Hook 4 filters the messagesDeleted notification.
                        }
                    }
                )
            );

            // ── Hook 4: postNotificationName — filter messagesDeleted, don't block ─
            // Blocking this notification entirely causes a ~5-second selection-mode
            // freeze because ChatActivity waits for it to clear the action bar.
            // Instead, strip our preserved IDs from the args in-place.
            // ChatActivity receives messagesDeleted([]) → clears selection instantly
            // → finds nothing to remove from the adapter → our messages stay with X.
            HMethod.hookMethod(
                ClassLoad.getClass(ClassNames.NOTIFICATION_CENTER),
                AutomationResolver.resolve("NotificationCenter", "postNotificationName",
                        AutomationResolver.ResolverType.Method),
                AutomationResolver.merge(
                    AutomationResolver.resolveObject("postNotificationName",
                            new Class[]{int.class, Object[].class}),
                    new AbstractMethodHook() {
                        @Override
                        protected void beforeMethod(MethodHookParam param) {
                            if (!ConfigManager.showDeletedMessages.isEnable()) return;
                            if ((int) param.args[0] != NotificationCenter.getMessagesDeleted()) return;
                            try {
                                Object[] notifArgs = (Object[]) param.args[1];
                                if (notifArgs == null || notifArgs.length == 0) return;
                                // notifArgs[0] = ArrayList<Integer> message IDs
                                // notifArgs[1] = long channelId
                                @SuppressWarnings("unchecked")
                                ArrayList<Integer> deletingIds = (ArrayList<Integer>) notifArgs[0];
                                // Remove preserved IDs in-place so every listener sees the
                                // filtered list.  Do NOT call setResult(null) — the notification
                                // must still fire so ChatActivity can exit selection mode.
                                filterPreservedIds(deletingIds);
                            } catch (Throwable t) {
                                Logger.e(t);
                            }
                        }
                    }
                )
            );

            ShowDeletedMessages.init();
            ShowDeletedMessages.initAutoDownload();
        } catch (Throwable e) {
            Logger.e(e);
        }

        if (ConfigManager.showDeletedMessages.isEnable() && !ChatMessageCell.isEnable)
            ChatMessageCell.init();
    }

    // ── Hook 6: DownloadController ───────────────────────────────────────────

    public static void initAutoDownload() {
        if (ClassLoad.getClass(ClassNames.DOWNLOAD_CONTROLLER) == null) return;

        HMethod.hookMethod(
            ClassLoad.getClass(ClassNames.DOWNLOAD_CONTROLLER),
            AutomationResolver.resolve("DownloadController", "canDownloadMedia",
                    AutomationResolver.ResolverType.Method),
            ClassLoad.getClass(ClassNames.TL_MESSAGE),
            new AbstractMethodHook() {
                @Override
                protected void beforeMethod(MethodHookParam param) {
                    TLRPC.Message message = new TLRPC.Message(param.args[0]);
                    if ((message.getFlags() & FLAG_DELETED) != 0) {
                        param.setResult(0);
                    }
                }
            }
        );
    }
}

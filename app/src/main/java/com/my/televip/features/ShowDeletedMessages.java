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

import java.lang.reflect.Field;
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
 *  4. postNotificationName hook for messagesDeleted
 *     — beforeMethod: Strip our preserved IDs from the notification args in-place.
 *       ChatActivity receives messagesDeleted([]) → removes nothing from the adapter.
 *     — afterMethod: Force-exit selection mode by locating the ChatActivity observer
 *       via NotificationCenter.getObservers(), clearing its selectedMessagesIds entries
 *       for our preserved IDs, then calling hideActionMode() on its ActionBar.
 *       Also refreshes the adapter so cells redraw with the X indicator.
 *       (Bug 2 fix: without this afterMethod, messagesDeleted([]) makes ChatActivity
 *       do nothing at all — selection checkboxes freeze until the user navigates away.)
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
     */
    private static void markFlagDeletedInDialogs(LongSparseArray dialogMessage,
                                                  long dialogKey,
                                                  ArrayList<Integer> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) return;

        if (dialogKey != 0) {
            ArrayList<Object> msgs = dialogMessage.get(dialogKey);
            if (msgs != null) markInList(msgs, targetIds);
        } else {
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

    // ── Selection-mode helpers (Bug 2 fix) ───────────────────────────────────

    /**
     * Returns the observer list registered with NotificationCenter for the given
     * notification ID.  Tries the public getObservers() method first; falls back to
     * scanning the SparseArray fields of the NotificationCenter instance.
     */
    private static ArrayList<?> getObserversForId(Object nc, int notifId) {
        // Primary: call the public method (present in NagramX / most Telegram builds)
        try {
            String methodName = AutomationResolver.resolve(
                    "NotificationCenter", "getObservers", AutomationResolver.ResolverType.Method);
            Object result = de.robv.android.xposed.XposedHelpers.callMethod(nc, methodName, notifId);
            if (result instanceof ArrayList) return (ArrayList<?>) result;
        } catch (Throwable ignored) {}

        // Fallback: scan SparseArray fields on the NotificationCenter instance
        try {
            for (Field f : nc.getClass().getDeclaredFields()) {
                if (!android.util.SparseArray.class.equals(f.getType())) continue;
                f.setAccessible(true);
                android.util.SparseArray<?> sa = (android.util.SparseArray<?>) f.get(nc);
                if (sa == null) continue;
                Object entry = sa.get(notifId);
                if (entry instanceof ArrayList && !((ArrayList<?>) entry).isEmpty()) {
                    return (ArrayList<?>) entry;
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    /**
     * Given a ChatActivity instance, clears entries for our preserved message IDs from
     * its selectedMessagesIds field, then hides the action mode and refreshes the adapter.
     *
     * Identification heuristic for selectedMessagesIds vs messagesDict:
     *   — Both are SparseArray[] fields on ChatActivity.
     *   — selectedMessagesIds has at most ~100 entries (Telegram's selection cap) AND
     *     its keys are a subset of the currently-selected message IDs (= deletedIds).
     *   — messagesDict has hundreds of entries; most keys are NOT in deletedIds.
     *   So: find the SparseArray[] whose total size is ≤ 200 AND that has at least one
     *   key matching deletedIds — that is selectedMessagesIds.
     */
    private static void forceClearSelectionMode(Object ncInstance) {
        try {
            int messagesDeletedId = NotificationCenter.getMessagesDeleted();
            ArrayList<?> observers = getObserversForId(ncInstance, messagesDeletedId);
            if (observers == null || observers.isEmpty()) return;

            Class<?> chatActivityClass = ClassLoad.getClass(ClassNames.CHAT_ACTIVITY);
            if (chatActivityClass == null) return;

            // Iterate a snapshot to avoid ConcurrentModificationException
            for (Object observer : new ArrayList<>(observers)) {
                if (observer == null || !chatActivityClass.isInstance(observer)) continue;
                clearSelectionOnChatActivity(observer, chatActivityClass);
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    private static void clearSelectionOnChatActivity(Object chatActivity, Class<?> chatActivityClass) {
        try {
            boolean clearedAny = false;

            // ── Step 1: Find and clear selectedMessagesIds ───────────────────────
            // Walk the class hierarchy to find SparseArray[] fields
            Class<?> cls = chatActivityClass;
            while (cls != null && !Object.class.equals(cls)) {
                for (Field f : cls.getDeclaredFields()) {
                    if (!f.getType().isArray()) continue;
                    Class<?> comp = f.getType().getComponentType();
                    if (comp == null || !android.util.SparseArray.class.isAssignableFrom(comp)) continue;

                    f.setAccessible(true);
                    Object val = f.get(chatActivity);
                    if (!(val instanceof android.util.SparseArray[])) continue;

                    android.util.SparseArray<?>[] arrays = (android.util.SparseArray<?>[]) val;

                    // Heuristic: selectedMessagesIds has ≤ 200 total entries
                    int totalSize = 0;
                    for (android.util.SparseArray<?> sa : arrays) totalSize += sa.size();
                    if (totalSize > 200) continue; // messagesDict has many more — skip

                    // Must contain at least one of our preserved IDs
                    boolean hasPreservedKey = false;
                    for (android.util.SparseArray<?> sa : arrays) {
                        for (Integer id : deletedIds) {
                            if (sa.indexOfKey(id) >= 0) { hasPreservedKey = true; break; }
                        }
                        if (hasPreservedKey) break;
                    }
                    if (!hasPreservedKey) continue;

                    // This is selectedMessagesIds — clear our preserved IDs from it
                    for (android.util.SparseArray<?> sa : arrays) {
                        for (Integer id : deletedIds) {
                            if (sa.indexOfKey(id) >= 0) {
                                sa.delete(id);
                                clearedAny = true;
                            }
                        }
                    }
                    break; // Found and processed selectedMessagesIds; stop scanning
                }
                cls = cls.getSuperclass();
            }

            if (!clearedAny) return;

            // ── Step 2: Hide the action mode bar if selection is now empty ───────
            // Check whether ALL selection arrays are empty
            boolean allEmpty = true;
            cls = chatActivityClass;
            outer:
            while (cls != null && !Object.class.equals(cls)) {
                for (Field f : cls.getDeclaredFields()) {
                    if (!f.getType().isArray()) continue;
                    Class<?> comp = f.getType().getComponentType();
                    if (comp == null || !android.util.SparseArray.class.isAssignableFrom(comp)) continue;
                    f.setAccessible(true);
                    Object val = f.get(chatActivity);
                    if (!(val instanceof android.util.SparseArray[])) continue;
                    android.util.SparseArray<?>[] arrays = (android.util.SparseArray<?>[]) val;
                    int totalSize = 0;
                    for (android.util.SparseArray<?> sa : arrays) totalSize += sa.size();
                    if (totalSize > 200) continue; // skip messagesDict
                    for (android.util.SparseArray<?> sa : arrays) {
                        if (sa.size() > 0) { allEmpty = false; break outer; }
                    }
                }
                cls = cls.getSuperclass();
            }

            if (allEmpty) {
                hideActionMode(chatActivity, chatActivityClass);
            }

            // ── Step 3: Refresh the adapter so preserved cells redraw with X ─────
            refreshChatAdapter(chatActivity, chatActivityClass);

        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    /**
     * Calls hideActionMode() on the ChatActivity (directly) or on its ActionBar field.
     * Tries AutomationResolver first; falls back to scanning for a field whose type name
     * contains "ActionBar".
     */
    private static void hideActionMode(Object chatActivity, Class<?> chatActivityClass) {
        // Attempt 1: call hideActionMode directly on ChatActivity
        try {
            de.robv.android.xposed.XposedHelpers.callMethod(chatActivity,
                    AutomationResolver.resolve("ChatActivity", "hideActionMode",
                            AutomationResolver.ResolverType.Method));
            return;
        } catch (Throwable ignored) {}
        try {
            de.robv.android.xposed.XposedHelpers.callMethod(chatActivity, "hideActionMode");
            return;
        } catch (Throwable ignored) {}

        // Attempt 2: find the ActionBar field and call hideActionMode on it
        try {
            Class<?> cls = chatActivityClass;
            while (cls != null && !Object.class.equals(cls)) {
                for (Field f : cls.getDeclaredFields()) {
                    String typeName = f.getType().getName();
                    if (!typeName.contains("ActionBar")) continue;
                    f.setAccessible(true);
                    Object actionBar = f.get(chatActivity);
                    if (actionBar == null) continue;
                    try {
                        de.robv.android.xposed.XposedHelpers.callMethod(actionBar, "hideActionMode");
                        return;
                    } catch (Throwable ignored) {}
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    /**
     * Finds the main chat RecyclerView (RecyclerListView) and calls
     * notifyDataSetChanged() on its adapter, so preserved cells redraw with the X.
     */
    private static void refreshChatAdapter(Object chatActivity, Class<?> chatActivityClass) {
        try {
            Class<?> cls = chatActivityClass;
            while (cls != null && !Object.class.equals(cls)) {
                for (Field f : cls.getDeclaredFields()) {
                    String typeName = f.getType().getName();
                    if (!typeName.contains("RecyclerListView") && !typeName.contains("RecyclerView")) continue;
                    f.setAccessible(true);
                    Object rv = f.get(chatActivity);
                    if (rv == null) continue;
                    try {
                        Object adapter = de.robv.android.xposed.XposedHelpers.callMethod(rv, "getAdapter");
                        if (adapter != null) {
                            de.robv.android.xposed.XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
                            return; // refreshed the first (main) list found
                        }
                    } catch (Throwable ignored) {}
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    // ── Hook 2: processUpdateArray ────────────────────────────────────────────

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

                            HashMap<Long, ArrayList<Integer>> pendingDeletes = new HashMap<>();

                            for (Object item : updates) {
                                Class<?> itemCls = item.getClass();

                                if (itemCls.equals(ClassLoad.getClass(ClassNames.TL_UPDATE_DELETE_MESSAGES))) {
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

                                } else if (itemCls.equals(ClassLoad.getClass(ClassNames.TL_UPDATE_DELETE_CHANNEL_MESSAGES))) {
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

                            LongSparseArray dialogMessage = mc.getDialogMessage();

                            for (Map.Entry<Long, ArrayList<Integer>> entry : pendingDeletes.entrySet()) {
                                long dialogKey = entry.getKey();
                                ArrayList<Integer> ids = entry.getValue();

                                markFlagDeletedInDialogs(dialogMessage, dialogKey, ids);
                                markMessagesDeletedForController(mc.getMessagesStorage(), dialogKey, ids);
                            }

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

                                // Register ALL selected IDs into deletedIds immediately.
                                // This ensures Hook 4's filter strips them ALL from the
                                // messagesDeleted notification, so ChatActivity receives []
                                // and nothing is wiped from the adapter.
                                for (Integer id : msgIds) {
                                    if (!deletedIds.contains(id)) {
                                        deletedIds.add(id);
                                    }
                                }

                                MessagesController mc = new MessagesController(param.thisObject);
                                markFlagDeletedInDialogs(mc.getDialogMessage(), 0L, msgIds);

                            } catch (Throwable t) {
                                Logger.e(t);
                            }
                        }
                    }
                )
            );

            // ── Hook 4: postNotificationName for messagesDeleted ─────────────────
            //
            // beforeMethod: Strip preserved IDs from the args in-place so ChatActivity
            //   receives messagesDeleted([]) and removes nothing from the adapter.
            //   The notification must still fire so ChatActivity can run its handler.
            //
            // afterMethod (Bug 2 fix): After ChatActivity processed messagesDeleted([])
            //   and did nothing, force-exit selection mode by:
            //   (a) Locating the ChatActivity observer via NotificationCenter.getObservers()
            //   (b) Scanning its SparseArray[] fields to find selectedMessagesIds
            //   (c) Clearing our preserved IDs from it and calling hideActionMode()
            //   (d) Refreshing the adapter so cells redraw with the X indicator
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
                                @SuppressWarnings("unchecked")
                                ArrayList<Integer> deletingIds = (ArrayList<Integer>) notifArgs[0];
                                filterPreservedIds(deletingIds);
                            } catch (Throwable t) {
                                Logger.e(t);
                            }
                        }

                        @Override
                        protected void afterMethod(MethodHookParam param) {
                            if (!ConfigManager.showDeletedMessages.isEnable()) return;
                            if ((int) param.args[0] != NotificationCenter.getMessagesDeleted()) return;
                            if (deletedIds.isEmpty()) return;
                            try {
                                // NotificationCenter dispatched messagesDeleted([]) to all
                                // observers.  ChatActivity saw an empty list and did nothing —
                                // selection checkboxes are still visible.  Fix it now.
                                forceClearSelectionMode(param.thisObject);
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
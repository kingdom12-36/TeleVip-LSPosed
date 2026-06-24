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
 *       to deletedIds (fix for multi-select wipe bug).
 *
 *  2. processUpdateArray hook (beforeMethod)
 *     — Server ack arrives.  Collect ALL deleted IDs from the update batch first,
 *       mark FLAG_DELETED, write to DB, let all updates pass through unchanged.
 *
 *  3. markMessagesAsDeleted hook (beforeMethod)
 *     — Filter preserved IDs out of args; block only if nothing remains.
 *
 *  4. postNotificationName hook for messagesDeleted
 *     — beforeMethod: Strip preserved IDs from args so ChatActivity sees [].
 *     — afterMethod (Bug 2 fix): Force-exit selection mode by:
 *         (a) Getting ChatActivity via NotificationCenter.getObservers()
 *         (b) Clearing selectedMessagesIds entries for our preserved IDs
 *         (c) Calling hideActionMode() on the ActionBar
 *         (d) Refreshing cells so circles disappear immediately (no scroll needed):
 *             tries updateVisibleRows(), then chatAdapter.notifyDataSetChanged(true),
 *             then chatListView invalidation — all by direct field name via
 *             AutomationResolver before falling back to type scanning.
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
                    if (!deletedIds.contains(msgId)) deletedIds.add(msgId);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static boolean filterPreservedIds(ArrayList<Integer> ids) {
        if (ids == null) return true;
        Iterator<Integer> iter = ids.iterator();
        while (iter.hasNext()) {
            if (deletedIds.contains(iter.next())) iter.remove();
        }
        return ids.isEmpty();
    }

    // ── Selection-mode exit helpers (Bug 2 fix) ───────────────────────────────

    /**
     * Returns the observer list from NotificationCenter for the given notification ID.
     * Tries the public getObservers() method first, then scans SparseArray fields.
     */
    private static ArrayList<?> getObserversForId(Object nc, int notifId) {
        try {
            String method = AutomationResolver.resolve(
                    "NotificationCenter", "getObservers", AutomationResolver.ResolverType.Method);
            Object result = de.robv.android.xposed.XposedHelpers.callMethod(nc, method, notifId);
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
                if (entry instanceof ArrayList && !((ArrayList<?>) entry).isEmpty())
                    return (ArrayList<?>) entry;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Entry point called from Hook 4 afterMethod.
     * Finds all active ChatActivity observers and clears their selection state.
     */
    private static void forceClearSelectionMode(Object ncInstance) {
        try {
            int messagesDeletedId = NotificationCenter.getMessagesDeleted();
            ArrayList<?> observers = getObserversForId(ncInstance, messagesDeletedId);
            if (observers == null || observers.isEmpty()) return;

            Class<?> chatActivityClass = ClassLoad.getClass(ClassNames.CHAT_ACTIVITY);
            if (chatActivityClass == null) return;

            for (Object observer : new ArrayList<>(observers)) {
                if (observer == null || !chatActivityClass.isInstance(observer)) continue;
                clearSelectionOnChatActivity(observer, chatActivityClass);
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    /**
     * Clears selectedMessagesIds for our preserved IDs, hides the action mode bar,
     * and refreshes visible cells so the selection circles disappear immediately.
     *
     * Identification of selectedMessagesIds vs messagesDict (both are SparseArray[]):
     *   selectedMessagesIds has ≤ 200 total entries AND contains keys in deletedIds.
     *   messagesDict has hundreds of entries; most keys are NOT in deletedIds.
     */
    private static void clearSelectionOnChatActivity(Object chatActivity, Class<?> chatActivityClass) {
        try {
            boolean clearedAny = false;

            // ── Step 1: Find and clear selectedMessagesIds ───────────────────────
            Class<?> cls = chatActivityClass;
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

                    // Heuristic: selectedMessagesIds has ≤ 200 total entries
                    int totalSize = 0;
                    for (android.util.SparseArray<?> sa : arrays) totalSize += sa.size();
                    if (totalSize > 200) continue;

                    // Must contain at least one preserved ID key
                    boolean hasPreservedKey = false;
                    for (android.util.SparseArray<?> sa : arrays) {
                        for (Integer id : deletedIds) {
                            if (sa.indexOfKey(id) >= 0) { hasPreservedKey = true; break; }
                        }
                        if (hasPreservedKey) break;
                    }
                    if (!hasPreservedKey) continue;

                    for (android.util.SparseArray<?> sa : arrays) {
                        for (Integer id : deletedIds) {
                            if (sa.indexOfKey(id) >= 0) {
                                sa.delete(id);
                                clearedAny = true;
                            }
                        }
                    }
                    break outer;
                }
                cls = cls.getSuperclass();
            }

            if (!clearedAny) return;

            // ── Step 2: Check if all selection arrays are now empty ──────────────
            boolean allEmpty = true;
            cls = chatActivityClass;
            outerCheck:
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
                        if (sa.size() > 0) { allEmpty = false; break outerCheck; }
                    }
                }
                cls = cls.getSuperclass();
            }

            // ── Step 3: Hide action mode bar if selection is fully cleared ───────
            if (allEmpty) hideActionMode(chatActivity, chatActivityClass);

            // ── Step 4: Refresh visible cells so circles disappear immediately ───
            refreshChatAdapter(chatActivity, chatActivityClass);

        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    /**
     * Calls hideActionMode on the ChatActivity's action bar.
     *
     * Priority:
     *   1. AutomationResolver name → direct call on chatActivity
     *   2. Raw "hideActionMode" → direct call on chatActivity
     *   3. "actionBar" field via AutomationResolver → hideActionMode on it
     *   4. Type-scan for ActionBar field → hideActionMode on it
     */
    private static void hideActionMode(Object chatActivity, Class<?> chatActivityClass) {
        // 1. Direct call on ChatActivity (resolved name)
        try {
            de.robv.android.xposed.XposedHelpers.callMethod(chatActivity,
                    AutomationResolver.resolve("ChatActivity", "hideActionMode",
                            AutomationResolver.ResolverType.Method));
            return;
        } catch (Throwable ignored) {}

        // 2. Direct call on ChatActivity (raw name)
        try {
            de.robv.android.xposed.XposedHelpers.callMethod(chatActivity, "hideActionMode");
            return;
        } catch (Throwable ignored) {}

        // 3. actionBar field via AutomationResolver
        try {
            String fieldName = AutomationResolver.resolve("ChatActivity", "actionBar",
                    AutomationResolver.ResolverType.Field);
            Object ab = de.robv.android.xposed.XposedHelpers.getObjectField(chatActivity, fieldName);
            if (ab != null) {
                de.robv.android.xposed.XposedHelpers.callMethod(ab, "hideActionMode");
                return;
            }
        } catch (Throwable ignored) {}

        // 4. Type-scan the class hierarchy for an ActionBar-like field
        try {
            Class<?> actionBarClass = ClassLoad.getClass("org.telegram.ui.ActionBar.ActionBar");
            Class<?> cls = chatActivityClass;
            while (cls != null && !Object.class.equals(cls)) {
                for (Field f : cls.getDeclaredFields()) {
                    String typeName = f.getType().getName();
                    if (!typeName.contains("ActionBar")) continue;
                    if (actionBarClass != null && !actionBarClass.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object ab = f.get(chatActivity);
                    if (ab == null) continue;
                    try {
                        de.robv.android.xposed.XposedHelpers.callMethod(ab, "hideActionMode");
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
     * Refreshes the chat list so preserved-message cells redraw without selection circles.
     *
     * Priority (most to least targeted):
     *   1. updateVisibleRows() on ChatActivity — the exact method NagramX calls after
     *      multi-selection changes; triggers per-cell visual update without data reload.
     *   2. chatAdapter field (AutomationResolver → raw name) → notifyDataSetChanged(true)
     *   3. chatListView field (AutomationResolver → raw name) → adapter.notifyDataSetChanged(true)
     *   4. Type-scan all RecyclerView/RecyclerListView fields → invalidate + notify all
     */
    private static void refreshChatAdapter(Object chatActivity, Class<?> chatActivityClass) {
        // 1. updateVisibleRows() — preferred: refreshes cell views in-place
        try {
            de.robv.android.xposed.XposedHelpers.callMethod(chatActivity,
                    AutomationResolver.resolve("ChatActivity", "updateVisibleRows",
                            AutomationResolver.ResolverType.Method));
            return;
        } catch (Throwable ignored) {}
        try {
            de.robv.android.xposed.XposedHelpers.callMethod(chatActivity, "updateVisibleRows");
            return;
        } catch (Throwable ignored) {}

        // 2. chatAdapter field → notifyDataSetChanged(true)
        try {
            String adapterField = AutomationResolver.resolve("ChatActivity", "chatAdapter",
                    AutomationResolver.ResolverType.Field);
            Object adapter = de.robv.android.xposed.XposedHelpers.getObjectField(chatActivity, adapterField);
            if (adapter != null) {
                notifyAdapter(adapter);
                return;
            }
        } catch (Throwable ignored) {}

        // 3. chatListView field → get adapter → notifyDataSetChanged(true)
        try {
            String listField = AutomationResolver.resolve("ChatActivity", "chatListView",
                    AutomationResolver.ResolverType.Field);
            Object rv = de.robv.android.xposed.XposedHelpers.getObjectField(chatActivity, listField);
            if (rv != null) {
                try { de.robv.android.xposed.XposedHelpers.callMethod(rv, "invalidate"); } catch (Throwable ignored) {}
                try {
                    Object adapter = de.robv.android.xposed.XposedHelpers.callMethod(rv, "getAdapter");
                    if (adapter != null) { notifyAdapter(adapter); return; }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // 4. Type-scan: refresh ALL RecyclerView-like fields found on ChatActivity
        try {
            Class<?> cls = chatActivityClass;
            while (cls != null && !Object.class.equals(cls)) {
                for (Field f : cls.getDeclaredFields()) {
                    String typeName = f.getType().getName();
                    if (!typeName.contains("RecyclerListView")
                            && !typeName.contains("RecyclerView")
                            && !typeName.contains("ChatListRecyclerView")) continue;
                    f.setAccessible(true);
                    Object rv = f.get(chatActivity);
                    if (rv == null) continue;
                    try { de.robv.android.xposed.XposedHelpers.callMethod(rv, "invalidate"); } catch (Throwable ignored) {}
                    try {
                        Object adapter = de.robv.android.xposed.XposedHelpers.callMethod(rv, "getAdapter");
                        if (adapter != null) notifyAdapter(adapter);
                    } catch (Throwable ignored) {}
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    /**
     * Calls notifyDataSetChanged on an adapter.
     * Telegram's ChatActivityAdapter overrides notifyDataSetChanged(boolean); tries
     * that first, falls back to the no-arg RecyclerView.Adapter base method.
     */
    private static void notifyAdapter(Object adapter) {
        try {
            de.robv.android.xposed.XposedHelpers.callMethod(adapter, "notifyDataSetChanged", true);
        } catch (Throwable ignored) {
            try {
                de.robv.android.xposed.XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
            } catch (Throwable ignored2) {}
        }
    }

    // ── Hook 2: processUpdateArray ────────────────────────────────────────────

    public static void init() {
        try {
            if (ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER) == null) return;

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
                                        if (bucket == null) { bucket = new ArrayList<>(); pendingDeletes.put(0L, bucket); }
                                        bucket.addAll(ids);
                                    }
                                } else if (itemCls.equals(ClassLoad.getClass(ClassNames.TL_UPDATE_DELETE_CHANNEL_MESSAGES))) {
                                    TLRPC.TL_updateDeleteChannelMessages cm =
                                            new TLRPC.TL_updateDeleteChannelMessages(item);
                                    long key = -cm.getChannelID();
                                    ArrayList<Integer> ids = cm.getMessages();
                                    if (ids != null && !ids.isEmpty()) {
                                        ArrayList<Integer> bucket = pendingDeletes.get(key);
                                        if (bucket == null) { bucket = new ArrayList<>(); pendingDeletes.put(key, bucket); }
                                        bucket.addAll(ids);
                                    }
                                }
                            }

                            if (pendingDeletes.isEmpty()) return;
                            LongSparseArray dialogMessage = mc.getDialogMessage();
                            for (Map.Entry<Long, ArrayList<Integer>> entry : pendingDeletes.entrySet()) {
                                markFlagDeletedInDialogs(dialogMessage, entry.getKey(), entry.getValue());
                                markMessagesDeletedForController(mc.getMessagesStorage(), entry.getKey(), entry.getValue());
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
                                    if (filterPreservedIds(ids)) param.setResult(null);
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
                Logger.w("ShowDeletedMessages: removeDeletedMessagesFromNotifications not found. " + Utils.issue);
            } else {
                final Method finalRemove = removeFromNotif;
                HMethod.hookMethod(finalRemove, new AbstractMethodHook() {
                    @Override
                    protected void beforeMethod(MethodHookParam param) {
                        if (ConfigManager.showDeletedMessages.isEnable()) param.setResult(null);
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
                                ArrayList<Integer> msgIds = Utils.castList(param.args[0], Integer.class);
                                if (msgIds == null || msgIds.isEmpty()) return;

                                // Register ALL selected IDs into deletedIds immediately so
                                // Hook 4's filter strips them from the messagesDeleted notification.
                                for (Integer id : msgIds) {
                                    if (!deletedIds.contains(id)) deletedIds.add(id);
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
            // beforeMethod: Strip preserved IDs from args so ChatActivity sees [] and
            //   removes nothing from the adapter.  Notification still fires so ChatActivity
            //   runs its handler (important for non-selection-related cleanup paths).
            //
            // afterMethod (Bug 2 fix): ChatActivity processed [] and did nothing about
            //   selection mode.  We now force-exit it by:
            //   (a) Getting the ChatActivity observer from NotificationCenter.getObservers()
            //   (b) Clearing its selectedMessagesIds for our preserved IDs
            //   (c) Calling hideActionMode() so the action bar returns to normal
            //   (d) Calling updateVisibleRows() / notifyDataSetChanged(true) so the
            //       selection circles vanish immediately without requiring a scroll.
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
                    if ((message.getFlags() & FLAG_DELETED) != 0) param.setResult(0);
                }
            }
        );
    }
}
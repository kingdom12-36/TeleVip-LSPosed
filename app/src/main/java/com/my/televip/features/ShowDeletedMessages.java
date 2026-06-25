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
 * Hooks:
 *  1. deleteMessages (beforeMethod) — registers all selected IDs into deletedIds immediately.
 *  2. processUpdateArray (beforeMethod) — handles server-ack deletions.
 *  3. markMessagesAsDeleted (beforeMethod) — filters preserved IDs out; blocks if empty.
 *  4. postNotificationName for messagesDeleted:
 *       beforeMethod — strips preserved IDs so ChatActivity sees [].
 *       afterMethod  — force-exits selection mode + refreshes cells immediately:
 *                      hideActionMode via duck-typing (tries hideActionMode() on every
 *                      View field until one accepts it — survives obfuscation because
 *                      method names are preserved as interface contracts).
 *                      Cell refresh via duck-typing (tries getAdapter() on every View
 *                      field to find the RecyclerView — does NOT rely on class names
 *                      which ProGuard renames, only on method names which it cannot).
 *  5. removeDeletedMessagesFromNotifications — blocked.
 *  6. DownloadController.canDownloadMedia — blocked for FLAG_DELETED messages.
 */
public class ShowDeletedMessages {

    public static final int FLAG_DELETED = 1 << 31;
    public static final CopyOnWriteArrayList<Integer> deletedIds = new CopyOnWriteArrayList<>();
    public static boolean isEnable = false;

    // ── DB helper ─────────────────────────────────────────────────────────────

    public static void markMessagesDeletedForController(MessagesStorage messagesStorage,
                                                        long dialogId,
                                                        ArrayList<Integer> delMsg) {
        MessageStorage.markMessagesDeleted(messagesStorage, dialogId, delMsg);
    }

    // ── Flag / filter helpers ─────────────────────────────────────────────────

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
        Iterator<Integer> it = ids.iterator();
        while (it.hasNext()) { if (deletedIds.contains(it.next())) it.remove(); }
        return ids.isEmpty();
    }

    // ── Selection-mode exit (Bug 2 fix) ──────────────────────────────────────

    private static ArrayList<?> getObserversForId(Object nc, int notifId) {
        try {
            String m = AutomationResolver.resolve("NotificationCenter", "getObservers", AutomationResolver.ResolverType.Method);
            Object r = de.robv.android.xposed.XposedHelpers.callMethod(nc, m, notifId);
            if (r instanceof ArrayList) return (ArrayList<?>) r;
        } catch (Throwable ignored) {}
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

    private static void forceClearSelectionMode(Object ncInstance) {
        try {
            ArrayList<?> observers = getObserversForId(ncInstance, NotificationCenter.getMessagesDeleted());
            if (observers == null || observers.isEmpty()) return;
            Class<?> caClass = ClassLoad.getClass(ClassNames.CHAT_ACTIVITY);
            if (caClass == null) return;
            for (Object obs : new ArrayList<>(observers)) {
                if (obs == null || !caClass.isInstance(obs)) continue;
                clearSelectionOnChatActivity(obs, caClass);
            }
        } catch (Throwable t) { Logger.e(t); }
    }

    /**
     * Clears selectedMessagesIds for preserved IDs, hides the action bar, and
     * forces cells to redraw with the X indicator and without selection circles.
     *
     * selectedMessagesIds identification: the SparseArray[] whose every key is
     * contained in deletedIds (the selected messages we preserved).  messagesDict
     * has hundreds of keys, most NOT in deletedIds, so it is reliably excluded.
     */
    private static void clearSelectionOnChatActivity(Object ca, Class<?> caClass) {
        try {
            boolean clearedAny = false;

            // Step 1: find + clear selectedMessagesIds
            Class<?> cls = caClass;
            outer:
            while (cls != null && !Object.class.equals(cls)) {
                for (Field f : cls.getDeclaredFields()) {
                    if (!f.getType().isArray()) continue;
                    Class<?> comp = f.getType().getComponentType();
                    if (comp == null || !android.util.SparseArray.class.isAssignableFrom(comp)) continue;
                    f.setAccessible(true);
                    Object val = f.get(ca);
                    if (!(val instanceof android.util.SparseArray[])) continue;
                    android.util.SparseArray<?>[] arrays = (android.util.SparseArray<?>[]) val;

                    // Must be non-empty
                    int total = 0; for (android.util.SparseArray<?> sa : arrays) total += sa.size();
                    if (total == 0) continue;

                    // Every key in this SparseArray[] must be in deletedIds.
                    // selectedMessagesIds holds exactly the selected messages (all in deletedIds).
                    // messagesDict holds ALL loaded messages — most keys NOT in deletedIds.
                    boolean allKeysInDeletedIds = true;
                    outer2:
                    for (android.util.SparseArray<?> sa : arrays) {
                        for (int k = 0; k < sa.size(); k++) {
                            if (!deletedIds.contains(sa.keyAt(k))) {
                                allKeysInDeletedIds = false;
                                break outer2;
                            }
                        }
                    }
                    if (!allKeysInDeletedIds) continue;

                    // This is selectedMessagesIds — clear our preserved IDs from it
                    for (android.util.SparseArray<?> sa : arrays) {
                        for (Integer id : deletedIds) {
                            if (sa.indexOfKey(id) >= 0) { sa.delete(id); clearedAny = true; }
                        }
                    }
                    break outer;
                }
                cls = cls.getSuperclass();
            }

            if (!clearedAny) return;

            // Step 2: check if all arrays are empty → hide action mode
            boolean allEmpty = true;
            cls = caClass;
            outerEmpty:
            while (cls != null && !Object.class.equals(cls)) {
                for (Field f : cls.getDeclaredFields()) {
                    if (!f.getType().isArray()) continue;
                    Class<?> comp = f.getType().getComponentType();
                    if (comp == null || !android.util.SparseArray.class.isAssignableFrom(comp)) continue;
                    f.setAccessible(true);
                    Object val = f.get(ca);
                    if (!(val instanceof android.util.SparseArray[])) continue;
                    android.util.SparseArray<?>[] arrays = (android.util.SparseArray<?>[]) val;
                    // Only check arrays that could be selectedMessagesIds (not messagesDict)
                    int total = 0; for (android.util.SparseArray<?> sa : arrays) total += sa.size();
                    if (total > 200) continue; // messagesDict has many more entries
                    for (android.util.SparseArray<?> sa : arrays) {
                        if (sa.size() > 0) { allEmpty = false; break outerEmpty; }
                    }
                }
                cls = cls.getSuperclass();
            }

            if (allEmpty) hideActionMode(ca, caClass);

            // Step 3: refresh visible cells
            refreshVisibleCells(ca, caClass);

        } catch (Throwable t) { Logger.e(t); }
    }

    /**
     * Hides the chat action mode bar (the toolbar that appears when messages are selected).
     *
     * Uses duck-typing: tries hideActionMode() on every android.view.View field found in
     * ChatActivity's class hierarchy, stopping at the first success.  This works in
     * obfuscated builds because:
     *   - android.view.View is a framework class that cannot be renamed by ProGuard
     *   - hideActionMode() is Telegram's own ActionBar method whose name may be kept
     *     because it's called from many places throughout the codebase
     * Falls back to AutomationResolver name resolution for builds where it IS registered.
     */
    private static void hideActionMode(Object ca, Class<?> caClass) {
        // Priority 1: AutomationResolver → direct call on ChatActivity
        try {
            de.robv.android.xposed.XposedHelpers.callMethod(ca,
                    AutomationResolver.resolve("ChatActivity", "hideActionMode", AutomationResolver.ResolverType.Method));
            return;
        } catch (Throwable ignored) {}

        // Priority 2: raw name → direct call on ChatActivity
        try { de.robv.android.xposed.XposedHelpers.callMethod(ca, "hideActionMode"); return; }
        catch (Throwable ignored) {}

        // Priority 3: AutomationResolver for actionBar field → hideActionMode on it
        try {
            Object ab = de.robv.android.xposed.XposedHelpers.getObjectField(ca,
                    AutomationResolver.resolve("ChatActivity", "actionBar", AutomationResolver.ResolverType.Field));
            if (ab != null) { de.robv.android.xposed.XposedHelpers.callMethod(ab, "hideActionMode"); return; }
        } catch (Throwable ignored) {}

        // Priority 4: duck-typing — try hideActionMode() on every View field in the class hierarchy.
        // The ActionBar is the only View in ChatActivity that has this method.
        try {
            Class<?> cls = caClass;
            while (cls != null && !Object.class.equals(cls)) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    try {
                        Object val = f.get(ca);
                        if (!(val instanceof android.view.View)) continue;
                        de.robv.android.xposed.XposedHelpers.callMethod(val, "hideActionMode");
                        return; // succeeded
                    } catch (Throwable ignored) {}
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable t) { Logger.e(t); }
    }

    /**
     * Forces all currently-visible chat message cells to redraw immediately, making
     * the X indicator appear and selection circles disappear without requiring a scroll.
     *
     * The main chat RecyclerView is found by duck-typing:
     *   - Walk every field in ChatActivity's class hierarchy
     *   - Filter to fields whose value is an android.view.View (framework class, never renamed)
     *   - Try calling getAdapter() on each — only RecyclerView/ListView have this method
     *   - Among matching views, pick the one whose adapter reports the most items (= chat list)
     *
     * This works in obfuscated builds where the RecyclerListView subclass name is renamed,
     * because getAdapter() / getItemCount() / getChildCount() / getChildAt() / invalidate()
     * are all standard Android or RecyclerView API methods that ProGuard cannot rename.
     *
     * Two refresh paths:
     *   (a) Invalidate each visible child — forces redraw at next vsync so cells call
     *       measureTime (our hook) which draws the X-in-timestamp; also redraws circles
     *       based on the now-cleared selectedMessagesIds.
     *   (b) notifyDataSetChanged on the adapter — triggers onBindViewHolder so cells call
     *       setMessageObject (our hook) which applies the X-in-text prefix.
     */
    private static void refreshVisibleCells(Object ca, Class<?> caClass) {
        // Collect all RecyclerView-like views via duck-typing
        List<Object> recyclerViews = new ArrayList<>();
        try {
            Class<?> cls = caClass;
            while (cls != null && !Object.class.equals(cls)) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    try {
                        Object val = f.get(ca);
                        if (!(val instanceof android.view.View)) continue;
                        // Duck-type: does it have getAdapter()?
                        Object adapter = de.robv.android.xposed.XposedHelpers.callMethod(val, "getAdapter");
                        if (adapter != null) recyclerViews.add(val);
                    } catch (Throwable ignored) {}
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable ignored) {}

        if (recyclerViews.isEmpty()) return;

        // Pick the one with the most adapter items = main chat list
        Object mainRv = null;
        int maxItems = -1;
        for (Object rv : recyclerViews) {
            try {
                Object adapter = de.robv.android.xposed.XposedHelpers.callMethod(rv, "getAdapter");
                int count = (int) de.robv.android.xposed.XposedHelpers.callMethod(adapter, "getItemCount");
                if (count > maxItems) { maxItems = count; mainRv = rv; }
            } catch (Throwable ignored) {}
        }
        if (mainRv == null) mainRv = recyclerViews.get(0);

        // (a) Invalidate each visible child — immediate redraw (circles + X-in-timestamp)
        try {
            int childCount = (int) de.robv.android.xposed.XposedHelpers.callMethod(mainRv, "getChildCount");
            for (int i = 0; i < childCount; i++) {
                try {
                    Object child = de.robv.android.xposed.XposedHelpers.callMethod(mainRv, "getChildAt", i);
                    if (child != null) de.robv.android.xposed.XposedHelpers.callMethod(child, "invalidate");
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // (b) notifyDataSetChanged — triggers onBindViewHolder (X-in-text via setMessageObject hook)
        try {
            Object adapter = de.robv.android.xposed.XposedHelpers.callMethod(mainRv, "getAdapter");
            if (adapter != null) {
                // Telegram's ChatActivityAdapter has notifyDataSetChanged(boolean); try it first
                try { de.robv.android.xposed.XposedHelpers.callMethod(adapter, "notifyDataSetChanged", true); }
                catch (Throwable e) { de.robv.android.xposed.XposedHelpers.callMethod(adapter, "notifyDataSetChanged"); }
            }
        } catch (Throwable ignored) {}
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
                        && m.getParameterTypes()[4] == int.class)
                    candidates.add(m.getName());
            }
            if (candidates.size() != 1) {
                Logger.w("ShowDeletedMessages: processUpdateArray hook failed — "
                        + (candidates.isEmpty() ? "no match" : "ambiguous") + ". " + Utils.issue);
                return;
            }
            HMethod.hookMethod(
                ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER), candidates.get(0),
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
                                Class<?> ic = item.getClass();
                                if (ic.equals(ClassLoad.getClass(ClassNames.TL_UPDATE_DELETE_MESSAGES))) {
                                    ArrayList<Integer> ids = new TLRPC.TL_updateDeleteMessages(item).getMessages();
                                    if (ids != null && !ids.isEmpty()) {
                                        ArrayList<Integer> b = pendingDeletes.get(0L);
                                        if (b == null) { b = new ArrayList<>(); pendingDeletes.put(0L, b); }
                                        b.addAll(ids);
                                    }
                                } else if (ic.equals(ClassLoad.getClass(ClassNames.TL_UPDATE_DELETE_CHANNEL_MESSAGES))) {
                                    TLRPC.TL_updateDeleteChannelMessages cm = new TLRPC.TL_updateDeleteChannelMessages(item);
                                    long key = -cm.getChannelID();
                                    ArrayList<Integer> ids = cm.getMessages();
                                    if (ids != null && !ids.isEmpty()) {
                                        ArrayList<Integer> b = pendingDeletes.get(key);
                                        if (b == null) { b = new ArrayList<>(); pendingDeletes.put(key, b); }
                                        b.addAll(ids);
                                    }
                                }
                            }
                            if (pendingDeletes.isEmpty()) return;
                            LongSparseArray dm = mc.getDialogMessage();
                            for (Map.Entry<Long, ArrayList<Integer>> e : pendingDeletes.entrySet()) {
                                markFlagDeletedInDialogs(dm, e.getKey(), e.getValue());
                                markMessagesDeletedForController(mc.getMessagesStorage(), e.getKey(), e.getValue());
                            }
                        } catch (Throwable t) { Logger.e(t); }
                    }
                }
            );
        } catch (Throwable e) { Logger.e(e); }
    }

    // ── Hooks 1, 3, 4, 5, 6 ──────────────────────────────────────────────────

    public static void initProcessing() {
        try {
            if (!isEnable) {
                isEnable = true;

                // Hook 3: markMessagesAsDeleted — filter, don't block
                HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.MESSAGES_STORAGE),
                    AutomationResolver.resolve("MessagesStorage", "markMessagesAsDeleted", AutomationResolver.ResolverType.Method),
                    AutomationResolver.merge(
                        AutomationResolver.resolveObject("markMessagesAsDeleted",
                            new Class[]{long.class, java.util.ArrayList.class, boolean.class, boolean.class, int.class, int.class}),
                        new AbstractMethodHook() {
                            @Override
                            protected void beforeMethod(MethodHookParam param) {
                                if (!ConfigManager.showDeletedMessages.isEnable()) return;
                                try {
                                    @SuppressWarnings("unchecked")
                                    ArrayList<Integer> ids = (ArrayList<Integer>) param.args[1];
                                    if (filterPreservedIds(ids)) param.setResult(null);
                                } catch (Throwable t) { Logger.e(t); param.setResult(null); }
                            }
                        }
                    )
                );
            }

            // Hook 5: removeDeletedMessagesFromNotifications
            Method removeFromNotif = null;
            for (Method m : ClassLoad.getClass(ClassNames.NOTIFICATIONS_CONTROLLER).getDeclaredMethods()) {
                if (m.getName().equals(AutomationResolver.resolve("NotificationsController",
                        "removeDeletedMessagesFromNotifications", AutomationResolver.ResolverType.Method))) {
                    removeFromNotif = m; break;
                }
            }
            if (removeFromNotif == null) {
                Logger.w("ShowDeletedMessages: removeDeletedMessagesFromNotifications not found. " + Utils.issue);
            } else {
                final Method fr = removeFromNotif;
                HMethod.hookMethod(fr, new AbstractMethodHook() {
                    @Override
                    protected void beforeMethod(MethodHookParam param) {
                        if (ConfigManager.showDeletedMessages.isEnable()) param.setResult(null);
                    }
                });
            }

            // Hook 1: deleteMessages — mark FLAG_DELETED before original runs
            HMethod.hookMethod(
                ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER),
                AutomationResolver.resolve("MessagesController", "deleteMessages", AutomationResolver.ResolverType.Method),
                AutomationResolver.merge(
                    AutomationResolver.resolveObject("deleteMessages", new Class[]{
                        java.util.ArrayList.class, java.util.ArrayList.class,
                        ClassLoad.getClass(ClassNames.TLRPC_ENCRYPTED_CHAT),
                        long.class, boolean.class, int.class, boolean.class,
                        long.class, ClassLoad.getClass(ClassNames.TL_OBJECT),
                        int.class, boolean.class, int.class
                    }),
                    new AbstractMethodHook() {
                        @Override
                        protected void beforeMethod(MethodHookParam param) {
                            if (ConfigManager.showDeletedMessages == null || !ConfigManager.showDeletedMessages.isEnable()) return;
                            try {
                                ArrayList<Integer> msgIds = Utils.castList(param.args[0], Integer.class);
                                if (msgIds == null || msgIds.isEmpty()) return;
                                for (Integer id : msgIds) if (!deletedIds.contains(id)) deletedIds.add(id);
                                markFlagDeletedInDialogs(new MessagesController(param.thisObject).getDialogMessage(), 0L, msgIds);
                            } catch (Throwable t) { Logger.e(t); }
                        }
                    }
                )
            );

            // Hook 4: postNotificationName for messagesDeleted
            //
            // beforeMethod — strip preserved IDs so ChatActivity sees [] and nothing
            //   is removed from the adapter.
            //
            // afterMethod — ChatActivity processed [] (did nothing about selection mode).
            //   We fix this via two duck-typed operations:
            //
            //   hideActionMode(): scans every View field in ChatActivity and calls
            //   hideActionMode() on each until one succeeds.  Telegram's ActionBar is the
            //   only View that accepts this call.  This approach works even when ProGuard
            //   has renamed the ActionBar class, because method names are preserved as
            //   interface/protocol contracts called from throughout the codebase.
            //
            //   refreshVisibleCells(): scans every View field, finds RecyclerViews by
            //   calling getAdapter() on each (only RV/LV have this method), picks the
            //   largest adapter (= main chat list), then (a) invalidates all visible
            //   children (immediate redraw — circles use cleared selectedMessagesIds,
            //   X-in-timestamp drawn by measureTime hook) and (b) notifyDataSetChanged
            //   (triggers onBindViewHolder — X-in-text applied by setMessageObject hook).
            HMethod.hookMethod(
                ClassLoad.getClass(ClassNames.NOTIFICATION_CENTER),
                AutomationResolver.resolve("NotificationCenter", "postNotificationName", AutomationResolver.ResolverType.Method),
                AutomationResolver.merge(
                    AutomationResolver.resolveObject("postNotificationName", new Class[]{int.class, Object[].class}),
                    new AbstractMethodHook() {
                        @Override
                        protected void beforeMethod(MethodHookParam param) {
                            if (!ConfigManager.showDeletedMessages.isEnable()) return;
                            if ((int) param.args[0] != NotificationCenter.getMessagesDeleted()) return;
                            try {
                                Object[] notifArgs = (Object[]) param.args[1];
                                if (notifArgs == null || notifArgs.length == 0) return;
                                @SuppressWarnings("unchecked")
                                ArrayList<Integer> ids = (ArrayList<Integer>) notifArgs[0];
                                filterPreservedIds(ids);
                            } catch (Throwable t) { Logger.e(t); }
                        }

                        @Override
                        protected void afterMethod(MethodHookParam param) {
                            if (!ConfigManager.showDeletedMessages.isEnable()) return;
                            if ((int) param.args[0] != NotificationCenter.getMessagesDeleted()) return;
                            if (deletedIds.isEmpty()) return;
                            try { forceClearSelectionMode(param.thisObject); }
                            catch (Throwable t) { Logger.e(t); }
                        }
                    }
                )
            );

            ShowDeletedMessages.init();
            ShowDeletedMessages.initAutoDownload();
        } catch (Throwable e) { Logger.e(e); }

        if (ConfigManager.showDeletedMessages.isEnable() && !ChatMessageCell.isEnable)
            ChatMessageCell.init();
    }

    // Hook 6: DownloadController
    public static void initAutoDownload() {
        if (ClassLoad.getClass(ClassNames.DOWNLOAD_CONTROLLER) == null) return;
        HMethod.hookMethod(
            ClassLoad.getClass(ClassNames.DOWNLOAD_CONTROLLER),
            AutomationResolver.resolve("DownloadController", "canDownloadMedia", AutomationResolver.ResolverType.Method),
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
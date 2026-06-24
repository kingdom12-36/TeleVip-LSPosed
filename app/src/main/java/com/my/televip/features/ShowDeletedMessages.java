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
 *       afterMethod  — force-exits selection mode and refreshes visible cells immediately.
 *  5. removeDeletedMessagesFromNotifications — blocked.
 *  6. DownloadController.canDownloadMedia — blocked for FLAG_DELETED messages.
 */
public class ShowDeletedMessages {

    public static final int FLAG_DELETED = 1 << 31;
    public static final CopyOnWriteArrayList<Integer> deletedIds = new CopyOnWriteArrayList<>();
    public static boolean isEnable = false;

    // Cached RecyclerView base class (loaded once, survives obfuscation because
    // androidx/support libraries keep their original class names in the APK).
    private static Class<?> sRecyclerViewClass = null;

    private static Class<?> getRecyclerViewClass() {
        if (sRecyclerViewClass != null) return sRecyclerViewClass;
        try { sRecyclerViewClass = Class.forName("androidx.recyclerview.widget.RecyclerView"); return sRecyclerViewClass; } catch (Throwable ignored) {}
        try { sRecyclerViewClass = Class.forName("android.support.v7.widget.RecyclerView");   return sRecyclerViewClass; } catch (Throwable ignored) {}
        return null;
    }

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

    /** Gets the list of NotificationCenter observers for a given notification ID. */
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

    /** Called from Hook 4 afterMethod — finds ChatActivity observers and fixes them. */
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
     * Clears selectedMessagesIds for our preserved IDs, hides the action bar,
     * then forces an immediate visual refresh of all visible cells.
     *
     * selectedMessagesIds vs messagesDict heuristic:
     *   Both are SparseArray[] on ChatActivity.  selectedMessagesIds has ≤ 200 entries
     *   total AND contains keys that are in deletedIds.  messagesDict has hundreds of
     *   entries and most keys are NOT in deletedIds.
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
                    int total = 0; for (android.util.SparseArray<?> sa : arrays) total += sa.size();
                    if (total > 200) continue; // messagesDict has many more entries
                    boolean hasKey = false;
                    for (android.util.SparseArray<?> sa : arrays)
                        for (Integer id : deletedIds) if (sa.indexOfKey(id) >= 0) { hasKey = true; break; }
                    if (!hasKey) continue;
                    for (android.util.SparseArray<?> sa : arrays)
                        for (Integer id : deletedIds) if (sa.indexOfKey(id) >= 0) { sa.delete(id); clearedAny = true; }
                    break outer;
                }
                cls = cls.getSuperclass();
            }

            if (!clearedAny) return;

            // Step 2: check all-empty then hide action mode
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
                    int total = 0; for (android.util.SparseArray<?> sa : arrays) total += sa.size();
                    if (total > 200) continue;
                    for (android.util.SparseArray<?> sa : arrays)
                        if (sa.size() > 0) { allEmpty = false; break outerEmpty; }
                }
                cls = cls.getSuperclass();
            }

            if (allEmpty) hideActionMode(ca, caClass);

            // Step 3: refresh all visible cells immediately
            refreshVisibleCells(ca, caClass);

        } catch (Throwable t) { Logger.e(t); }
    }

    private static void hideActionMode(Object ca, Class<?> caClass) {
        // Try direct call on ChatActivity (resolved + raw)
        try { de.robv.android.xposed.XposedHelpers.callMethod(ca, AutomationResolver.resolve("ChatActivity", "hideActionMode", AutomationResolver.ResolverType.Method)); return; } catch (Throwable ignored) {}
        try { de.robv.android.xposed.XposedHelpers.callMethod(ca, "hideActionMode"); return; } catch (Throwable ignored) {}
        // Try actionBar field via AutomationResolver
        try {
            Object ab = de.robv.android.xposed.XposedHelpers.getObjectField(ca, AutomationResolver.resolve("ChatActivity", "actionBar", AutomationResolver.ResolverType.Field));
            if (ab != null) { de.robv.android.xposed.XposedHelpers.callMethod(ab, "hideActionMode"); return; }
        } catch (Throwable ignored) {}
        // Type-scan for ActionBar field in class hierarchy
        try {
            Class<?> abClass = ClassLoad.getClass("org.telegram.ui.ActionBar.ActionBar");
            Class<?> cls = caClass;
            while (cls != null && !Object.class.equals(cls)) {
                for (Field f : cls.getDeclaredFields()) {
                    if (!f.getType().getName().contains("ActionBar")) continue;
                    if (abClass != null && !abClass.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object ab = f.get(ca);
                    if (ab == null) continue;
                    try { de.robv.android.xposed.XposedHelpers.callMethod(ab, "hideActionMode"); return; } catch (Throwable ignored) {}
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable t) { Logger.e(t); }
    }

    /**
     * Finds every RecyclerView in ChatActivity and immediately:
     *   (a) invalidates all currently-visible child views — forces redraw so circles
     *       vanish and the X-in-timestamp appears (measureTime hook fires on redraw)
     *   (b) calls notifyDataSetChanged() on the adapter — triggers onBindViewHolder
     *       so setMessageObject fires and the X-in-text prefix is applied
     *
     * RecyclerView detection uses the androidx/support base class via isAssignableFrom
     * rather than a string match on the type name, so it works even when Telegram's
     * RecyclerListView subclass is renamed by ProGuard.
     *
     * The "main" chat list is selected as the RecyclerView whose adapter reports the
     * most items — it will always have more entries than any overlay/panel list.
     */
    private static void refreshVisibleCells(Object ca, Class<?> caClass) {
        Class<?> rvClass = getRecyclerViewClass();

        // Collect all RecyclerView instances from ChatActivity fields
        List<Object> allRvs = new ArrayList<>();
        try {
            Class<?> cls = caClass;
            while (cls != null && !Object.class.equals(cls)) {
                for (Field f : cls.getDeclaredFields()) {
                    boolean isRv = f.getType().getName().contains("RecyclerView");
                    if (!isRv && rvClass != null) isRv = rvClass.isAssignableFrom(f.getType());
                    if (!isRv) continue;
                    f.setAccessible(true);
                    try {
                        Object rv = f.get(ca);
                        if (rv != null) allRvs.add(rv);
                    } catch (Throwable ignored) {}
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable ignored) {}

        if (allRvs.isEmpty()) return;

        // Pick the RecyclerView with the most adapter items = main chat list
        Object mainRv = null;
        int maxItems = -1;
        for (Object rv : allRvs) {
            try {
                Object adapter = de.robv.android.xposed.XposedHelpers.callMethod(rv, "getAdapter");
                if (adapter == null) continue;
                int count = (int) de.robv.android.xposed.XposedHelpers.callMethod(adapter, "getItemCount");
                if (count > maxItems) { maxItems = count; mainRv = rv; }
            } catch (Throwable ignored) {}
        }

        // Fallback: use first non-null rv if none had a countable adapter
        if (mainRv == null) mainRv = allRvs.get(0);

        // (a) Invalidate every currently-visible child — immediate redraw on next vsync
        try {
            int childCount = (int) de.robv.android.xposed.XposedHelpers.callMethod(mainRv, "getChildCount");
            for (int i = 0; i < childCount; i++) {
                try {
                    Object child = de.robv.android.xposed.XposedHelpers.callMethod(mainRv, "getChildAt", i);
                    if (child != null) de.robv.android.xposed.XposedHelpers.callMethod(child, "invalidate");
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // (b) notifyDataSetChanged so onBindViewHolder fires — applies X-in-text
        try {
            Object adapter = de.robv.android.xposed.XposedHelpers.callMethod(mainRv, "getAdapter");
            if (adapter != null) {
                // Telegram's ChatActivityAdapter overrides notifyDataSetChanged(boolean);
                // try that first, fall back to the standard no-arg RecyclerView.Adapter method.
                try { de.robv.android.xposed.XposedHelpers.callMethod(adapter, "notifyDataSetChanged", true); }
                catch (Throwable ignored) { de.robv.android.xposed.XposedHelpers.callMethod(adapter, "notifyDataSetChanged"); }
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
                if (m.getName().equals(AutomationResolver.resolve("NotificationsController", "removeDeletedMessagesFromNotifications", AutomationResolver.ResolverType.Method))) {
                    removeFromNotif = m; break;
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

            // Hook 1: deleteMessages — mark FLAG_DELETED and register all IDs before original runs
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
            // beforeMethod — strip preserved IDs so ChatActivity receives [] and removes
            //   nothing from the adapter.
            //
            // afterMethod — ChatActivity processed [] and did nothing about selection mode.
            //   Fix by:
            //   (a) finding ChatActivity via NotificationCenter.getObservers()
            //   (b) clearing its selectedMessagesIds for preserved IDs
            //   (c) calling hideActionMode() on the ActionBar
            //   (d) refreshVisibleCells(): invalidates every visible child of the main
            //       RecyclerView (circles vanish + X-in-timestamp redraws) and calls
            //       notifyDataSetChanged() so onBindViewHolder fires (X-in-text applied).
            //       The main RecyclerView is identified by the adapter with the most items,
            //       using the androidx RecyclerView base class for obfuscation-safe detection.
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
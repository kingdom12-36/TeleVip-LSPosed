package com.my.televip.features;

import android.graphics.Color;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.SparseArray;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.language.Keys;
import com.my.televip.language.Translator;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;
import com.my.televip.utils.Utils;
import com.my.televip.virtuals.ChatMessageCellDefault;
import com.my.televip.virtuals.SQLite.SQLiteCursor;
import com.my.televip.virtuals.SQLite.SQLiteDatabase;
import com.my.televip.virtuals.SQLite.SQLitePreparedStatement;
import com.my.televip.virtuals.Theme;
import com.my.televip.virtuals.androidx.LongSparseArray;
import com.my.televip.virtuals.messenger.MessageObject;
import com.my.televip.virtuals.messenger.MessagesController;
import com.my.televip.virtuals.messenger.MessagesStorage;
import com.my.televip.virtuals.messenger.NotificationCenter;
import com.my.televip.virtuals.tgnet.NativeByteBuffer;
import com.my.televip.virtuals.tgnet.TLRPC;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedHelpers;

/**
 * DontWipeMessages — keeps messages deleted by others visible.
 *
 * Supports Official Telegram AND NagramX/Nekogram.
 *
 * NagramX obfuscates field names:  flags→"k",  id→"a"
 * We NEVER read those fields through the virtual class wrappers (getFlags/getID
 * hard-code "flags"/"id").  Instead we always go through:
 *   rawFlags(obj)   uses AutomationResolver → "k" on NagramX, "flags" elsewhere
 *   rawId(obj)      uses AutomationResolver → "a" on NagramX, "id" elsewhere
 *
 * Visual mark strategy (two layers):
 *   Layer A – text prefix  "✕ " prepended to message text in Hook 1.
 *             Works everywhere; visible in the message body.
 *   Layer B – timestamp mark via ChatMessageCell.measureTime hook.
 *             Works on Official Telegram; on NagramX only if obfuscated names
 *             match Re-Telegram's Nekogram.java mappings (X50 / h7).
 */
public class DontWipeMessages {

    // ── constants ────────────────────────────────────────────────────────────
    public static final int FLAG_DELETED = 1 << 31;

    /** True only while the LOCAL user is actively deleting something. */
    private static boolean isMyOwnDelete = false;

    /**
     * In-memory set of message IDs deleted in this session.
     * Used as an authoritative fallback when flag-bit reads fail.
     */
    private static final Set<Integer> deletedIds =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // ── background DB thread ─────────────────────────────────────────────────
    private static final Handler dbHandler = new Handler(makeDbLooper());
    private static Looper makeDbLooper() {
        HandlerThread t = new HandlerThread("TeleVip-DontWipe", Process.THREAD_PRIORITY_DISPLAY);
        t.start();
        return t.getLooper();
    }

    // ── obfuscation-aware field helpers ──────────────────────────────────────

    /**
     * Read the flags int from a raw TLRPC$Message object.
     * Uses AutomationResolver so it works on NagramX ("k") and Official ("flags").
     */
    private static int rawFlags(Object rawMsg) {
        String field = AutomationResolver.resolve(
                "TLRPC$Message", "flags", AutomationResolver.ResolverType.Field);
        return XposedHelpers.getIntField(rawMsg, field);
    }

    /**
     * Write the flags int into a raw TLRPC$Message object.
     */
    private static void rawSetFlags(Object rawMsg, int flags) {
        String field = AutomationResolver.resolve(
                "TLRPC$Message", "flags", AutomationResolver.ResolverType.Field);
        XposedHelpers.setIntField(rawMsg, field, flags);
    }

    /**
     * Read the id int from a raw TLRPC$Message object.
     * Uses AutomationResolver so it works on NagramX ("a") and Official ("id").
     */
    private static int rawId(Object rawMsg) {
        String field = AutomationResolver.resolve(
                "TLRPC$Message", "id", AutomationResolver.ResolverType.Field);
        return XposedHelpers.getIntField(rawMsg, field);
    }

    // ── DB persistence ───────────────────────────────────────────────────────

    /**
     * Writes FLAG_DELETED into the serialised TL buffer stored in SQLite
     * so the mark survives app restarts.
     * flags are at byte-offset 4 in every TL_message serialisation.
     */
    private static void persistDeletedFlag(MessagesStorage ms,
                                           long dialogId,
                                           ArrayList<Integer> ids) {
        try {
            dbHandler.post(() -> {
                try {
                    SQLiteDatabase db = ms.getDatabase();
                    for (int t = 0; t < 2; t++) {
                        String table  = (t == 0) ? "messages_v2" : "messages_topics";
                        String query  = "SELECT data,mid,uid FROM " + table
                                + " WHERE " + (dialogId == 0 ? "is_channel" : "uid")
                                + " = " + dialogId
                                + " AND mid IN (" + TextUtils.join(",", ids) + ");";
                        String update = "UPDATE " + table + " SET data = ? WHERE uid = ? AND mid = ?";

                        SQLiteCursor cursor = db.queryFinalized(query, new Object[]{});
                        SQLitePreparedStatement state = db.executeFast(update);

                        while (cursor.next()) {
                            NativeByteBuffer data = cursor.byteBufferValue(0);
                            int  mid = cursor.intValue(1);
                            long uid = cursor.longValue(2);

                            data.position(4);
                            int flags = (int) data.readInt32(true);
                            flags |= FLAG_DELETED;
                            data.position(4);
                            data.writeInt32(flags);
                            data.position(0);

                            state.requery();
                            state.bindByteBuffer(1, data);
                            state.bindLong(2, uid);
                            state.bindInteger(3, mid);
                            state.step();
                            data.reuse();
                        }
                        cursor.dispose();
                        state.dispose();
                    }
                } catch (Throwable e) {
                    Logger.e(e);
                }
            });
        } catch (Throwable e) {
            Logger.e(e);
        }
    }

    // ── entry point ──────────────────────────────────────────────────────────

    public static void init() {
        try {
            hookDeletionEvents();
            hookBlockDbDeletion();
            hookOwnDeletePassthrough();
            hookUI();
            hookAutoDownload();
        } catch (Throwable e) {
            Logger.e(e);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  HOOK 1 — intercept delete updates coming from the server
    //
    //  Key fix:  flags / id are read via rawFlags() / rawId() which use
    //  AutomationResolver, so they work on NagramX where the fields are
    //  renamed to "k" / "a".
    //
    //  Also prepends "✕ " to the message text as a guaranteed visual mark
    //  (Layer A).  This is client-independent and needs no ChatMessageCell.
    // ════════════════════════════════════════════════════════════════════════

    private static void hookDeletionEvents() {
        try {
            Class<?> mcClass = ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER);
            if (mcClass == null) return;

            // find processUpdateArray: (ArrayList,ArrayList,ArrayList,boolean,int)
            List<String> found = new ArrayList<>();
            for (Method m : mcClass.getDeclaredMethods())
                if (m.getParameterCount() == 5
                        && m.getParameterTypes()[0] == ArrayList.class
                        && m.getParameterTypes()[1] == ArrayList.class
                        && m.getParameterTypes()[2] == ArrayList.class
                        && m.getParameterTypes()[3] == boolean.class
                        && m.getParameterTypes()[4] == int.class)
                    found.add(m.getName());

            if (found.size() != 1) {
                Logger.w("DontWipeMessages: processUpdateArray candidates=" + found.size() + " " + Utils.issue);
                return;
            }

            HMethod.hookMethod(mcClass, found.get(0),
                    ArrayList.class, ArrayList.class, ArrayList.class, boolean.class, int.class,
                    new AbstractMethodHook() {
                        @Override
                        protected void beforeMethod(MethodHookParam param) {
                            try {
                                if (!isEnabled()) return;
                                MessagesController mc = new MessagesController(param.thisObject);
                                List<Object> updates = Utils.castList(param.args[0], Object.class);
                                if (updates == null || updates.isEmpty()) return;

                                ArrayList<Object> keep = new ArrayList<>();
                                for (Object item : updates) {
                                    Class<?> cls = item.getClass();
                                    boolean isCh = cls.equals(ClassLoad.getClass(ClassNames.TL_UPDATE_DELETE_CHANNEL_MESSAGES));
                                    boolean isDi = cls.equals(ClassLoad.getClass(ClassNames.TL_UPDATE_DELETE_MESSAGES));

                                    if (isCh) {
                                        TLRPC.TL_updateDeleteChannelMessages upd =
                                                new TLRPC.TL_updateDeleteChannelMessages(item);
                                        long dialogId = -upd.getChannelID();

                                        // mark in-memory objects
                                        LongSparseArray map = mc.getDialogMessage();
                                        ArrayList<Object> live = map.get(dialogId);
                                        if (live != null)
                                            for (Object o : live)
                                                markDeleted(o, upd.getMessages());

                                        for (int id : upd.getMessages()) deletedIds.add(id);
                                        persistDeletedFlag(mc.getMessagesStorage(), dialogId, upd.getMessages());

                                    } else if (isDi) {
                                        TLRPC.TL_updateDeleteMessages upd =
                                                new TLRPC.TL_updateDeleteMessages(item);
                                        SparseArray<Object> byId = mc.getDialogMessagesByIds();
                                        for (int id : upd.getMessages()) {
                                            Object o = byId.get(id);
                                            if (o != null) markDeleted(o, null);
                                            deletedIds.add(id);
                                        }
                                        persistDeletedFlag(mc.getMessagesStorage(), 0, upd.getMessages());
                                    } else {
                                        keep.add(item);
                                    }
                                }
                                param.args[0] = keep;

                            } catch (Throwable e) {
                                Logger.e(e);
                            }
                        }
                    });
        } catch (Throwable e) {
            Logger.e(e);
        }
    }

    /**
     * Sets FLAG_DELETED on a MessageObject wrapper and prepends "✕ " to the
     * message text so the mark is immediately visible (Layer A).
     *
     * @param msgObj  raw Telegram MessageObject (the wrapper)
     * @param filter  if non-null, only mark if rawId is in this list
     */
    private static void markDeleted(Object msgObj, ArrayList<Integer> filter) {
        try {
            Object rawMsg = new MessageObject(msgObj).getMessageOwner().message;
            if (rawMsg == null) return;

            int id = 0;
            try { id = rawId(rawMsg); } catch (Throwable ignored) {}
            if (filter != null && !filter.contains(id)) return;

            // set FLAG_DELETED bit
            try {
                int currentFlags = rawFlags(rawMsg);
                rawSetFlags(rawMsg, currentFlags | FLAG_DELETED);
            } catch (Throwable e) {
                Logger.e(e);
            }

            // remember for this session
            if (id != 0) deletedIds.add(id);

            // prepend "✕ " to message text — Layer A (guaranteed visual mark)
            try {
                String text = (String) XposedHelpers.getObjectField(rawMsg, "message");
                if (text != null && !text.startsWith("✕ ")) {
                    XposedHelpers.setObjectField(rawMsg, "message", "✕ " + text);
                }
            } catch (Throwable ignored) {}

        } catch (Throwable e) {
            Logger.e(e);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  HOOK 2 — block MessagesStorage.markMessagesAsDeleted(...)
    // ════════════════════════════════════════════════════════════════════════

    private static void hookBlockDbDeletion() {
        try {
            if (ClassLoad.getClass(ClassNames.MESSAGES_STORAGE) == null) return;

            HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.MESSAGES_STORAGE),
                    AutomationResolver.resolve("MessagesStorage", "markMessagesAsDeleted",
                            AutomationResolver.ResolverType.Method),
                    AutomationResolver.merge(
                            AutomationResolver.resolveObject("markMessagesAsDeleted",
                                    new Class[]{long.class, ArrayList.class, boolean.class,
                                            boolean.class, int.class, int.class}),
                            new AbstractMethodHook() {
                                @Override
                                protected void beforeMethod(MethodHookParam param) {
                                    if (isEnabled() && !isMyOwnDelete) param.setResult(null);
                                }
                            }));
        } catch (Throwable e) {
            Logger.e(e);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  HOOK 3 — own-delete passthrough + silence messagesDeleted broadcast
    // ════════════════════════════════════════════════════════════════════════

    private static void hookOwnDeletePassthrough() {
        try {
            if (ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER) == null) return;

            HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER),
                    AutomationResolver.resolve("MessagesController", "deleteMessages",
                            AutomationResolver.ResolverType.Method),
                    AutomationResolver.merge(
                            AutomationResolver.resolveObject("deleteMessages", new Class[]{
                                    ArrayList.class, ArrayList.class,
                                    ClassLoad.getClass(ClassNames.TLRPC_ENCRYPTED_CHAT),
                                    long.class, boolean.class, int.class, boolean.class,
                                    long.class, ClassLoad.getClass(ClassNames.TL_OBJECT),
                                    int.class, boolean.class, int.class}),
                            new AbstractMethodHook() {
                                @Override
                                protected void beforeMethod(MethodHookParam param) {
                                    isMyOwnDelete = true;
                                }
                            }));

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
                                    if (isEnabled() && !isMyOwnDelete) {
                                        int id = (int) param.args[0];
                                        if (id == NotificationCenter.getMessagesDeleted())
                                            param.setResult(null);
                                    }
                                }
                                @Override
                                protected void afterMethod(MethodHookParam param) {
                                    isMyOwnDelete = false;
                                }
                            }));

            if (ClassLoad.getClass(ClassNames.NOTIFICATIONS_CONTROLLER) != null)
                for (Method m : ClassLoad.getClass(ClassNames.NOTIFICATIONS_CONTROLLER).getDeclaredMethods())
                    if (m.getName().equals(AutomationResolver.resolve("NotificationsController",
                            "removeDeletedMessagesFromNotifications",
                            AutomationResolver.ResolverType.Method))) {
                        HMethod.hookMethod(m, new AbstractMethodHook() {
                            @Override
                            protected void beforeMethod(MethodHookParam param) {
                                if (isEnabled()) param.setResult(null);
                            }
                        });
                        break;
                    }

        } catch (Throwable e) {
            Logger.e(e);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  HOOK 4 — timestamp mark via ChatMessageCell.measureTime  (Layer B)
    //
    //  Two-layer method discovery:
    //   1. AutomationResolver name (NagramX: "h7", Official: "measureTime")
    //   2. Reflection scan: any void method(MessageObject) in the cell class
    //
    //  Deleted detection uses rawFlags() + rawId() so NagramX obfuscation
    //  is handled correctly.
    // ════════════════════════════════════════════════════════════════════════

    private static void hookUI() {
        try {
            Class<?> cellClass = ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL);
            if (cellClass == null) {
                Logger.w("DontWipeMessages: ChatMessageCell not found — timestamp mark unavailable (text mark active)");
                return;
            }

            Class<?> msgClass = ClassLoad.getClass(ClassNames.MESSAGE_OBJECT);
            String resolvedName = AutomationResolver.resolve(
                    "ChatMessageCell", "measureTime", AutomationResolver.ResolverType.Method);

            Method target = null;

            // Layer 1: exact match by resolved name + parameter type
            try {
                if (msgClass != null) {
                    target = cellClass.getDeclaredMethod(resolvedName, msgClass);
                } else {
                    // msgClass unknown — find by name + single-param
                    for (Method m : cellClass.getDeclaredMethods())
                        if (m.getName().equals(resolvedName) && m.getParameterCount() == 1) {
                            target = m; break;
                        }
                }
            } catch (NoSuchMethodException ignored) {}

            // Layer 2: scan by signature
            if (target == null) {
                Logger.w("DontWipeMessages: measureTime '" + resolvedName + "' not found, scanning…");
                for (Class<?> c : new Class<?>[]{cellClass,
                        cellClass.getSuperclass() != null ? cellClass.getSuperclass() : cellClass}) {
                    if (c == null) continue;
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getReturnType() != void.class || m.getParameterCount() != 1) continue;
                        if (msgClass != null && m.getParameterTypes()[0] != msgClass) continue;
                        target = m;
                        Logger.w("DontWipeMessages: measureTime fallback found: " + m.getName());
                        break;
                    }
                    if (target != null) break;
                }
            }

            if (target == null) {
                Logger.w("DontWipeMessages: measureTime not found — timestamp mark unavailable");
                return;
            }

            HMethod.hookMethod(target, new AbstractMethodHook() {
                @Override
                protected void afterMethod(MethodHookParam param) {
                    try {
                        if (!isEnabled()) return;

                        Object msgArg = param.args[0];
                        if (msgArg == null) return;

                        TLRPC.Message owner = new MessageObject(msgArg).getMessageOwner();
                        if (owner == null || owner.message == null) return;

                        // check deleted — uses obfuscation-safe helpers
                        int msgId = 0;
                        try { msgId = rawId(owner.message); } catch (Throwable ignored) {}

                        boolean bySet  = msgId != 0 && deletedIds.contains(msgId);
                        boolean byFlag = false;
                        try { byFlag = (rawFlags(owner.message) & FLAG_DELETED) != 0; }
                        catch (Throwable ignored) {}

                        if (!bySet && !byFlag) return;

                        // build "✕ Deleted" spannable
                        String labelText = "✕ " + Translator.get(Keys.DontWipeMessages);
                        SpannableStringBuilder label = new SpannableStringBuilder(labelText + " ");
                        label.setSpan(new ForegroundColorSpan(Color.rgb(220, 50, 50)),
                                0, labelText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                        // read currentTimeString — try resolved name, then literal
                        String timeField = AutomationResolver.resolve(
                                "ChatMessageCell", "currentTimeString",
                                AutomationResolver.ResolverType.Field);
                        Object rawTime = null;
                        String usedField = timeField;
                        try {
                            rawTime = XposedHelpers.getObjectField(param.thisObject, timeField);
                        } catch (Throwable e1) {
                            try {
                                rawTime = XposedHelpers.getObjectField(param.thisObject, "currentTimeString");
                                usedField = "currentTimeString";
                            } catch (Throwable e2) {
                                Logger.w("DontWipeMessages: currentTimeString not accessible");
                                return;
                            }
                        }

                        // prepend label
                        SpannableStringBuilder newTime;
                        if (rawTime instanceof SpannableStringBuilder) {
                            newTime = new SpannableStringBuilder(label).append((SpannableStringBuilder) rawTime);
                        } else {
                            newTime = new SpannableStringBuilder(label)
                                    .append(rawTime != null ? rawTime.toString() : "");
                        }
                        XposedHelpers.setObjectField(param.thisObject, usedField, newTime);

                        // widen the cell so text doesn't clip
                        TextPaint paint = Theme.getTextPaint();
                        if (paint != null) {
                            try {
                                ChatMessageCellDefault cell = new ChatMessageCellDefault(param.thisObject) {};
                                int extra = (int) Math.ceil(paint.measureText(label, 0, label.length()));
                                cell.setTimeTextWidth(cell.getTimeTextWidth() + extra);
                                cell.setTimeWidth(cell.getTimeWidth() + extra);
                            } catch (Throwable ignored) {}
                        }

                    } catch (Throwable e) {
                        Logger.e(e);
                    }
                }
            });

        } catch (Throwable e) {
            Logger.e(e);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  HOOK 5 — stop auto-download for deleted media
    // ════════════════════════════════════════════════════════════════════════

    private static void hookAutoDownload() {
        try {
            if (ClassLoad.getClass(ClassNames.DOWNLOAD_CONTROLLER) == null) return;

            HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.DOWNLOAD_CONTROLLER),
                    AutomationResolver.resolve("DownloadController", "canDownloadMedia",
                            AutomationResolver.ResolverType.Method),
                    ClassLoad.getClass(ClassNames.TL_MESSAGE),
                    new AbstractMethodHook() {
                        @Override
                        protected void beforeMethod(MethodHookParam param) {
                            try {
                                Object rawMsg = param.args[0];
                                int id = 0;
                                try { id = rawId(rawMsg); } catch (Throwable ignored) {}
                                boolean deleted = id != 0 && deletedIds.contains(id);
                                try { deleted = deleted || (rawFlags(rawMsg) & FLAG_DELETED) != 0; }
                                catch (Throwable ignored) {}
                                if (deleted) param.setResult(0);
                            } catch (Throwable e) {
                                Logger.e(e);
                            }
                        }
                    });
        } catch (Throwable e) {
            Logger.e(e);
        }
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private static boolean isEnabled() {
        return ConfigManager.dontWipeMessages != null
                && ConfigManager.dontWipeMessages.isEnable();
    }
}

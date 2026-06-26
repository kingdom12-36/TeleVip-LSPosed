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
 * DontWipeMessages — single-file feature (all logic here, nothing else needed)
 *
 * Works on both Official Telegram AND NagramX/Nekogram.
 * NagramX obfuscation is handled via two layers:
 *   1. NagramX.java static mappings (class/method/field names)
 *   2. Dynamic reflection fallback in hookUI() — finds measureTime by signature
 *
 * ┌─ Hook 1: hookDeletionEvents()        intercepts delete updates from server
 * ├─ Hook 2: hookBlockDbDeletion()       blocks SQLite deletion
 * ├─ Hook 3: hookOwnDeletePassthrough()  lets user delete their own messages normally
 * ├─ Hook 4: hookUI()                    shows ✕ label next to timestamp
 * └─ Hook 5: hookAutoDownload()          stops auto-download of deleted media
 */
public class DontWipeMessages {

    // ══════════════════════════════════════════════════════════════
    //  CONSTANTS
    // ══════════════════════════════════════════════════════════════

    /** Written into TLRPC.Message.flags to mark a message as deleted-by-other */
    public static final int FLAG_DELETED = 1 << 31;

    /** Set when THE LOCAL USER is deleting — lets hooks 2 & 3 pass through */
    private static boolean isMyOwnDelete = false;

    /**
     * In-memory set of deleted message IDs for the current session.
     * Needed because in NagramX the flags field may be obfuscated and
     * reading getFlags() might not return FLAG_DELETED reliably.
     * This set is authoritative for messages deleted during this session.
     */
    private static final Set<Integer> deletedIds =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // ══════════════════════════════════════════════════════════════
    //  INLINE DB HANDLER  (no external dependency)
    // ══════════════════════════════════════════════════════════════

    private static final Handler dbHandler = new Handler(makeDbLooper());

    private static Looper makeDbLooper() {
        HandlerThread t = new HandlerThread("TeleVip-DontWipe", Process.THREAD_PRIORITY_DISPLAY);
        t.start();
        return t.getLooper();
    }

    /**
     * Writes FLAG_DELETED into the serialized TL buffer in SQLite so the mark
     * survives app restarts. Runs on a background thread.
     */
    private static void persistDeletedFlag(MessagesStorage ms, long dialogId, ArrayList<Integer> ids) {
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
                        SQLitePreparedStatement state  = db.executeFast(update);

                        while (cursor.next()) {
                            NativeByteBuffer data = cursor.byteBufferValue(0);
                            int  mid = cursor.intValue(1);
                            long uid = cursor.longValue(2);

                            // flags is at byte offset 4 in the TL message serialization
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

    // ══════════════════════════════════════════════════════════════
    //  ENTRY POINT — called from ConfigManager
    // ══════════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════════
    //  HOOK 1 — intercept deletion updates from server
    //  Catches TL_updateDeleteMessages + TL_updateDeleteChannelMessages
    //  Sets FLAG_DELETED on in-memory objects AND persists to SQLite
    //  Also adds to deletedIds set for reliable UI detection
    // ══════════════════════════════════════════════════════════════

    private static void hookDeletionEvents() {
        try {
            Class<?> mcClass = ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER);
            if (mcClass == null) return;

            // processUpdateArray: ArrayList, ArrayList, ArrayList, boolean, int
            Method[] methods = mcClass.getDeclaredMethods();
            List<String> found = new ArrayList<>();
            for (Method m : methods)
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

                                        // flag in-memory objects
                                        LongSparseArray map = mc.getDialogMessage();
                                        ArrayList<Object> live = map.get(dialogId);
                                        if (live != null)
                                            for (Object o : live) {
                                                TLRPC.Message owner = new MessageObject(o).getMessageOwner();
                                                if (upd.getMessages().contains(owner.getID())) {
                                                    owner.setFlags(owner.getFlags() | FLAG_DELETED);
                                                    deletedIds.add(owner.getID()); // reliable session marker
                                                }
                                            }

                                        for (int id : upd.getMessages()) deletedIds.add(id);
                                        persistDeletedFlag(mc.getMessagesStorage(), dialogId, upd.getMessages());

                                    } else if (isDi) {
                                        TLRPC.TL_updateDeleteMessages upd =
                                                new TLRPC.TL_updateDeleteMessages(item);

                                        SparseArray<Object> byId = mc.getDialogMessagesByIds();
                                        for (int id : upd.getMessages()) {
                                            Object o = byId.get(id);
                                            if (o != null) {
                                                TLRPC.Message owner = new MessageObject(o).getMessageOwner();
                                                owner.setFlags(owner.getFlags() | FLAG_DELETED);
                                            }
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

    // ══════════════════════════════════════════════════════════════
    //  HOOK 2 — block MessagesStorage.markMessagesAsDeleted(...)
    //  Prevents Telegram removing the message from SQLite
    // ══════════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════════
    //  HOOK 3 — own-delete passthrough
    //  Sets isMyOwnDelete=true before user's own deleteMessages call
    //  Silences the messagesDeleted broadcast for others' deletions
    // ══════════════════════════════════════════════════════════════

    private static void hookOwnDeletePassthrough() {
        try {
            if (ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER) == null) return;

            // mark own-delete start
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

            // silence messagesDeleted broadcast for others' deletions
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

            // block push notification removal
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

    // ══════════════════════════════════════════════════════════════
    //  HOOK 4 — UI: show "✕ Deleted" beside the timestamp
    //
    //  TWO-LAYER approach to find ChatMessageCell.measureTime:
    //
    //  Layer 1 (static): AutomationResolver uses NagramX.java mappings
    //    ChatMessageCell → "X50", measureTime → "h7" for NagramX
    //    This covers the specific NagramX build Re-Telegram targets.
    //
    //  Layer 2 (dynamic): If Layer 1 fails (class null or method not found),
    //    scan ALL methods of the cell class looking for:
    //    void method(MessageObject) — which is measureTime's signature
    //
    //  DELETED detection uses deletedIds set (session) OR FLAG_DELETED bit (DB)
    //
    //  currentTimeString: works for both Official (CharSequence) and NagramX
    //  (SpannableStringBuilder) — detected at runtime via instanceof
    // ══════════════════════════════════════════════════════════════

    private static void hookUI() {
        try {
            Class<?> cellClass = ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL);
            if (cellClass == null) {
                Logger.w("DontWipeMessages: ChatMessageCell class not found — check NagramX mappings");
                return;
            }

            Class<?> msgClass = ClassLoad.getClass(ClassNames.MESSAGE_OBJECT);
            String resolvedName = AutomationResolver.resolve("ChatMessageCell", "measureTime",
                    AutomationResolver.ResolverType.Method);

            // Layer 1: try by resolved name
            Method target = null;
            try {
                target = cellClass.getDeclaredMethod(resolvedName, msgClass);
                Logger.d("DontWipeMessages: measureTime found as '" + resolvedName + "'");
            } catch (NoSuchMethodException ignored) {
                // Layer 2: scan all void methods taking a single MessageObject param
                Logger.w("DontWipeMessages: measureTime not found as '" + resolvedName + "' — scanning by signature");
                for (Method m : cellClass.getDeclaredMethods()) {
                    if (m.getReturnType() == void.class
                            && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == msgClass) {
                        target = m;
                        Logger.d("DontWipeMessages: found measureTime candidate: " + m.getName());
                        break;
                    }
                }
                // Layer 2b: try superclass if not found in declared class
                if (target == null && cellClass.getSuperclass() != null) {
                    for (Method m : cellClass.getSuperclass().getDeclaredMethods()) {
                        if (m.getReturnType() == void.class
                                && m.getParameterCount() == 1
                                && m.getParameterTypes()[0] == msgClass) {
                            target = m;
                            Logger.d("DontWipeMessages: found measureTime in superclass: " + m.getName());
                            break;
                        }
                    }
                }
            }

            if (target == null) {
                Logger.w("DontWipeMessages: cannot find measureTime — deleted mark will not show");
                return;
            }

            final Method finalTarget = target;
            HMethod.hookMethod(finalTarget, new AbstractMethodHook() {
                @Override
                protected void afterMethod(MethodHookParam param) {
                    try {
                        if (!isEnabled()) return;

                        Object msgArg = param.args[0];
                        if (msgArg == null) return;

                        TLRPC.Message owner = new MessageObject(msgArg).getMessageOwner();
                        if (owner == null) return;

                        // Check BOTH sources: in-memory session set AND persisted flag bit
                        int msgId = owner.getID();
                        boolean bySet   = deletedIds.contains(msgId);
                        boolean byFlag  = false;
                        try { byFlag = (owner.getFlags() & FLAG_DELETED) != 0; } catch (Throwable ignored) {}

                        if (!bySet && !byFlag) return;

                        // ── build "✕ Deleted" label ──────────────────────
                        String labelText = "✕ " + Translator.get(Keys.DontWipeMessages);
                        SpannableStringBuilder label = new SpannableStringBuilder(labelText + " ");
                        label.setSpan(new ForegroundColorSpan(Color.rgb(220, 50, 50)),
                                0, labelText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                        // ── read currentTimeString — two field name attempts ──
                        String resolvedField = AutomationResolver.resolve("ChatMessageCell",
                                "currentTimeString", AutomationResolver.ResolverType.Field);
                        Object rawTime = null;
                        try {
                            rawTime = XposedHelpers.getObjectField(param.thisObject, resolvedField);
                        } catch (Throwable e1) {
                            try {
                                rawTime = XposedHelpers.getObjectField(param.thisObject, "currentTimeString");
                                resolvedField = "currentTimeString";
                            } catch (Throwable e2) {
                                Logger.w("DontWipeMessages: currentTimeString not accessible");
                                return;
                            }
                        }

                        // ── prepend label — handles both String and SpannableStringBuilder ──
                        SpannableStringBuilder newTime;
                        if (rawTime instanceof SpannableStringBuilder) {
                            // NagramX / Nekogram: SpannableStringBuilder
                            newTime = new SpannableStringBuilder(label).append((SpannableStringBuilder) rawTime);
                        } else {
                            // Official Telegram: String / CharSequence
                            newTime = new SpannableStringBuilder(label)
                                    .append(rawTime != null ? rawTime.toString() : "");
                        }

                        // ── write back ───────────────────────────────────
                        XposedHelpers.setObjectField(param.thisObject, resolvedField, newTime);

                        // ── widen the cell so text doesn't clip ──────────
                        TextPaint paint = Theme.getTextPaint();
                        if (paint != null) {
                            ChatMessageCellDefault cell = new ChatMessageCellDefault(param.thisObject) {};
                            int extra = (int) Math.ceil(paint.measureText(label, 0, label.length()));
                            cell.setTimeTextWidth(cell.getTimeTextWidth() + extra);
                            cell.setTimeWidth(cell.getTimeWidth() + extra);
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

    // ══════════════════════════════════════════════════════════════
    //  HOOK 5 — stop auto-download for deleted media
    // ══════════════════════════════════════════════════════════════

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
                                TLRPC.Message msg = new TLRPC.Message(param.args[0]);
                                boolean deleted = deletedIds.contains(msg.getID());
                                try { deleted = deleted || (msg.getFlags() & FLAG_DELETED) != 0; } catch (Throwable ignored) {}
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

    // ══════════════════════════════════════════════════════════════
    //  HELPER
    // ══════════════════════════════════════════════════════════════

    private static boolean isEnabled() {
        return ConfigManager.dontWipeMessages != null
                && ConfigManager.dontWipeMessages.isEnable();
    }
}

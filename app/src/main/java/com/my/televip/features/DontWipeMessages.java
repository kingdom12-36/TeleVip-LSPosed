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
import android.view.View; // تم إضافته للتلوين
import android.graphics.Canvas; // تم إضافته للتلوين

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
import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * DontWipeMessages — single-file feature
 *
 * يحفظ الرسائل المحذوفة من الطرف الثاني ويعرضها في الشات
 * مع علامة "✕ محذوفة" باللون الأحمر قرب الوقت
 *
 * ┌─ Hook 1: hookDeletionEvents()     — يلتقط حدث الحذف من السيرفر
 * ├─ Hook 2: hookBlockDbDeletion()    — يمنع حذف الرسالة من قاعدة البيانات
 * ├─ Hook 3: hookOwnDeletePassthrough() — يسمح لك تحذف رسائلك بشكل طبيعي
 * ├─ Hook 4: hookUI()                 — يضيف علامة ✕ قرب وقت الرسالة
 * ├─ Hook 5: hookAutoDownload()       — يوقف تحميل ميديا الرسائل المحذوفة
 * └─ Hook 6: hookUIBackground()       — التعديل الجديد لتلوين الرسالة باللون الأحمر الشفاف
 */
public class DontWipeMessages {

    // ══════════════════════════════════════════════════════════════
    //  CONSTANTS
    // ══════════════════════════════════════════════════════════════

    /** Bit flag written into the message flags field to mark it as deleted */
    public static final int FLAG_DELETED = 1 << 31;

    /** True only while the LOCAL user is deleting their own message */
    private static boolean isMyOwnDelete = false;

    // ══════════════════════════════════════════════════════════════
    //  INLINE DB HANDLER  (no import from MessageStorage needed)
    // ══════════════════════════════════════════════════════════════

    private static final Handler dbHandler = new Handler(makeDbLooper());

    private static Looper makeDbLooper() {
        HandlerThread t = new HandlerThread("TeleVip-DontWipe", Process.THREAD_PRIORITY_DISPLAY);
        t.start();
        return t.getLooper();
    }

    /**
     * Writes FLAG_DELETED into the serialized message data in SQLite.
     * Works on both messages_v2 and messages_topics tables.
     */
    private static void persistDeletedFlag(MessagesStorage messagesStorage, long dialogId, ArrayList<Integer> delMsg) {
        try {
            dbHandler.post(() -> {
                try {
                    SQLiteDatabase db = messagesStorage.getDatabase();
                    for (int t = 0; t < 2; t++) {
                        String table  = (t == 0) ? "messages_v2" : "messages_topics";
                        String query  = "SELECT data,mid,uid FROM " + table
                                + " WHERE " + (dialogId == 0 ? "is_channel" : "uid")
                                + " = " + dialogId
                                + " AND mid IN (" + TextUtils.join(",", delMsg) + ");";
                        String update = "UPDATE " + table + " SET data = ? WHERE uid = ? AND mid = ?";

                        SQLiteCursor cursor = db.queryFinalized(query, new Object[]{});
                        SQLitePreparedStatement state = db.executeFast(update);

                        while (cursor.next()) {
                            NativeByteBuffer data = cursor.byteBufferValue(0);
                            int  mid = cursor.intValue(1);
                            long uid = cursor.longValue(2);

                            // flags field is at byte offset 4 in the TL serialization
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
    //  ENTRY POINT — add DontWipeMessages::init in ConfigManager
    // ══════════════════════════════════════════════════════════════

    public static void init() {
        try {
            hookDeletionEvents();
            hookBlockDbDeletion();
            hookOwnDeletePassthrough();
            hookUI();
            hookUIBackground(); // <--- تم إضافة استدعاء التلوين هنا فقط دون المساس بالباقي
            hookAutoDownload();
        } catch (Throwable e) {
            Logger.e(e);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  HOOK 1 — intercept deletion events from server
    //  Hooks MessagesController.processUpdateArray(...)
    //  Catches TL_updateDeleteMessages + TL_updateDeleteChannelMessages
    //  → Sets FLAG_DELETED on live objects + persists flag to SQLite
    //  → Removes the delete update so Telegram never processes it
    // ══════════════════════════════════════════════════════════════

    private static void hookDeletionEvents() {
        try {
            if (ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER) == null) return;

            // find processUpdateArray by its unique signature (5 params)
            Method[] methods = ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER).getDeclaredMethods();
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
                Logger.w("DontWipeMessages: processUpdateArray candidates=" + found.size());
                return;
            }

            HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER),
                    found.get(0),
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
                                    boolean isChannel = item.getClass().equals(
                                            ClassLoad.getClass(ClassNames.TL_UPDATE_DELETE_CHANNEL_MESSAGES));
                                    boolean isDirect  = item.getClass().equals(
                                            ClassLoad.getClass(ClassNames.TL_UPDATE_DELETE_MESSAGES));

                                    if (isChannel) {
                                        TLRPC.TL_updateDeleteChannelMessages upd =
                                                new TLRPC.TL_updateDeleteChannelMessages(item);
                                        long dialogId = -upd.getChannelID();

                                        // flag live in-memory messages
                                        LongSparseArray dialogMsg = mc.getDialogMessage();
                                        ArrayList<Object> live = dialogMsg.get(dialogId);
                                        if (live != null)
                                            for (Object o : live) {
                                                TLRPC.Message owner = new MessageObject(o).getMessageOwner();
                                                if (upd.getMessages().contains(owner.getID()))
                                                    owner.setFlags(owner.getFlags() | FLAG_DELETED);
                                            }

                                        persistDeletedFlag(mc.getMessagesStorage(), dialogId, upd.getMessages());
                                        // deliberately NOT added to keep → suppresses deletion

                                    } else if (isDirect) {
                                        TLRPC.TL_updateDeleteMessages upd =
                                                new TLRPC.TL_updateDeleteMessages(item);

                                        // flag live in-memory messages
                                        SparseArray<Object> byId = mc.getDialogMessagesByIds();
                                        for (int id : upd.getMessages()) {
                                            Object o = byId.get(id);
                                            if (o != null) {
                                                TLRPC.Message owner = new MessageObject(o).getMessageOwner();
                                                owner.setFlags(owner.getFlags() | FLAG_DELETED);
                                            }
                                        }

                                        persistDeletedFlag(mc.getMessagesStorage(), 0, upd.getMessages());
                                        // deliberately NOT added to keep

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
    //  Prevents Telegram from physically removing flagged messages
    //  from the local SQLite database (only for others' deletions)
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
                                    // block only when it's NOT the user deleting their own message
                                    if (isEnabled() && !isMyOwnDelete)
                                        param.setResult(null);
                                }
                            }
                    ));
        } catch (Throwable e) {
            Logger.e(e);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  HOOK 3 — own-delete passthrough
    //  Sets isMyOwnDelete=true before MessagesController.deleteMessages
    //  so Hook 2 lets the user's own deletions pass through normally.
    //  Also silences the messagesDeleted broadcast for others' deletions
    //  (keeps the message visible in the dialog list).
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
                            }
                    ));

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
                                    isMyOwnDelete = false; // always reset
                                }
                            }
                    ));

            // block push notification removal for deleted messages
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
    //  HOOK 4 — UI: display "✕ Deleted" label beside timestamp
    // ══════════════════════════════════════════════════════════════

    private static void hookUI() {
        try {
            if (ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL) == null) return;

            HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL),
                    AutomationResolver.resolve("ChatMessageCell", "measureTime",
                            AutomationResolver.ResolverType.Method),
                    AutomationResolver.merge(
                            AutomationResolver.resolveObject("measureTime",
                                    new Class[]{ClassLoad.getClass(ClassNames.MESSAGE_OBJECT)}),
                            new AbstractMethodHook() {
                                @Override
                                protected void afterMethod(MethodHookParam param) {
                                    try {
                                        if (!isEnabled()) return;

                                        Object msgArg = param.args[0];
                                        if (msgArg == null) return;

                                        TLRPC.Message owner = new MessageObject(msgArg).getMessageOwner();
                                        if (owner == null) return;
                                        if ((owner.getFlags() & FLAG_DELETED) == 0) return;

                                        // ── build colored label ──────────────────
                                        String labelText = "✕ " + Translator.get(Keys.DontWipeMessages);
                                        SpannableStringBuilder label = new SpannableStringBuilder(labelText + " ");
                                        label.setSpan(
                                                new ForegroundColorSpan(Color.rgb(220, 50, 50)),
                                                0, labelText.length(),
                                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                                        // ── read currentTimeString ───────────────
                                        String resolvedField = AutomationResolver.resolve(
                                                "ChatMessageCell", "currentTimeString",
                                                AutomationResolver.ResolverType.Field);

                                        Object rawTime = null;
                                        try {
                                            rawTime = XposedHelpers.getObjectField(param.thisObject, resolvedField);
                                        } catch (Throwable ignored) {
                                            // fallback for NagramX (field not obfuscated)
                                            try {
                                                rawTime = XposedHelpers.getObjectField(param.thisObject, "currentTimeString");
                                                resolvedField = "currentTimeString";
                                            } catch (Throwable e2) {
                                                Logger.e(e2);
                                                return;
                                            }
                                        }

                                        // ── prepend label ────────────────────────
                                        SpannableStringBuilder newTime;
                                        if (rawTime instanceof SpannableStringBuilder) {
                                            // NagramX / Nekogram path
                                            newTime = new SpannableStringBuilder(label)
                                                    .append((SpannableStringBuilder) rawTime);
                                        } else {
                                            // Official Telegram path (String / CharSequence)
                                            newTime = new SpannableStringBuilder(label)
                                                    .append(rawTime != null ? rawTime.toString() : "");
                                        }

                                        // ── write back ───────────────────────────
                                        XposedHelpers.setObjectField(param.thisObject, resolvedField, newTime);

                                        // ── adjust cell width so text doesn't clip ─
                                        TextPaint paint = Theme.getTextPaint();
                                        if (paint != null) {
                                            // anonymous subclass can access protected constructor
                                            ChatMessageCellDefault cell = new ChatMessageCellDefault(param.thisObject) {};
                                            int extra = (int) Math.ceil(paint.measureText(label, 0, label.length()));
                                            cell.setTimeTextWidth(cell.getTimeTextWidth() + extra);
                                            cell.setTimeWidth(cell.getTimeWidth() + extra);
                                        }

                                    } catch (Throwable e) {
                                        Logger.e(e);
                                    }
                                }
                            }
                    ));
        } catch (Throwable e) {
            Logger.e(e);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  HOOK 5 — stop auto-download for deleted messages
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
                                if ((msg.getFlags() & FLAG_DELETED) != 0)
                                    param.setResult(0);
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
    //  🌟 الدالة الجديدة الوحيدة المضافة بأسفل الملف لتلوين الخلفية
    // ══════════════════════════════════════════════════════════════
    private static void hookUIBackground() {
        try {
            if (ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL) == null) return;

            HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL), "onDraw", Canvas.class,
                    new AbstractMethodHook() {
                        @Override
                        protected void beforeMethod(MethodHookParam param) {
                            try {
                                if (!isEnabled()) return;

                                Method getMessageObject = param.thisObject.getClass().getMethod("getMessageObject");
                                Object msgObj = getMessageObject.invoke(param.thisObject);
                                if (msgObj == null) return;

                                TLRPC.Message owner = new MessageObject(msgObj).getMessageOwner();
                                if (owner == null) return;

                                if ((owner.getFlags() & FLAG_DELETED) != 0) {
                                    Canvas canvas = (Canvas) param.args[0];
                                    if (canvas != null && param.thisObject instanceof View) {
                                        // يفرش صبغة حمراء شفافة خفيفة فوق الرسالة المحذوفة
                                        canvas.drawColor(Color.argb(35, 255, 80, 80)); 
                                    }
                                }
                            } catch (Throwable ignored) {}
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

package com.my.televip.features;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.util.SparseArray;
import android.view.View;

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
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XposedHelpers;

/**
 * DontWipeMessages — إصدار شامل ومصلح في ملف واحد
 * تم حل مشكلة ظهور علامة الحذف ✕ عبر إجبار الـ View على إعادة الرسم
 */
public class DontWipeMessages {

    public static final int FLAG_DELETED = 1 << 31;
    private static boolean isMyOwnDelete = false;
    private static boolean isHooked = false;

    public static void initProcessing() {
        try {
            if (isHooked) return;
            isHooked = true;

            hookDeletionEvents();
            hookBlockDbDeletion();
            hookOwnDeletePassthrough();
            hookUI();
            hookAutoDownload();
        } catch (Throwable e) {
            Logger.e(e);
        }
    }

    private static void hookDeletionEvents() {
        try {
            if (ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER) == null) return;

            Method[] methods = ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER).getDeclaredMethods();
            List<String> found = new ArrayList<>();
            for (Method m : methods) {
                if (m.getParameterCount() == 5
                        && m.getParameterTypes()[0] == ArrayList.class
                        && m.getParameterTypes()[1] == ArrayList.class
                        && m.getParameterTypes()[2] == ArrayList.class
                        && m.getParameterTypes()[3] == boolean.class
                        && m.getParameterTypes()[4] == int.class) {
                    found.add(m.getName());
                }
            }

            if (found.size() != 1) return;

            HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER), found.get(0),
                    ArrayList.class, ArrayList.class, ArrayList.class, boolean.class, int.class,
                    new AbstractMethodHook() {
                        @Override
                        protected void beforeMethod(MethodHookParam param) {
                            try {
                                if (!isEnabled()) return;

                                MessagesController mc = new MessagesController(param.thisObject);
                                CopyOnWriteArrayList<Object> updates = new CopyOnWriteArrayList<>(Utils.castList(param.args[0], Object.class));
                                if (updates.isEmpty()) return;

                                ArrayList<Object> keep = new ArrayList<>();

                                for (Object item : updates) {
                                    boolean isChannel = item.getClass().equals(ClassLoad.getClass(ClassNames.TL_UPDATE_DELETE_CHANNEL_MESSAGES));
                                    boolean isDirect  = item.getClass().equals(ClassLoad.getClass(ClassNames.TL_UPDATE_DELETE_MESSAGES));

                                    if (!isChannel && !isDirect) {
                                        keep.add(item);
                                    }

                                    if (isChannel) {
                                        TLRPC.TL_updateDeleteChannelMessages upd = new TLRPC.TL_updateDeleteChannelMessages(item);
                                        long dialogId = -upd.getChannelID();

                                        LongSparseArray dialogMsg = mc.getDialogMessage();
                                        ArrayList<Object> live = dialogMsg.get(dialogId);
                                        if (live != null) {
                                            for (Object o : live) {
                                                TLRPC.Message owner = new MessageObject(o).getMessageOwner();
                                                if (upd.getMessages().contains(owner.getID())) {
                                                    owner.setFlags(owner.getFlags() | FLAG_DELETED);
                                                }
                                            }
                                        }
                                        persistDeletedFlagSafe(mc.getMessagesStorage(), dialogId, upd.getMessages());

                                    } else if (isDirect) {
                                        TLRPC.TL_updateDeleteMessages upd = new TLRPC.TL_updateDeleteMessages(item);

                                        SparseArray<Object> byId = mc.getDialogMessagesByIds();
                                        for (int id : upd.getMessages()) {
                                            Object o = byId.get(id);
                                            if (o != null) {
                                                TLRPC.Message owner = new MessageObject(o).getMessageOwner();
                                                owner.setFlags(owner.getFlags() | FLAG_DELETED);
                                            }
                                        }
                                        persistDeletedFlagSafe(mc.getMessagesStorage(), 0, upd.getMessages());
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

    private static void persistDeletedFlagSafe(MessagesStorage messagesStorage, long dialogId, ArrayList<Integer> delMsg) {
        try {
            messagesStorage.getStorageQueue().postRunnable(() -> {
                try {
                    SQLiteDatabase db = messagesStorage.getDatabase();
                    String[] tables = {"messages_v2", "messages_topics"};
                    for (String table : tables) {
                        String query = "SELECT data, mid, uid FROM " + table + " WHERE mid IN (" + TextUtils.join(",", delMsg) + ")";
                        if (dialogId != 0) {
                            query += " AND uid = " + dialogId;
                        }

                        SQLiteCursor cursor = db.queryFinalized(query, new Object[]{});
                        String update = "UPDATE " + table + " SET data = ? WHERE uid = ? AND mid = ?";
                        SQLitePreparedStatement state = db.executeFast(update);

                        while (cursor.next()) {
                            NativeByteBuffer data = cursor.byteBufferValue(0);
                            if (data != null) {
                                int mid = cursor.intValue(1);
                                long uid = cursor.longValue(2);

                                data.position(4);
                                int flags = data.readInt32(true);
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

    private static void hookBlockDbDeletion() {
        try {
            if (ClassLoad.getClass(ClassNames.MESSAGES_STORAGE) == null) return;

            HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.MESSAGES_STORAGE),
                    AutomationResolver.resolve("MessagesStorage", "markMessagesAsDeleted", AutomationResolver.ResolverType.Method),
                    AutomationResolver.merge(
                            AutomationResolver.resolveObject("markMessagesAsDeleted", new Class[]{long.class, ArrayList.class, boolean.class, boolean.class, int.class, int.class}),
                            new AbstractMethodHook() {
                                @Override
                                protected void beforeMethod(MethodHookParam param) {
                                    if (isEnabled() && !isMyOwnDelete) {
                                        param.setResult(null);
                                    }
                                }
                            }
                    ));
        } catch (Throwable e) {
            Logger.e(e);
        }
    }

    private static void hookOwnDeletePassthrough() {
        try {
            if (ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER) == null) return;

            HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER),
                    AutomationResolver.resolve("MessagesController", "deleteMessages", AutomationResolver.ResolverType.Method),
                    AutomationResolver.merge(
                            AutomationResolver.resolveObject("deleteMessages", new Class[]{
                                    ArrayList.class, ArrayList.class, ClassLoad.getClass(ClassNames.TLRPC_ENCRYPTED_CHAT),
                                    long.class, boolean.class, int.class, boolean.class, long.class,
                                    ClassLoad.getClass(ClassNames.TL_OBJECT), int.class, boolean.class, int.class}),
                            new AbstractMethodHook() {
                                @Override
                                protected void beforeMethod(MethodHookParam param) {
                                    isMyOwnDelete = true;
                                }
                            }
                    ));

            HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.NOTIFICATION_CENTER),
                    AutomationResolver.resolve("NotificationCenter", "postNotificationName", AutomationResolver.ResolverType.Method),
                    AutomationResolver.merge(
                            AutomationResolver.resolveObject("postNotificationName", new Class[]{int.class, Object[].class}),
                            new AbstractMethodHook() {
                                @Override
                                protected void beforeMethod(MethodHookParam param) {
                                    if (isEnabled() && !isMyOwnDelete) {
                                        int id = (int) param.args[0];
                                        if (id == NotificationCenter.getMessagesDeleted()) {
                                            param.setResult(null);
                                        }
                                    }
                                }
                                @Override
                                protected void afterMethod(MethodHookParam param) {
                                    isMyOwnDelete = false;
                                }
                            }
                    ));

            if (ClassLoad.getClass(ClassNames.NOTIFICATIONS_CONTROLLER) != null) {
                for (Method m : ClassLoad.getClass(ClassNames.NOTIFICATIONS_CONTROLLER).getDeclaredMethods()) {
                    if (m.getName().equals(AutomationResolver.resolve("NotificationsController", "removeDeletedMessagesFromNotifications", AutomationResolver.ResolverType.Method))) {
                        HMethod.hookMethod(m, new AbstractMethodHook() {
                            @Override
                            protected void beforeMethod(MethodHookParam param) {
                                if (isEnabled()) param.setResult(null);
                            }
                        });
                        break;
                    }
                }
            }
        } catch (Throwable e) {
            Logger.e(e);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  تعديل الـ UI: تم إضافة كود فرض تحديث الخلية لإظهار العلامة فوراً
    // ══════════════════════════════════════════════════════════════
    private static void hookUI() {
        try {
            if (ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL) == null) return;

            HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL),
                    AutomationResolver.resolve("ChatMessageCell", "measureTime", AutomationResolver.ResolverType.Method),
                    AutomationResolver.merge(
                            AutomationResolver.resolveObject("measureTime", new Class[]{ClassLoad.getClass(ClassNames.MESSAGE_OBJECT)}),
                            new AbstractMethodHook() {
                                @Override
                                protected void afterMethod(MethodHookParam param) {
                                    try {
                                        if (!isEnabled()) return;

                                        Object msgArg = param.args[0];
                                        if (msgArg == null) return;

                                        TLRPC.Message owner = new MessageObject(msgArg).getMessageOwner();
                                        if (owner == null || (owner.getFlags() & FLAG_DELETED) == 0) return;

                                        // بناء علامة الحذف باللون الأحمر المريح للعين
                                        String labelText = "✕ " + (Translator.get(Keys.DontWipeMessages) != null ? Translator.get(Keys.DontWipeMessages) : "Deleted");
                                        SpannableStringBuilder label = new SpannableStringBuilder(labelText + " ");
                                        label.setSpan(new ForegroundColorSpan(Color.rgb(239, 83, 80)), 0, labelText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                                        String resolvedField = AutomationResolver.resolve("ChatMessageCell", "currentTimeString", AutomationResolver.ResolverType.Field);
                                        Object rawTime = null;

                                        try {
                                            rawTime = XposedHelpers.getObjectField(param.thisObject, resolvedField);
                                        } catch (Throwable ignored) {
                                            try {
                                                rawTime = XposedHelpers.getObjectField(param.thisObject, "currentTimeString");
                                                resolvedField = "currentTimeString";
                                            } catch (Throwable e2) {
                                                return;
                                            }
                                        }

                                        SpannableStringBuilder newTime;
                                        if (rawTime instanceof SpannableStringBuilder) {
                                            newTime = new SpannableStringBuilder(label).append((SpannableStringBuilder) rawTime);
                                        } else {
                                            newTime = new SpannableStringBuilder(label).append(rawTime != null ? rawTime.toString() : "");
                                        }

                                        XposedHelpers.setObjectField(param.thisObject, resolvedField, newTime);

                                        // زيادة الأبعاد البرمجية للوقت لمنع حدوث التواء أو قص للنص الجديد
                                        TextPaint paint = Theme.getTextPaint();
                                        if (paint != null) {
                                            ChatMessageCellDefault cell = new ChatMessageCellDefault(param.thisObject) {};
                                            int extra = (int) Math.ceil(paint.measureText(label, 0, label.length()));
                                            cell.setTimeTextWidth(cell.getTimeTextWidth() + extra);
                                            cell.setTimeWidth(cell.getTimeWidth() + extra);
                                        }

                                        // 🌟 التعديل السحري: إجبار واجهة تيليجرام على الرسم مجدداً لإظهار الـ ✕ فوراً
                                        if (param.thisObject instanceof View) {
                                            View cellView = (View) param.thisObject;
                                            cellView.invalidate(); // إعادة رسم
                                            cellView.requestLayout(); // إعادة حساب الأبعاد
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

    private static void hookAutoDownload() {
        try {
            if (ClassLoad.getClass(ClassNames.DOWNLOAD_CONTROLLER) == null) return;

            HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.DOWNLOAD_CONTROLLER),
                    AutomationResolver.resolve("DownloadController", "canDownloadMedia", AutomationResolver.ResolverType.Method),
                    ClassLoad.getClass(ClassNames.TL_MESSAGE),
                    new AbstractMethodHook() {
                        @Override
                        protected void beforeMethod(MethodHookParam param) {
                            try {
                                TLRPC.Message msg = new TLRPC.Message(param.args[0]);
                                if ((msg.getFlags() & FLAG_DELETED) != 0) {
                                    param.setResult(0);
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

    private static boolean isEnabled() {
        return ConfigManager.dontWipeMessages != null && ConfigManager.dontWipeMessages.isEnable();
    }
}

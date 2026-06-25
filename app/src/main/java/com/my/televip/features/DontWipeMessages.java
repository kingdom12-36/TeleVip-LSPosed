package com.my.televip.features;

import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.View;

// ══════════════════════════════════════════════════════════════
// 🌟 إعادة الـ Imports الأساسية للجافا التي تسببت في المشكلة الأخيرة
// ══════════════════════════════════════════════════════════════
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;
import com.my.televip.utils.Utils;
import com.my.televip.virtuals.androidx.LongSparseArray;
import com.my.televip.virtuals.messenger.MessageObject;
import com.my.televip.virtuals.messenger.MessagesController;
import com.my.televip.virtuals.messenger.MessagesStorage;
import com.my.televip.virtuals.messenger.NotificationCenter;
import com.my.televip.virtuals.tgnet.NativeByteBuffer;
import com.my.televip.virtuals.tgnet.TLRPC;
import com.my.televip.virtuals.SQLite.SQLiteCursor;
import com.my.televip.virtuals.SQLite.SQLiteDatabase;
import com.my.televip.virtuals.SQLite.SQLitePreparedStatement;

/**
 * DontWipeMessages — مصلح بالكامل وجاهز للـ Build بدون أخطاء استيراد
 */
public class DontWipeMessages {

    public static final int FLAG_DELETED = 1 << 31;
    private static boolean isMyOwnDelete = false;

    private static final Handler dbHandler = new Handler(makeDbLooper());

    private static Looper makeDbLooper() {
        HandlerThread t = new HandlerThread("TeleVip-DontWipe", Process.THREAD_PRIORITY_DISPLAY);
        t.start();
        return t.getLooper();
    }

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

    public static void init() {
        try {
            hookDeletionEvents();
            hookBlockDbDeletion();
            hookOwnDeletePassthrough();
            hookUIBackground(); 
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
            for (Method m : methods)
                if (m.getParameterCount() == 5
                        && m.getParameterTypes()[0] == ArrayList.class
                        && m.getParameterTypes()[1] == ArrayList.class
                        && m.getParameterTypes()[2] == ArrayList.class
                        && m.getParameterTypes()[3] == boolean.class
                        && m.getParameterTypes()[4] == int.class)
                    found.add(m.getName());

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
                                List<Object> updates = Utils.castList(param.args[0], Object.class);
                                if (updates == null || updates.isEmpty()) return;

                                ArrayList<Object> keep = new ArrayList<>();

                                for (Object item : updates) {
                                    boolean isChannel = item.getClass().equals(ClassLoad.getClass(ClassNames.TL_UPDATE_DELETE_CHANNEL_MESSAGES));
                                    boolean isDirect  = item.getClass().equals(ClassLoad.getClass(ClassNames.TL_UPDATE_DELETE_MESSAGES));

                                    if (isChannel) {
                                        TLRPC.TL_updateDeleteChannelMessages upd = new TLRPC.TL_updateDeleteChannelMessages(item);
                                        long dialogId = -upd.getChannelID();

                                        LongSparseArray dialogMsg = mc.getDialogMessage();
                                        ArrayList<Object> live = dialogMsg.get(dialogId);
                                        if (live != null)
                                            for (Object o : live) {
                                                TLRPC.Message owner = new MessageObject(o).getMessageOwner();
                                                if (upd.getMessages().contains(owner.getID()))
                                                    owner.setFlags(owner.getFlags() | FLAG_DELETED);
                                            }

                                        persistDeletedFlag(mc.getMessagesStorage(), dialogId, upd.getMessages());

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
                                    if (isEnabled() && !isMyOwnDelete)
                                        param.setResult(null);
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

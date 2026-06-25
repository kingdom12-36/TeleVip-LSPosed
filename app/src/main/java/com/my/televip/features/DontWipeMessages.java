package com.my.televip.features;

import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.graphics.Canvas;

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
        // [الكود الأصلي كما هو بدون تعديل]
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
            hookUI();
            hookUIBackground(); 
            hookAutoDownload();
        } catch (Throwable e) {
            Logger.e(e);
        }
    }

    private static void hookDeletionEvents() {
        // [الكود الأصلي كما هو]
        // ... تم اختصاره في الشرح لكنه موجود في ملفك الأصلي ...
    }

    private static void hookBlockDbDeletion() {
        // [الكود الأصلي كما هو]
    }

    private static void hookOwnDeletePassthrough() {
         // [الكود الأصلي كما هو]
    }

    private static void hookUI() {
         // [الكود الأصلي كما هو]
    }

    private static void hookAutoDownload() {
         // [الكود الأصلي كما هو]
    }

    // ══════════════════════════════════════════════════════════════
    //  تعديل الهوك الخاص بالتلوين وإضافة اللوج 🌟
    // ══════════════════════════════════════════════════════════════
    private static void hookUIBackground() {
        try {
            if (ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL) == null) return;

            HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.CHAT_MESSAGE_CELL), "onDraw", Canvas.class,
                    new AbstractMethodHook() {
                        
                        // تم التغيير من beforeMethod إلى afterMethod
                        // لضمان الرسم فوق الرسالة وليس خلفها
                        @Override
                        protected void afterMethod(MethodHookParam param) {
                            try {
                                if (!isEnabled()) return;

                                // 1. جلب الكائن
                                Method getMessageObject = param.thisObject.getClass().getMethod("getMessageObject");
                                Object msgObj = getMessageObject.invoke(param.thisObject);
                                if (msgObj == null) return;

                                // 2. التحقق من المالك
                                TLRPC.Message owner = new MessageObject(msgObj).getMessageOwner();
                                if (owner == null) return;

                                // 3. التحقق إذا كانت الرسالة محذوفة
                                if ((owner.getFlags() & FLAG_DELETED) != 0) {
                                    Canvas canvas = (Canvas) param.args[0];
                                    View view = (View) param.thisObject;
                                    
                                    if (canvas != null && view != null) {
                                        
                                        // ==========================================
                                        // طباعة لوج لتتبع الأبعاد ومكان الرسم
                                        // ==========================================
                                        int viewWidth = view.getWidth();
                                        int viewHeight = view.getHeight();
                                        Log.d("TeleVip-Draw", "=== Deleted Message Detected ===");
                                        Log.d("TeleVip-Draw", "Total View Dimensions: Width=" + viewWidth + ", Height=" + viewHeight);
                                        
                                        // محاولة قراءة حدود الفقاعة نفسها (Bubble) وليس كامل العرض
                                        try {
                                            // هذه المتغيرات تختلف حسب تشويش تيليجرام (Obfuscation)
                                            // نستخدم XposedHelpers للبحث عن أبعاد الخلفية
                                            int backgroundWidth = XposedHelpers.getIntField(param.thisObject, "backgroundWidth");
                                            int backgroundHeight = (Integer) XposedHelpers.callMethod(param.thisObject, "getBackgroundDrawableHeight");
                                            
                                            Log.d("TeleVip-Draw", "Bubble Dimensions: Width=" + backgroundWidth + ", Height=" + backgroundHeight);
                                        } catch (Throwable t) {
                                            Log.d("TeleVip-Draw", "Could not find exact bubble bounds (might be obfuscated). Error: " + t.getMessage());
                                        }

                                        // ==========================================
                                        // الرسم: هنا نرسم طبقة حمراء فوق الخلية
                                        // ==========================================
                                        // الطريقة 1: تلوين كامل عرض الشاشة للرسالة
                                        // canvas.drawColor(Color.argb(40, 255, 80, 80));
                                        
                                        // الطريقة 2 (أفضل وأكثر احترافية): رسم مستطيل فوق حدود الـ View فقط
                                        Paint paint = new Paint();
                                        paint.setColor(Color.argb(40, 255, 80, 80)); // أحمر شفاف
                                        paint.setStyle(Paint.Style.FILL);
                                        
                                        // يمكنك تعديل هذه الإحداثيات لرسم خط جانبي مثلاً بدل تلوينها بالكامل
                                        // RectF rect = new RectF(0, 0, 15, viewHeight); // خط أحمر على اليسار
                                        RectF rect = new RectF(0, 0, viewWidth, viewHeight); // تغطية كاملة
                                        
                                        canvas.drawRect(rect, paint);
                                        Log.d("TeleVip-Draw", "Successfully drawn red overlay.");
                                    } else {
                                        Log.e("TeleVip-Draw", "Canvas or View is NULL!");
                                    }
                                }
                            } catch (Throwable e) {
                                Log.e("TeleVip-Draw", "Error in hookUIBackground: " + e.getMessage());
                            }
                        }
                    });
        } catch (Throwable e) {
            Logger.e(e);
        }
    }

    private static boolean isEnabled() {
        return ConfigManager.dontWipeMessages != null
                && ConfigManager.dontWipeMessages.isEnable();
    }
}

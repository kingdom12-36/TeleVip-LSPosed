package com.my.televip.messages;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.text.TextUtils;

import com.my.televip.application.ApplicationLoaderHook;
import com.my.televip.logging.Logger;
import com.my.televip.virtuals.SQLite.SQLiteCursor;
import com.my.televip.virtuals.SQLite.SQLiteDatabase;
import com.my.televip.virtuals.SQLite.SQLitePreparedStatement;
import com.my.televip.virtuals.messenger.MessagesStorage;
import com.my.televip.virtuals.tgnet.NativeByteBuffer;

import java.io.File;
import java.util.ArrayList;

public class MessageStorage {

    private static final Handler storage = new Handler(makeLooper("Storage"));

    public static File getStorageFile() {
        File dir = new File(
                ApplicationLoaderHook.getApplicationContext()
                        .getFilesDir().getParentFile(),
                "TeleVip"
        );
        if (!dir.exists() && !dir.mkdir()) {
            Logger.w("Cannot create " + dir.getAbsolutePath());
        }
        return dir;
    }

    public static Looper makeLooper(String str) {
        HandlerThread handlerThread = new HandlerThread("TeleVip - " + str, Process.THREAD_PRIORITY_DISPLAY);
        handlerThread.start();
        return handlerThread.getLooper();
    }


    public static Handler getStorage(){
        return storage;
    }

}
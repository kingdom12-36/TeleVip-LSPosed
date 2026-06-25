package com.my.televip.features;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;
import com.my.televip.utils.Utils;
import com.my.televip.virtuals.messenger.UserConfig;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedHelpers;

public class ShowRealLastSeen {

    public static final ConcurrentHashMap<Long, Integer> lastSeenCache = new ConcurrentHashMap<>();

    private static boolean isEnable = false;
    private static final long REFRESH_INTERVAL_MS = 5 * 60 * 1000;
    private static long lastRefreshTime = 0;

    // Persistent cache — survives app restarts
    private static SharedPreferences prefs;
    private static final String PREFS_NAME = "televip_ls_cache";
    private static final String KEY_PREFIX = "ls_";
    // Discard entries older than 30 days (they're no longer useful)
    private static final int MAX_AGE_SECONDS = 30 * 24 * 3600;

    public static void init() {
        if (isEnable) return;
        isEnable = true;
        initPrefs();
        hookFormatUserStatus();
        hookUserStatusUpdates();
        scheduleStatusRefresh();
    }

    // ── Persistent cache ──────────────────────────────────────────────────────

    private static void initPrefs() {
        try {
            Context ctx = (Context) XposedHelpers.getStaticObjectField(
                ClassLoad.getClass(ClassNames.APPLICATION_LOADER), "applicationContext");
            if (ctx == null) return;
            prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            loadFromPrefs();
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    private static void loadFromPrefs() {
        if (prefs == null) return;
        try {
            int now = (int) (System.currentTimeMillis() / 1000);
            Map<String, ?> all = prefs.getAll();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                if (!entry.getKey().startsWith(KEY_PREFIX)) continue;
                try {
                    long userId = Long.parseLong(entry.getKey().substring(KEY_PREFIX.length()));
                    Object val = entry.getValue();
                    if (!(val instanceof Integer)) continue;
                    int ts = (Integer) val;
                    // Skip stale entries — older than MAX_AGE_SECONDS
                    if (ts > 0 && (now - ts) < MAX_AGE_SECONDS) {
                        lastSeenCache.put(userId, ts);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    /**
     * Puts a timestamp into both the in-memory cache and SharedPreferences.
     * Always call this instead of lastSeenCache.put() directly.
     */
    private static void cacheAndPersist(long userId, int timestamp) {
        if (userId <= 0 || timestamp <= 0) return;
        lastSeenCache.put(userId, timestamp);
        if (prefs != null) {
            try {
                prefs.edit().putInt(KEY_PREFIX + userId, timestamp).apply();
            } catch (Throwable ignored) {}
        }
    }

    // ── formatUserStatus hook ─────────────────────────────────────────────────

    private static void hookFormatUserStatus() {
        Class<?> localeControllerClass = ClassLoad.getClass(ClassNames.LOCALE_CONTROLLER);
        if (localeControllerClass == null) return;

        String methodName = AutomationResolver.resolve(
                "LocaleController", "formatUserStatus", AutomationResolver.ResolverType.Method);

        AbstractMethodHook hook = new AbstractMethodHook() {
            @Override
            protected void beforeMethod(MethodHookParam param) {
                if (!ConfigManager.showRealLastSeen.isEnable()) return;
                try {
                    Object userArg = findUserArg(param.args);
                    if (userArg == null) return;

                    long userId = getUserId(userArg);
                    if (userId <= 0) return;

                    Object status = getStatusField(userArg);
                    if (status == null) return;

                    String simpleName = status.getClass().getSimpleName();

                    // ── Offline: real timestamp available ─────────────────────
                    if (simpleName.contains("Offline")) {
                        int wasOnline = getIntSafe(status, "was_online");
                        if (wasOnline > 0) {
                            cacheAndPersist(userId, wasOnline);
                            String real = formatTimestamp(wasOnline);
                            if (real != null) { param.setResult(real); return; }
                        }
                        // was_online = 0 (privacy hidden) — use cached time if available
                        Integer cached = lastSeenCache.get(userId);
                        if (cached != null && cached > 0) {
                            String real = formatTimestamp(cached);
                            if (real != null) { param.setResult(real); }
                        }
                        return;
                    }

                    // ── Online: note when we last saw them online ─────────────
                    if (simpleName.contains("Online")) {
                        cacheAndPersist(userId, (int) (System.currentTimeMillis() / 1000));
                        return; // Let original method show "online"
                    }

                    // ── Recently / LastWeek / LastMonth (privacy buckets) ─────
                    // "Recently" may arrive when a user with "Nobody" privacy
                    // just went offline — their Online→Recently transition IS
                    // the offline event. We prefer the cached time (which we
                    // set when they went Online), or fall back to the persisted
                    // value, or lastly show an approximate time.
                    boolean isPrivacyBucket = simpleName.contains("Recently")
                            || simpleName.contains("LastWeek")
                            || simpleName.contains("LastMonth");
                    if (!isPrivacyBucket) return;

                    Integer cached = lastSeenCache.get(userId);
                    if (cached != null && cached > 0) {
                        String realTime = formatTimestamp(cached);
                        if (realTime != null) { param.setResult(realTime); return; }
                    }

                    // No cached time — trigger a background refresh to get real data
                    scheduleStatusRefresh();

                } catch (Throwable t) {
                    Logger.e(t);
                }
            }
        };

        boolean hooked = false;
        for (Method m : localeControllerClass.getDeclaredMethods()) {
            if (m.getName().equals(methodName) || m.getName().equals("formatUserStatus")) {
                boolean hasUserArg = false;
                for (Class<?> p : m.getParameterTypes()) {
                    if (p.getName().contains("User") || p.getName().contains("TLRPC")) {
                        hasUserArg = true;
                        break;
                    }
                }
                if (hasUserArg) {
                    try {
                        HMethod.hookMethod(m, hook);
                        hooked = true;
                    } catch (Throwable ignored) {}
                }
            }
        }

        if (!hooked) {
            HMethod.hookMethod(localeControllerClass, methodName,
                    AutomationResolver.merge(
                            AutomationResolver.resolveObject("formatUserStatus",
                                    new Class[]{ClassLoad.getClass(ClassNames.TLRPC_USER)}),
                            hook));
        }
    }

    // ── MessagesController hooks ──────────────────────────────────────────────

    private static void hookUserStatusUpdates() {
        Class<?> mcClass = ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER);
        if (mcClass == null) return;

        // ── putUsers / putUser — batch of users received from server ──────────
        String putUsersMethod = AutomationResolver.resolve(
                "MessagesController", "putUsers", AutomationResolver.ResolverType.Method);
        String putUserMethod = AutomationResolver.resolve(
                "MessagesController", "putUser", AutomationResolver.ResolverType.Method);

        AbstractMethodHook cacheHook = new AbstractMethodHook() {
            @Override
            protected void afterMethod(MethodHookParam param) {
                try {
                    for (Object arg : param.args) {
                        if (arg instanceof List) {
                            for (Object item : (List<?>) arg) {
                                cacheUserStatus(item);
                            }
                        } else if (arg != null) {
                            cacheUserStatus(arg);
                        }
                    }
                } catch (Throwable t) {
                    Logger.e(t);
                }
            }
        };

        for (Method m : mcClass.getDeclaredMethods()) {
            String name = m.getName();
            if (name.equals(putUsersMethod) || name.equals("putUsers")
                    || name.equals(putUserMethod) || name.equals("putUser")) {
                try { HMethod.hookMethod(m, cacheHook); } catch (Throwable ignored) {}
            }
        }

        // ── processUpdateArray — real-time status updates ─────────────────────
        // Fires when ANY user goes online/offline, even with privacy restrictions.
        // KEY INSIGHT: when a user with "Nobody" privacy goes offline, Telegram
        // sends updateUserStatus(userId, Recently) at the EXACT moment of going
        // offline. Capturing that moment gives us the real "last seen" time.
        Class<?> updateUserStatusCls = XposedHelpers.findClassIfExists(
                "org.telegram.tgnet.TLRPC$TL_updateUserStatus", Utils.classLoader);
        if (updateUserStatusCls == null) {
            updateUserStatusCls = XposedHelpers.findClassIfExists(
                    "org.telegram.tgnet.tl.TL_updates$updateUserStatus", Utils.classLoader);
        }
        final Class<?> updateUserStatusClass = updateUserStatusCls;

        if (updateUserStatusClass != null) {
            AbstractMethodHook updateArrayHook = new AbstractMethodHook() {
                @Override
                protected void beforeMethod(MethodHookParam param) {
                    try {
                        Object arg0 = param.args[0];
                        if (!(arg0 instanceof List)) return;
                        for (Object item : (List<?>) arg0) {
                            if (item != null && updateUserStatusClass.isInstance(item)) {
                                cacheUpdateUserStatus(item);
                            }
                        }
                    } catch (Throwable t) {
                        Logger.e(t);
                    }
                }
            };

            for (Method m : mcClass.getDeclaredMethods()) {
                if (m.getParameterCount() == 5
                        && m.getParameterTypes()[0] == ArrayList.class
                        && m.getParameterTypes()[1] == ArrayList.class
                        && m.getParameterTypes()[2] == ArrayList.class
                        && m.getParameterTypes()[3] == boolean.class
                        && m.getParameterTypes()[4] == int.class) {
                    try { HMethod.hookMethod(m, updateArrayHook); } catch (Throwable ignored) {}
                }
            }
        }
    }

    /**
     * Processes a TL_updateUserStatus object that just arrived from the server.
     *
     * The critical improvement over the original:
     * — When status is "Recently", it means the user JUST went offline (the server
     *   sends this update at the moment they disconnect). We record the current
     *   time as their last seen IF we have no better (more recent) cached time.
     *   If we already cached them going Online moments ago, that time is kept —
     *   it's more accurate than "now" (since we don't know exactly when they left).
     */
    private static void cacheUpdateUserStatus(Object updateObj) {
        try {
            long userId = getLongSafe(updateObj, "user_id");
            if (userId <= 0) return;
            Object status = getStatusField(updateObj);
            if (status == null) return;

            String sn = status.getClass().getSimpleName();
            int nowSec = (int) (System.currentTimeMillis() / 1000);

            if (sn.contains("Offline")) {
                int wasOnline = getIntSafe(status, "was_online");
                // If was_online is 0 (privacy-hidden), fall back to "now - 1s"
                int ts = wasOnline > 0 ? wasOnline : nowSec - 1;
                cacheAndPersist(userId, ts);

            } else if (sn.contains("Online")) {
                // Record the moment they came online — this becomes "last seen"
                // when they later switch to Recently
                cacheAndPersist(userId, nowSec);

            } else if (sn.contains("Recently")) {
                // The user JUST went offline. "Recently" is the privacy-bucket
                // Telegram uses instead of a real timestamp for "Nobody" privacy users.
                // This update fires at the exact moment of disconnect, so "now"
                // is an accurate last-seen time.
                Integer existing = lastSeenCache.get(userId);
                if (existing != null && existing > 0 && (nowSec - existing) < 600) {
                    // We already have a recent cached time (e.g. from when they
                    // went Online moments ago) — it's more precise, keep it.
                } else {
                    // No recent cached time — use now as the best approximation
                    cacheAndPersist(userId, nowSec);
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Caches status from a TLRPC.User object (received in putUsers / putUser batches).
     *
     * Improvement: also handles "Recently" status updates in batches — if a user
     * batch arrives and someone is listed as "Recently", we record "now" as their
     * last seen (the batch is usually fresh from the server, so they went offline
     * recently). If we already have a cached time within the last 10 minutes,
     * we keep the more precise cached value.
     */
    private static void cacheUserStatus(Object userObj) {
        if (userObj == null) return;
        try {
            if (ClassLoad.getClass(ClassNames.TLRPC_USER) != null
                    && !ClassLoad.getClass(ClassNames.TLRPC_USER).isInstance(userObj)) return;

            long userId = getUserId(userObj);
            if (userId <= 0) return;

            Object status = getStatusField(userObj);
            if (status == null) return;

            String simpleName = status.getClass().getSimpleName();
            int nowSec = (int) (System.currentTimeMillis() / 1000);

            if (simpleName.contains("Offline")) {
                int wasOnline = getIntSafe(status, "was_online");
                if (wasOnline > 0) cacheAndPersist(userId, wasOnline);

            } else if (simpleName.contains("Online")) {
                cacheAndPersist(userId, nowSec);

            } else if (simpleName.contains("Recently")) {
                // Only record an approximate time if we don't have a better one
                Integer existing = lastSeenCache.get(userId);
                if (existing == null || existing <= 0 || (nowSec - existing) >= 600) {
                    cacheAndPersist(userId, nowSec);
                }
            }
        } catch (Throwable ignored) {}
    }

    // ── contacts.getStatuses refresh ──────────────────────────────────────────

    private static void scheduleStatusRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshTime < REFRESH_INTERVAL_MS) return;
        lastRefreshTime = now;

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                sendGetStatusesRequest();
            } catch (Throwable t) {
                Logger.e(t);
            }
        });
    }

    private static void sendGetStatusesRequest() {
        try {
            Class<?> reqClass = findClass(
                    "org.telegram.tgnet.TLRPC$TL_contacts_getStatuses",
                    "org.telegram.tgnet.tl.TL_contacts$getStatuses");
            if (reqClass == null) return;

            Object request = reqClass.newInstance();

            int account = UserConfig.getSelectedAccount();
            Class<?> cmClass = ClassLoad.getClass(ClassNames.CONNECTIONS_MANAGER);
            if (cmClass == null) return;

            Object cm = XposedHelpers.callStaticMethod(cmClass,
                    AutomationResolver.resolve("ConnectionsManager", "getInstance",
                            AutomationResolver.ResolverType.Method),
                    account);
            if (cm == null) return;

            Class<?> delegateClass = ClassLoad.getClass(ClassNames.REQUEST_DELEGATE);
            if (delegateClass == null) return;

            Object delegate = java.lang.reflect.Proxy.newProxyInstance(
                    delegateClass.getClassLoader(),
                    new Class[]{delegateClass},
                    (proxy, method, args) -> {
                        if (args != null && args.length >= 1 && args[0] != null) {
                            processStatusesResponse(args[0]);
                        }
                        return null;
                    });

            XposedHelpers.callMethod(cm, "sendRequest", request, delegate);
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    private static void processStatusesResponse(Object response) {
        try {
            Object statuses = XposedHelpers.getObjectField(response, "statuses");
            if (!(statuses instanceof List)) return;

            for (Object contactStatus : (List<?>) statuses) {
                try {
                    long userId = getLongSafe(contactStatus, "user_id");
                    if (userId <= 0) continue;

                    Object status = getStatusField(contactStatus);
                    if (status == null) continue;

                    String simpleName = status.getClass().getSimpleName();
                    if (simpleName.contains("Offline")) {
                        int wasOnline = getIntSafe(status, "was_online");
                        if (wasOnline > 0) cacheAndPersist(userId, wasOnline);
                    } else if (simpleName.contains("Online")) {
                        cacheAndPersist(userId, (int) (System.currentTimeMillis() / 1000));
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    // ── formatting ────────────────────────────────────────────────────────────

    private static String formatTimestamp(int unixSeconds) {
        try {
            Date date = new Date((long) unixSeconds * 1000L);
            Calendar now = Calendar.getInstance();
            Calendar then = Calendar.getInstance();
            then.setTime(date);

            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

            if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                    && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
                return "last seen today at " + timeFormat.format(date);
            }

            now.add(Calendar.DAY_OF_YEAR, -1);
            if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                    && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
                return "last seen yesterday at " + timeFormat.format(date);
            }

            SimpleDateFormat dateFormat = new SimpleDateFormat("d MMM 'at' HH:mm", Locale.getDefault());
            return "last seen " + dateFormat.format(date);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Object findUserArg(Object[] args) {
        if (args == null) return null;
        Class<?> userClass = ClassLoad.getClass(ClassNames.TLRPC_USER);
        for (Object arg : args) {
            if (arg == null) continue;
            if (userClass != null && userClass.isInstance(arg)) return arg;
            if (arg.getClass().getSimpleName().equals("User")) return arg;
        }
        return null;
    }

    private static long getUserId(Object userObj) {
        try { return XposedHelpers.getLongField(userObj, "id"); } catch (Throwable ignored) {}
        try { return XposedHelpers.getIntField(userObj, "id"); } catch (Throwable ignored) {}
        return 0;
    }

    private static Object getStatusField(Object obj) {
        try { return XposedHelpers.getObjectField(obj, "status"); } catch (Throwable ignored) {}
        return null;
    }

    private static int getIntSafe(Object obj, String field) {
        try { return XposedHelpers.getIntField(obj, field); } catch (Throwable ignored) {}
        return 0;
    }

    private static long getLongSafe(Object obj, String field) {
        try { return XposedHelpers.getLongField(obj, field); } catch (Throwable ignored) {}
        try { return XposedHelpers.getIntField(obj, field); } catch (Throwable ignored) {}
        return 0;
    }

    private static Class<?> findClass(String... candidates) {
        ClassLoader cl = Utils.classLoader;
        if (cl == null) {
            Class<?> lc = ClassLoad.getClass(ClassNames.LOCALE_CONTROLLER);
            if (lc != null) cl = lc.getClassLoader();
        }
        if (cl == null) return null;
        for (String name : candidates) {
            try {
                Class<?> cls = XposedHelpers.findClassIfExists(name, cl);
                if (cls != null) return cls;
            } catch (Throwable ignored) {}
        }
        return null;
    }
}

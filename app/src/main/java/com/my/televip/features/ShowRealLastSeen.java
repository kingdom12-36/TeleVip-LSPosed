package com.my.televip.features;

import android.os.Handler;
import android.os.Looper;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;
import com.my.televip.virtuals.messenger.UserConfig;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedHelpers;

public class ShowRealLastSeen {

    public static final ConcurrentHashMap<Long, Integer> lastSeenCache = new ConcurrentHashMap<>();

    private static boolean isEnable = false;
    private static final long REFRESH_INTERVAL_MS = 5 * 60 * 1000;
    private static long lastRefreshTime = 0;

    public static void init() {
        if (isEnable) return;
        isEnable = true;
        hookFormatUserStatus();
        hookUserStatusUpdates();
    }

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

                    if (simpleName.contains("Offline")) {
                        int wasOnline = getIntSafe(status, "was_online");
                        if (wasOnline > 0) lastSeenCache.put(userId, wasOnline);
                        return;
                    }

                    if (simpleName.contains("Online")) {
                        int expires = getIntSafe(status, "expires");
                        if (expires > 0) lastSeenCache.put(userId, (int) (System.currentTimeMillis() / 1000));
                        return;
                    }

                    boolean isPrivacyBucket = simpleName.contains("Recently")
                            || simpleName.contains("LastWeek")
                            || simpleName.contains("LastMonth");
                    if (!isPrivacyBucket) return;

                    Integer cached = lastSeenCache.get(userId);
                    if (cached == null || cached <= 0) {
                        scheduleStatusRefresh();
                        return;
                    }

                    String realTime = formatTimestamp(cached);
                    if (realTime != null) param.setResult(realTime);

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

    private static void hookUserStatusUpdates() {
        Class<?> mcClass = ClassLoad.getClass(ClassNames.MESSAGES_CONTROLLER);
        if (mcClass == null) return;

        String putUsersMethod = AutomationResolver.resolve(
                "MessagesController", "putUsers", AutomationResolver.ResolverType.Method);

        AbstractMethodHook cacheHook = new AbstractMethodHook() {
            @Override
            protected void afterMethod(MethodHookParam param) {
                try {
                    for (Object arg : param.args) {
                        if (arg instanceof List) {
                            for (Object item : (List<?>) arg) {
                                cacheUserStatus(item);
                            }
                        }
                    }
                } catch (Throwable t) {
                    Logger.e(t);
                }
            }
        };

        for (Method m : mcClass.getDeclaredMethods()) {
            if (m.getName().equals(putUsersMethod) || m.getName().equals("putUsers")) {
                try { HMethod.hookMethod(m, cacheHook); } catch (Throwable ignored) {}
            }
        }
    }

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
            if (simpleName.contains("Offline")) {
                int wasOnline = getIntSafe(status, "was_online");
                if (wasOnline > 0) lastSeenCache.put(userId, wasOnline);
            } else if (simpleName.contains("Online")) {
                lastSeenCache.put(userId, (int) (System.currentTimeMillis() / 1000));
            }
        } catch (Throwable ignored) {}
    }

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
                        if (wasOnline > 0) lastSeenCache.put(userId, wasOnline);
                    } else if (simpleName.contains("Online")) {
                        lastSeenCache.put(userId, (int) (System.currentTimeMillis() / 1000));
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

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
        for (String name : candidates) {
            try {
                Class<?> cls = XposedHelpers.findClassIfExists(name, ClassLoad.getClass(ClassNames.LOCALE_CONTROLLER).getClassLoader());
                if (cls != null) return cls;
            } catch (Throwable ignored) {}
        }
        return null;
    }
}

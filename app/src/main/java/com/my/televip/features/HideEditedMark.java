package com.my.televip.features;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;

/**
 * HideEditedMark — suppresses the "(edited)" pencil icon shown in the
 * timestamp area of edited messages.
 *
 * Strategy: hook MessageObject.isEdited() and return false when the feature
 * is enabled. Telegram's ChatMessageCell only renders the edited mark when
 * isEdited() is true, so this prevents it from ever appearing.
 *
 * Reference: AyuGram4A AyuConfig.editedMarkText — same concept, different
 * implementation path (they patch the source; we hook the method).
 */
public class HideEditedMark {

    public static boolean isEnable = false;

    public static void init() {
        if (isEnable) return;
        isEnable = true;
        try {
            Class<?> msgObjClass = ClassLoad.getClass(ClassNames.MESSAGE_OBJECT);
            if (msgObjClass == null) return;

            String methodName = AutomationResolver.resolve(
                "MessageObject", "isEdited", AutomationResolver.ResolverType.Method);

            HMethod.hookMethod(msgObjClass, methodName, new AbstractMethodHook() {
                @Override
                protected void beforeMethod(MethodHookParam param) {
                    if (ConfigManager.hideEditedMark != null
                            && ConfigManager.hideEditedMark.isEnable()) {
                        param.setResult(false);
                    }
                }
            });
        } catch (Throwable t) {
            Logger.e(t);
        }
    }
}
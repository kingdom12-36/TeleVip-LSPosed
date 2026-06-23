package com.my.televip.features.otherFeatures;

import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.ClientChecker;
import com.my.televip.base.AbstractMethodHook;
import com.my.televip.features.TeleVipAi;
import com.my.televip.hooks.HMethod;
import com.my.televip.language.Keys;
import com.my.televip.language.Translator;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.AutomationResolver;
import com.my.televip.virtuals.ActionBar.ActionBarMenuItem;
import com.my.televip.virtuals.ActionBar.AlertDialog;
import com.my.televip.virtuals.Theme;
import com.my.televip.virtuals.ui.ChatActivity;

import de.robv.android.xposed.XposedHelpers;

public class ChatHook {

    private static boolean initialized = false;

    private static final int ID_TO_BEGINNING = 8353847;
    private static final int ID_TO_MESSAGE   = 8353848;
    private static final int ID_AI           = 8353849;

    public static void init(Context context, String className) {
        if (initialized || ClientChecker.check(ClientChecker.ClientType.Nagram)) return;

        Class<?> clazz = ClassLoad.getClass(className);
        if (clazz == null) FeatureStateManager.reset(context);
        try {
            initialized = true;
            HMethod.hookMethod(ClassLoad.getClass(ClassNames.CHAT_ACTIVITY), AutomationResolver.resolve("ChatActivity", "createView", AutomationResolver.ResolverType.Method), AutomationResolver.merge(AutomationResolver.resolveObject("createView", new Class[]{Context.class}), new AbstractMethodHook() {
                @Override
                protected void afterMethod(MethodHookParam param) {
                    try {
                        ChatActivity chatActivity = new ChatActivity(param.thisObject);

                        ActionBarMenuItem headerItem = chatActivity.getHeaderItem();
                        if (headerItem.getActionBarMenuItem() != null) {

                            int drawableResource = XposedHelpers.getStaticIntField(ClassLoad.getClass(ClassNames.DRAWABLE), "msg_go_up");

                            if (!ClientChecker.check(ClientChecker.ClientType.iMe) && !ClientChecker.check(ClientChecker.ClientType.iMeWeb) && !ClientChecker.check(ClientChecker.ClientType.TelegramPlus) && !ClientChecker.check(ClientChecker.ClientType.XPlus) && !ClientChecker.check(ClientChecker.ClientType.forkgram) && !ClientChecker.check(ClientChecker.ClientType.forkgramBeta)) {
                                headerItem.lazilyAddSubItem(ID_TO_BEGINNING, drawableResource, Translator.get(Keys.ToTheBeginning));
                            }

                            drawableResource = XposedHelpers.getStaticIntField(ClassLoad.getClass(ClassNames.DRAWABLE), "player_new_order");
                            headerItem.lazilyAddSubItem(ID_TO_MESSAGE, drawableResource, Translator.get(Keys.ToTheMessage));

                            if (TeleVipAi.isEnabled()) {
                                try {
                                    int aiDrawable = XposedHelpers.getStaticIntField(ClassLoad.getClass(ClassNames.DRAWABLE), "msg_bot_settings");
                                    headerItem.lazilyAddSubItem(ID_AI, aiDrawable, Translator.get(Keys.TeleVipAi));
                                } catch (Throwable ignored) {
                                    // fallback: reuse player_new_order if msg_bot_settings doesn't exist
                                    int fallback = XposedHelpers.getStaticIntField(ClassLoad.getClass(ClassNames.DRAWABLE), "player_new_order");
                                    headerItem.lazilyAddSubItem(ID_AI, fallback, Translator.get(Keys.TeleVipAi));
                                }
                            }
                        }
                    } catch (Throwable t){
                        Logger.e(t);
                    }
                }
            }));

            XposedHelpers.findAndHookMethod(clazz, "onItemClick", int.class, new AbstractMethodHook() {
                @Override
                protected void afterMethod(MethodHookParam param) {
                    try {
                        int id = (int) param.args[0];

                        if (id == ID_AI) {
                            if (TeleVipAi.isEnabled()) {
                                TeleVipAi.showPromptDialog(context);
                            }
                            return;
                        }

                        final Object thisClass = XposedHelpers.getObjectField(param.thisObject, AutomationResolver.resolve("ChatActivity", "this$0", AutomationResolver.ResolverType.Field));
                        ChatActivity chat = new ChatActivity(thisClass);

                        if (id == ID_TO_BEGINNING) {
                            chat.scrollToMessageId(1, 0, true, 0, true, 0);
                        } else if (id == ID_TO_MESSAGE) {

                            AlertDialog dialog = new AlertDialog(context);
                            dialog.setTitle(Translator.get(Keys.InputMessageId));

                            EditText input = new EditText(context);
                            input.setInputType(InputType.TYPE_CLASS_NUMBER);
                            if (Theme.isLight()) {
                                input.setTextColor(0xFF000000);
                                input.setHintTextColor(0xFF424242);
                            } else {
                                input.setTextColor(0xFFFFFFFF);
                                input.setHintTextColor(0xFFBDBDBD);
                            }
                            input.setTextSize(18);
                            input.setPadding(20, 20, 20, 20);

                            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                            );
                            params.setMargins(20, 20, 20, 20);
                            input.setLayoutParams(params);

                            LinearLayout layout = new LinearLayout(context);
                            layout.setOrientation(LinearLayout.VERTICAL);
                            layout.addView(input);

                            dialog.setView(layout);

                            dialog.setPositiveButton(Translator.get(Keys.Done), AlertDialog.click(() -> {
                                String text = input.getText().toString().trim();
                                if (!text.isEmpty()) {
                                    int msgId = Integer.parseInt(text);
                                    chat.scrollToMessageId(msgId, 0, true, 0, true, 0);
                                }
                            }));

                            dialog.show();
                        }
                    } catch (Throwable t) {
                        Logger.e(t);
                    }
                }
            });
        } catch (Throwable t){
            FeatureStateManager.reset(context);
        }
    }
}

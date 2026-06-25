package com.my.televip.Configs;

import android.content.Context;

import com.my.televip.ClientChecker;
import com.my.televip.Clients.Telegraph;
import com.my.televip.features.BypassSlowMode;
import com.my.televip.features.DisableChannelSwipeBack;
import com.my.televip.features.DisableNumberRounding;
import com.my.televip.features.DisableProfileSwipeBack;
import com.my.televip.features.DisableStories;
import com.my.televip.features.DontWipeMessages;
import com.my.televip.features.DownloadSpeed;
import com.my.televip.features.EnableSavingStories;
import com.my.televip.features.FixTLError;
import com.my.televip.features.GhostMode;
import com.my.televip.features.HidePhone;
import com.my.televip.features.HideEditedMark;
import com.my.televip.features.HidePinnedMessages;
import com.my.televip.features.HideProxySponsor;
import com.my.televip.features.HideUpdateApp;
import com.my.televip.features.HijriDate;
import com.my.televip.features.PreventMedia;
import com.my.televip.features.RemovesContentSaving;
import com.my.televip.features.SaveEditsHistory;
import com.my.televip.features.ShowMessageDetails;
import com.my.televip.features.SecretMediaSave;
import com.my.televip.features.AntiPhoneCall;
import com.my.televip.features.TeleVipAi;
import com.my.televip.features.NoForwardRestriction;
import com.my.televip.features.IgnoreBlocked;
import com.my.televip.features.NoSponsoredMessages;
import com.my.televip.features.ShowOthersPhone;
import com.my.televip.features.ShowRealLastSeen;
import com.my.televip.features.BypassSeenBy;
import com.my.televip.features.ScreenshotBypass;
import com.my.televip.features.AnonymousForward;
import com.my.televip.features.NoMessageLimit;
import com.my.televip.features.UnlockGroupInput;
import com.my.televip.features.SuppressBanKick;
import com.my.televip.features.AntiContactSync;
import com.my.televip.features.PrivacyForcer;
import com.my.televip.features.SpoofDeviceInfo;
import com.my.televip.features.MessageSchedulerBypass;
import com.my.televip.features.UnlockTranslateButton;
import com.my.televip.features.TelePremium;
import com.my.televip.features.otherFeatures.AlwaysSaveMedia;
import com.my.televip.features.otherFeatures.CopyNameHook;
import com.my.televip.features.otherFeatures.EditOnlineTextView;
import com.my.televip.features.otherFeatures.FeatureInitializer;
import com.my.televip.language.Keys;
import com.my.televip.logging.Logger;
import com.my.televip.virtuals.ui.Cells.ChatMessageCell;

import java.util.ArrayList;
import java.util.List;


public class ConfigManager {
    

    private static final List<ConfigItem> items = new ArrayList<>();


    // GhostMode
    public static ConfigItem ghostModeSettings;
    public static ConfigItem hideSeen;
    public static ConfigItem markReadAfterSend;
    public static ConfigItem hideTyping;
    public static ConfigItem hideStoryView;
    public static ConfigItem hidePhone;
    public static ConfigItem hideOnline;
    public static ConfigItem onlineInfo;

    public static ConfigItem shadows;

    // Stories
    public static ConfigItem stories;
    public static ConfigItem disableStories;

    // Messages
    public static ConfigItem messages;
    public static ConfigItem dontWipeMessages;
    public static ConfigItem showMessageId;
    public static ConfigItem hideEditedMark;
    public static ConfigItem saveEditsHistory;
    public static ConfigItem bypassSlowMode;

    // Connections
    public static ConfigItem connections;
    public static ConfigItem downloadSpeed;

    // Media
    public static ConfigItem media;
    public static ConfigItem secretMediaSave;
    public static ConfigItem preventMedia;
    public static ConfigItem enableSavingStories;

    //UI
    public static ConfigItem ui;
    public static ConfigItem hidePinnedMessages;
    public static ConfigItem disableChannelSwipeBack;
    public static ConfigItem disableProfileSwipeBack;
    public static ConfigItem hideProxySponsor;
    public static ConfigItem customCalendar;

    // Other Features
    public static ConfigItem otherFeatures;
    public static ConfigItem removesContentSaving;
    public static ConfigItem telegramPremium;
    public static ConfigItem disableNumberRounding;
    public static ConfigItem hideUpdateApp;
    public static ConfigItem fixTLError;

    // Message Details
    public static ConfigItem showMessageDetails;

    // New features
    public static ConfigItem noSponsoredMessages;
    public static ConfigItem noForwardRestriction;
    public static ConfigItem antiPhoneCall;

    public static ConfigItem ignoreBlocked;
    public static ConfigItem teleVipAi;

    // New features
    public static ConfigItem showOthersPhone;
    public static ConfigItem showRealLastSeen;
    public static ConfigItem bypassSeenBy;
    public static ConfigItem screenshotBypass;
    public static ConfigItem anonymousForward;
    public static ConfigItem noMessageLimit;
    public static ConfigItem unlockGroupInput;
    public static ConfigItem suppressBanKick;
    public static ConfigItem antiContactSync;
    public static ConfigItem privacyForcer;
    public static ConfigItem spoofDeviceInfo;
    public static ConfigItem messageSchedulerBypass;
    public static ConfigItem unlockTranslateButton;

    // Button
    public static ConfigItem btnChannel;
    public static ConfigItem btnRestartApp;

    public static void loadAndRead(Context context){
        ConfigPreferences.init();
        load(context);
        readFeature(context);
    }

    public static void load(Context context) {
        // GhostMode
        ghostModeSettings = new ConfigItem(ConfigItem.HEADER, Keys.GhostModeSettings);
        items.add(ghostModeSettings);

        hideSeen = new ConfigItem(ConfigItem.SWITCH, Keys.HideSeen, ConfigPreferences.getBoolean(Keys.HideSeen), GhostMode::init);
        items.add(hideSeen);

        markReadAfterSend = new ConfigItem(ConfigItem.SWITCH, Keys.MarkReadAfterSend, ConfigPreferences.getBoolean(Keys.MarkReadAfterSend), GhostMode::init);
        items.add(markReadAfterSend);

        hideTyping = new ConfigItem(ConfigItem.SWITCH, Keys.HideTyping, ConfigPreferences.getBoolean(Keys.HideTyping), GhostMode::init);
        items.add(hideTyping);

        hideStoryView = new ConfigItem(ConfigItem.SWITCH, Keys.HideStoryView, ConfigPreferences.getBoolean(Keys.HideStoryView), GhostMode::init);
        items.add(hideStoryView);

        hidePhone = new ConfigItem(ConfigItem.SWITCH, Keys.HidePhone, true, ConfigPreferences.getBoolean(Keys.HidePhone), HidePhone::init);
        items.add(hidePhone);

        hideOnline = new ConfigItem(ConfigItem.SWITCH, Keys.HideOnline, true, ConfigPreferences.getBoolean(Keys.HideOnline), GhostMode::init);
        items.add(hideOnline);

        showOthersPhone = new ConfigItem(ConfigItem.SWITCH, Keys.ShowOthersPhone, ConfigPreferences.getBoolean(Keys.ShowOthersPhone), () -> ShowOthersPhone.init(context));
        items.add(showOthersPhone);

        showRealLastSeen = new ConfigItem(ConfigItem.SWITCH, Keys.ShowRealLastSeen, ConfigPreferences.getBoolean(Keys.ShowRealLastSeen), ShowRealLastSeen::init);
        items.add(showRealLastSeen);

        onlineInfo = new ConfigItem(ConfigItem.INFO, Keys.OfflineVisibilityInfo);
        items.add(onlineInfo);

        shadows = new ConfigItem(ConfigItem.DIVIDER);
        items.add(shadows);
        if (!ClientChecker.check(ClientChecker.ClientType.Telegraph)) {

            // Stories
            stories = new ConfigItem(ConfigItem.HEADER, Keys.StoriesSettings);
            items.add(stories);

            disableStories = new ConfigItem(ConfigItem.SWITCH, Keys.DisableStories, true, ConfigPreferences.getBoolean(Keys.DisableStories), DisableStories::init);
            items.add(disableStories);

            items.add(shadows);

                        // Messages
            messages = new ConfigItem(ConfigItem.HEADER, Keys.MessagesSettings);
            items.add(messages);

            dontWipeMessages = new ConfigItem(ConfigItem.SWITCH, Keys.DontWipeMessages, ConfigPreferences.getBoolean(Keys.DontWipeMessages), DontWipeMessages::init);
            items.add(dontWipeMessages); // <--- هذا هو السطر الذي أضفناه هنا

            if (!ClientChecker.check(ClientChecker.ClientType.NagramX)) {
                showMessageId = new ConfigItem(ConfigItem.SWITCH, Keys.ShowMessageID, ConfigPreferences.getBoolean(Keys.ShowMessageID), ChatMessageCell::init);
                items.add(showMessageId);
            }


            hideEditedMark = new ConfigItem(ConfigItem.SWITCH, Keys.HideEditedMark, ConfigPreferences.getBoolean(Keys.HideEditedMark), HideEditedMark::init);
            items.add(hideEditedMark);

            saveEditsHistory = new ConfigItem(ConfigItem.SWITCH, Keys.SaveEditsHistory, ConfigPreferences.getBoolean(Keys.SaveEditsHistory), () -> SaveEditsHistory.init(context));
            items.add(saveEditsHistory);

            bypassSlowMode = new ConfigItem(ConfigItem.SWITCH, Keys.BypassSlowMode, ConfigPreferences.getBoolean(Keys.BypassSlowMode), BypassSlowMode::init);
            items.add(bypassSlowMode);

            showMessageDetails = new ConfigItem(ConfigItem.SWITCH, Keys.ShowMessageDetails, ConfigPreferences.getBoolean(Keys.ShowMessageDetails), () -> ShowMessageDetails.init(context));
            items.add(showMessageDetails);

            noSponsoredMessages = new ConfigItem(ConfigItem.SWITCH, Keys.NoSponsoredMessages, ConfigPreferences.getBoolean(Keys.NoSponsoredMessages), NoSponsoredMessages::init);
            items.add(noSponsoredMessages);

            noForwardRestriction = new ConfigItem(ConfigItem.SWITCH, Keys.NoForwardRestriction, ConfigPreferences.getBoolean(Keys.NoForwardRestriction), NoForwardRestriction::init);
            items.add(noForwardRestriction);

            bypassSeenBy = new ConfigItem(ConfigItem.SWITCH, Keys.BypassSeenBy, ConfigPreferences.getBoolean(Keys.BypassSeenBy), BypassSeenBy::init);
            items.add(bypassSeenBy);

            screenshotBypass = new ConfigItem(ConfigItem.SWITCH, Keys.ScreenshotBypass, ConfigPreferences.getBoolean(Keys.ScreenshotBypass), ScreenshotBypass::init);
            items.add(screenshotBypass);

            anonymousForward = new ConfigItem(ConfigItem.SWITCH, Keys.AnonymousForward, ConfigPreferences.getBoolean(Keys.AnonymousForward), AnonymousForward::init);
            items.add(anonymousForward);

            noMessageLimit = new ConfigItem(ConfigItem.SWITCH, Keys.NoMessageLimit, ConfigPreferences.getBoolean(Keys.NoMessageLimit), NoMessageLimit::init);
            items.add(noMessageLimit);

            unlockGroupInput = new ConfigItem(ConfigItem.SWITCH, Keys.UnlockGroupInput, ConfigPreferences.getBoolean(Keys.UnlockGroupInput), UnlockGroupInput::init);
            items.add(unlockGroupInput);

            suppressBanKick = new ConfigItem(ConfigItem.SWITCH, Keys.SuppressBanKick, ConfigPreferences.getBoolean(Keys.SuppressBanKick), SuppressBanKick::init);
            items.add(suppressBanKick);

            antiContactSync = new ConfigItem(ConfigItem.SWITCH, Keys.AntiContactSync, ConfigPreferences.getBoolean(Keys.AntiContactSync), AntiContactSync::init);
            items.add(antiContactSync);

            privacyForcer = new ConfigItem(ConfigItem.SWITCH, Keys.PrivacyForcer, ConfigPreferences.getBoolean(Keys.PrivacyForcer), PrivacyForcer::init);
            items.add(privacyForcer);

            spoofDeviceInfo = new ConfigItem(ConfigItem.SWITCH, Keys.SpoofDeviceInfo, ConfigPreferences.getBoolean(Keys.SpoofDeviceInfo), SpoofDeviceInfo::init);
            items.add(spoofDeviceInfo);

            messageSchedulerBypass = new ConfigItem(ConfigItem.SWITCH, Keys.MessageSchedulerBypass, ConfigPreferences.getBoolean(Keys.MessageSchedulerBypass), MessageSchedulerBypass::init);
            items.add(messageSchedulerBypass);

            unlockTranslateButton = new ConfigItem(ConfigItem.SWITCH, Keys.UnlockTranslateButton, ConfigPreferences.getBoolean(Keys.UnlockTranslateButton), UnlockTranslateButton::init);
            items.add(unlockTranslateButton);

            items.add(shadows);

            // Connections
            connections = new ConfigItem(ConfigItem.HEADER, Keys.ConnectionsSettings);
            items.add(connections);

            downloadSpeed = new ConfigItem(ConfigItem.SWITCH, Keys.DownloadSpeed, ConfigPreferences.getBoolean(Keys.DownloadSpeed), DownloadSpeed::init);
            items.add(downloadSpeed);

            items.add(shadows);

            // Media
            media = new ConfigItem(ConfigItem.HEADER, Keys.MediaSettings);
            items.add(media);

            secretMediaSave = new ConfigItem(ConfigItem.SWITCH, Keys.SecretMediaSave, ConfigPreferences.getBoolean(Keys.SecretMediaSave), SecretMediaSave::init);
            items.add(secretMediaSave);

            preventMedia = new ConfigItem(ConfigItem.SWITCH, Keys.PreventMedia, ConfigPreferences.getBoolean(Keys.PreventMedia), PreventMedia::init);
            items.add(preventMedia);

            enableSavingStories = new ConfigItem(ConfigItem.SWITCH, Keys.EnableSavingStories, ConfigPreferences.getBoolean(Keys.EnableSavingStories), EnableSavingStories::init);
            items.add(enableSavingStories);

            items.add(shadows);
        }

            // UI
            ui = new ConfigItem(ConfigItem.HEADER, Keys.UiSettings);
            items.add(ui);
        if (!ClientChecker.check(ClientChecker.ClientType.Telegraph)) {

            hidePinnedMessages = new ConfigItem(ConfigItem.SWITCH, Keys.HidePinnedMessages, ConfigPreferences.getBoolean(Keys.HidePinnedMessages), HidePinnedMessages::init);
            items.add(hidePinnedMessages);

            disableChannelSwipeBack = new ConfigItem(ConfigItem.SWITCH, Keys.DisableChannelSwipeBack, ConfigPreferences.getBoolean(Keys.DisableChannelSwipeBack), DisableChannelSwipeBack::init);
            items.add(disableChannelSwipeBack);

            disableProfileSwipeBack = new ConfigItem(ConfigItem.SWITCH, Keys.DisableProfileSwipeBack, ConfigPreferences.getBoolean(Keys.DisableProfileSwipeBack), DisableProfileSwipeBack::init);
            items.add(disableProfileSwipeBack);

            hideProxySponsor = new ConfigItem(ConfigItem.SWITCH, Keys.HideProxySponsor, true, ConfigPreferences.getBoolean(Keys.HideProxySponsor), HideProxySponsor::init);
            items.add(hideProxySponsor);

            customCalendar = new ConfigItem(ConfigItem.TEXT, Keys.Calendar, true, HijriDate::init);
            items.add(customCalendar);

            items.add(shadows);

            // Other Features
            otherFeatures = new ConfigItem(ConfigItem.HEADER, Keys.OtherFeaturesSettings);
            items.add(otherFeatures);

            removesContentSaving = new ConfigItem(ConfigItem.SWITCH, Keys.RemovesContentSaving, ConfigPreferences.getBoolean(Keys.RemovesContentSaving), RemovesContentSaving::init);
            items.add(removesContentSaving);

            antiPhoneCall = new ConfigItem(ConfigItem.SWITCH, Keys.AntiPhoneCall, ConfigPreferences.getBoolean(Keys.AntiPhoneCall), AntiPhoneCall::init);
            items.add(antiPhoneCall);

            ignoreBlocked = new ConfigItem(ConfigItem.SWITCH, Keys.IgnoreBlocked, ConfigPreferences.getBoolean(Keys.IgnoreBlocked), IgnoreBlocked::init);
            items.add(ignoreBlocked);

            teleVipAi = new ConfigItem(ConfigItem.SWITCH, Keys.TeleVipAi, ConfigPreferences.getBoolean(Keys.TeleVipAi), TeleVipAi::init);
            items.add(teleVipAi);
        }

        telegramPremium = new ConfigItem(ConfigItem.SWITCH, Keys.TelegramPremium, ConfigPreferences.getBoolean(Keys.TelegramPremium), TelePremium::init);
        items.add(telegramPremium);

        if (!ClientChecker.check(ClientChecker.ClientType.Telegraph)) {
            disableNumberRounding = new ConfigItem(ConfigItem.SWITCH, Keys.DisableNumberRounding, "5.3K -> 5300", ConfigPreferences.getBoolean(Keys.DisableNumberRounding), DisableNumberRounding::init);
            hideUpdateApp = new ConfigItem(ConfigItem.SWITCH, Keys.HideUpdateApp, true, ConfigPreferences.getBoolean(Keys.HideUpdateApp), HideUpdateApp::init);
            fixTLError = new ConfigItem(ConfigItem.SWITCH, Keys.FixTLError, ConfigPreferences.getBoolean(Keys.FixTLError), FixTLError::init);
            items.add(disableNumberRounding);
            items.add(hideUpdateApp);
            items.add(fixTLError);
        }

        items.add(shadows);

        btnChannel = new ConfigItem(ConfigItem.TEXT, Keys.DeveloperChannel);
        items.add(btnChannel);

        items.add(shadows);

        btnRestartApp = new ConfigItem(ConfigItem.TEXT, Keys.RestartApp);
        items.add(btnRestartApp);

        items.add(shadows);
    }

    public static List<ConfigItem> getItems() {
        return items;
    }

    public static void readFeature(Context context) {
        try {
            for (ConfigItem item : items) {
                if (item == null) continue;
                if (item.getType() != ConfigItem.SWITCH && item.getCustomCalendar() == 0) continue;
                if (item.isEnable()) item.run();
            }

            if (!ClientChecker.check(ClientChecker.ClientType.Telegraph)) {
                FeatureInitializer.init(context);
                CopyNameHook.init(context);
                AlwaysSaveMedia.init();
                if (!ClientChecker.check(ClientChecker.ClientType.Nagram) && !ClientChecker.check(ClientChecker.ClientType.NagramX)) EditOnlineTextView.init(context);
            } else {
                Telegraph.removeAd();
            }

        } catch (Throwable e) {
            Logger.e(e);
        }
    }

    public static boolean isGhostMode(){
        return hideSeen.isEnable() ||
                hideStoryView.isEnable() ||
                hideTyping.isEnable() ||
                hideOnline.isEnable() ||
                markReadAfterSend.isEnable();

    }

}

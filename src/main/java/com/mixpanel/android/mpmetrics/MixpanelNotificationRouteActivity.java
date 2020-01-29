package com.mixpanel.android.mpmetrics;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.URLUtil;

import com.mixpanel.android.util.MPLog;
import com.mixpanel.android.mpmetrics.MixpanelNotificationData.PushTapActionType;

import org.json.JSONException;
import org.json.JSONObject;

public class MixpanelNotificationRouteActivity extends Activity {

    protected final String LOGTAG = "MixpanelAPI.MixpanelNotificationRouteActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent routeIntent = getIntent();
        Bundle extras = routeIntent.getExtras();

        if (null == routeIntent) {
            MPLog.d(LOGTAG, "Notification route activity given null intent.");
            return;
        }

        trackAction(routeIntent);

        final Intent notificationIntent = handleRouteIntent(routeIntent);

        if (!extras.getBoolean("sticky")) {
            MixpanelFCMMessagingService fcmMessagingService = new MixpanelFCMMessagingService();
            NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(getApplicationContext().NOTIFICATION_SERVICE);
            fcmMessagingService.cancelNotification(extras, notificationManager);
        }
        startActivity(notificationIntent);
    }

    protected Intent handleRouteIntent(Intent routeIntent) {
        CharSequence actionTypeChars = routeIntent.getExtras().getCharSequence("actionType");
        PushTapActionType target;
        if (null == actionTypeChars) {
            MPLog.d(LOGTAG, "Notification action click logged with no action type");
            target = PushTapActionType.HOMESCREEN;
        } else {
            target = PushTapActionType.fromString(actionTypeChars.toString());
        }

        CharSequence uri = routeIntent.getExtras().getCharSequence("uri");

        final Intent defaultIntent = this.getPackageManager().getLaunchIntentForPackage(this.getPackageName());

        switch (target) {
            case HOMESCREEN:
                return defaultIntent;
            case URL_IN_BROWSER:
                if (URLUtil.isValidUrl(uri.toString())) {
                    return new Intent(Intent.ACTION_VIEW, Uri.parse(uri.toString()));
                } else {
                    MPLog.d(LOGTAG, "Wanted to open url in browser but url is invalid: " + uri.toString() + ". Starting default intent");
                    return defaultIntent;
                }
            case DEEP_LINK:
                return new Intent(Intent.ACTION_VIEW, Uri.parse(uri.toString()));
            default:
                return defaultIntent;
        }
    }

    protected void trackAction(Intent routeIntent) {
        Bundle intentExtras = routeIntent.getExtras();

        CharSequence tapTargetChars = intentExtras.getCharSequence("tapTarget");
        if (null == tapTargetChars) {
            MPLog.d(LOGTAG, "Notification action click logged with no tapTarget");
            return;
        }

        final String tapTarget = tapTargetChars.toString();
        final String buttonId;
        final String label;

        if (tapTarget.equals(MixpanelPushNotification.TAP_TARGET_BUTTON)) {
            CharSequence buttonIdChars = intentExtras.getCharSequence("buttonId");
            if (null == buttonIdChars) {
                MPLog.d(LOGTAG, "Notification action click logged with no buttonId");
            }

            CharSequence labelChars = intentExtras.getCharSequence("label");
            if (null == labelChars) {
                MPLog.d(LOGTAG, "Notification action click logged with no label");
            }

            buttonId = buttonIdChars == null ? null : buttonIdChars.toString();
            label = labelChars == null ? null : labelChars.toString();
        } else {
            buttonId = null;
            label = null;
        }

        CharSequence messageIdChars = intentExtras.getCharSequence("messageId");
        if (null == messageIdChars) {
            MPLog.d(LOGTAG, "Notification action click logged with no messageId");
        }

        CharSequence campaignIdChars = intentExtras.getCharSequence("campaignId");
        if (null == campaignIdChars) {
            MPLog.d(LOGTAG, "Notification action click logged with no campaignId");
        }

        final String messageId = messageIdChars == null ? null : messageIdChars.toString();
        final String campaignId = campaignIdChars == null ? null : campaignIdChars.toString();

        MixpanelAPI.allInstances(new MixpanelAPI.InstanceProcessor() {
            @Override
            public void process(MixpanelAPI api) {
                JSONObject pushProps = new JSONObject();
                try {
                    pushProps.put("tap_target", tapTarget);
                    if (tapTarget.equals(MixpanelPushNotification.TAP_TARGET_BUTTON)) {
                        pushProps.put("button_id", buttonId);
                        pushProps.put("button_label", label);
                    }
                    pushProps.put("message_id", messageId);
                    pushProps.put("campaign_id", campaignId);
                } catch (JSONException e) {
                    MPLog.e(LOGTAG, "Error loading tracking JSON properties.");
                }
                api.track("$push_notification_tap", pushProps);
            }
        });
    }

}

package com.autov.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import org.json.JSONObject;
import java.util.List;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsReceiver";
    private static final String SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !SMS_RECEIVED.equals(intent.getAction())) return;

        final PendingResult result = goAsync();
        try {
            QueueUploader.flushQueueIfAnyAsync(context);

            JSONObject payloadBase = buildBasePayload(context, intent);
            if (payloadBase != null) {
                processIncomingSms(context, payloadBase);
                QueueUploader.flushQueueIfAnyAsync(context);
            }
        } catch (Exception e) {
            Log.e(TAG, "onReceive error", e);
        } finally {
            result.finish();
        }
    }

    private void processIncomingSms(Context context, JSONObject payloadBase) {
        try {
            long ts = System.currentTimeMillis();
            String isoDate = payloadBase.optString("date", Iso.now());
            String fromNumber = payloadBase.optString("from", "");
            String body = payloadBase.optString("body", "");
            int subId = payloadBase.optInt("sim_id", -1);

            int simIndex = payloadBase.optInt("sim_index", -1);

            // Save to History Database once
            long dbId = SmsDatabaseHelper.getInstance(context).insertSms(
                    fromNumber, body, ts, SmsDatabaseHelper.STATUS_PENDING, subId, simIndex, isoDate, null
            );
            payloadBase.put("_db_id", dbId);

            // Notify UI of new SMS immediately
            try {
                Intent updateIntent = new Intent(QueueUploader.ACTION_SMS_STATUS_UPDATED);
                updateIntent.setPackage(context.getPackageName());
                updateIntent.putExtra("sms_id", dbId);
                updateIntent.putExtra("status", SmsDatabaseHelper.STATUS_PENDING);
                context.sendBroadcast(updateIntent);
            } catch (Exception ignore) {}

            // Get all configs and send if matched
            List<SmsDatabaseHelper.Config> configs = SmsDatabaseHelper.getInstance(context).getAllConfigs();
            
            if (configs.isEmpty()) {
                // No configurations exist at all
                Log.d(TAG, "No configurations exist for SMS from " + fromNumber);
                showNotification(context, fromNumber, body, "no_config");
                return;
            }
            
            boolean hasActiveConfig = false;
            int matchCount = 0;
            
            for (SmsDatabaseHelper.Config config : configs) {
                if (config.isActive) {
                    hasActiveConfig = true;
                    
                    // SIM Match
                    // simIndex: 0=Both, 1=Sim1, 2=Sim2
                    // detected simIndex (1-based slot index)
                    int detectedSimIndex = payloadBase.optInt("_detected_sim_index", -1);
                    if (config.simIndex != 0 && detectedSimIndex != -1) {
                        if (config.simIndex != detectedSimIndex) continue;
                    }

                    // If matched SIM, trigger async send
                    // QueueUploader will do the whitelist check inside
                    matchCount++;
                    QueueUploader.sendToConfigAsync(context, new JSONObject(payloadBase.toString()), config, "new-sms");
                }
            }

            if (!hasActiveConfig) {
                // Configurations exist but all are inactive
                Log.d(TAG, "All configurations are inactive for SMS from " + fromNumber);
                showNotification(context, fromNumber, body, "inactive");
            } else if (matchCount == 0) {
                // Active configurations exist but none matched (wrong SIM or whitelist)
                Log.d(TAG, "No active config matched for SMS from " + fromNumber);
                showNotification(context, fromNumber, body, "no_match");
            } else {
                // Success! User requested "say sned that"
                showNotification(context, fromNumber, body, "sending");
            }

        } catch (Exception e) {
            Log.e(TAG, "processIncomingSms error", e);
        }
    }

    private JSONObject buildBasePayload(Context context, Intent intent) {
        try {
            QueueUploader.ensureBaselineNow(context);
            long baseline = QueueUploader.getBaseline(context);

            Bundle bundle = intent.getExtras();
            if (bundle == null) return null;

            Object[] pdus = (Object[]) bundle.get("pdus");
            String format = bundle.getString("format");
            int subId = bundle.getInt("subscription", SubscriptionManager.INVALID_SUBSCRIPTION_ID);

            if (pdus == null || pdus.length == 0) return null;

            String fromNumber = "";
            StringBuilder body = new StringBuilder();
            long ts = System.currentTimeMillis();

            for (Object pdu : pdus) {
                SmsMessage msg = SmsMessage.createFromPdu((byte[]) pdu, format);
                if (msg == null) continue;
                if (fromNumber.isEmpty() && msg.getOriginatingAddress() != null) fromNumber = msg.getOriginatingAddress();
                if (msg.getTimestampMillis() > 0) ts = msg.getTimestampMillis();
                if (msg.getMessageBody() != null) body.append(msg.getMessageBody());
            }

            if (ts < baseline) return null;

            // SIM info (best-effort)
            SimInfoUtil.SimInfo si = SimInfoUtil.read(context, subId);
            int simSlot = -1;
            int simIndexDetected = -1;
            try {
                SubscriptionManager sm = SubscriptionManager.from(context);
                SubscriptionInfo info = (sm != null) ? sm.getActiveSubscriptionInfo(subId) : null;
                if (info != null) {
                    simSlot = info.getSimSlotIndex();
                    simIndexDetected = (simSlot >= 0) ? simSlot + 1 : -1;
                }
            } catch (SecurityException ignore) {}

            String maskedNumber = SimInfoUtil.maskNumber(si.phoneNumber);
            String mobile_address = DeviceIdUtil.get(context);
            String token = "";
            List<SmsDatabaseHelper.Config> configs = SmsDatabaseHelper.getInstance(context).getAllConfigs();
            if (!configs.isEmpty()) {
                for (SmsDatabaseHelper.Config c : configs) {
                    if (c.isActive && c.token != null) {
                        token = c.token;
                        break;
                    }
                }
                if (token.isEmpty()) token = configs.get(0).token;
            }
            
            // Get battery info
            int batteryLevel = -1;
            boolean isCharging = false;
            try {
                android.content.IntentFilter ifilter = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
                android.content.Intent batteryStatus = context.registerReceiver(null, ifilter);
                if (batteryStatus != null) {
                    int level = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                    int scale = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                    if (level != -1 && scale != -1) {
                        batteryLevel = (int) ((level / (float) scale) * 100);
                    }
                    int status = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                    isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                 status == android.os.BatteryManager.BATTERY_STATUS_FULL;
                }
            } catch (Exception ignore) {}
            
            JSONObject payload = new JSONObject()
                    .put("type", "incoming_new")
                    .put("token", token)
                    .put("mobile_address", mobile_address)
                    .put("battery_level", batteryLevel)
                    .put("is_charging", isCharging)
                    .put("from", fromNumber)
                    .put("body", sanitizeBody(body.toString()))
                    .put("sim_id", subId)
                    .put("sim_slot", simSlot)
                    .put("sim_index", simIndexDetected) // pass detected one for internal matching
                    .put("_detected_sim_index", simIndexDetected)
                    .put("sim_name", si.label)
                    .put("carrier_name_raw", si.operatorName)
                    .put("operator_numeric", si.operatorNumeric)
                    .put("mcc", si.mcc)
                    .put("mnc", si.mnc)
                    .put("line_number", maskedNumber)
                    .put("iccid_available", si.iccid != null)
                    .put("date", Iso.fromMillis(ts));

            QueueUploader.maybeAdvanceBaseline(context, ts);
            return payload;
        } catch (Exception e) {
            Log.e(TAG, "buildBasePayload error", e);
            return null;
        }
    }

    private static String sanitizeBody(String s) {
        if (s == null) return "";
        s = s.replaceAll("[\\p{Cntrl}&&[^\n\r\t]]", "");
        s = s.trim();
        final int MAX = 4000;
        return s.length() > MAX ? s.substring(0, MAX) : s;
    }

    private void showNotification(Context context, String fromNumber, String body, String reason) {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) 
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            String channelId = "sms_alerts";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        channelId,
                        "SMS Alerts",
                        reason.equals("sending") ? android.app.NotificationManager.IMPORTANCE_LOW : android.app.NotificationManager.IMPORTANCE_HIGH
                );
                nm.createNotificationChannel(channel);
            }

            Intent intent = new Intent(context, DashboardActivity.class);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    context, 0, intent,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M 
                            ? android.app.PendingIntent.FLAG_IMMUTABLE 
                            : 0
            );

            String title, message, bigText;
            int icon = android.R.drawable.ic_dialog_email;
            
            switch (reason) {
                case "no_config":
                    title = "No Configuration";
                    message = "You dont have conf"; // Matches user request
                    bigText = "SMS from " + fromNumber + " not forwarded.\n\nYou don't have configuration.";
                    icon = android.R.drawable.ic_dialog_alert;
                    break;
                case "inactive":
                    title = "No Active Configuration"; // Matches "no active cong"
                    message = "Configuration is inactive";
                    bigText = "SMS from " + fromNumber + " not forwarded.\n\nConfiguration is OFF (inactive).";
                    icon = android.R.drawable.ic_dialog_alert;
                    break;
                case "sending":
                    title = "Forwarding SMS";
                    message = "Sending SMS..."; // Matches "say sned that"
                    bigText = "Forwarding SMS from " + fromNumber + " to server.";
                    icon = android.R.drawable.ic_menu_send;
                    break;
                case "no_match":
                default:
                    title = "SMS Not Forwarded";
                    message = "No matching config";
                    bigText = "SMS from " + fromNumber + " matches no active configuration rules.";
                    icon = android.R.drawable.ic_dialog_alert;
                    break;
            }

            android.app.Notification notification = new android.app.Notification.Builder(context, channelId)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(new android.app.Notification.BigTextStyle()
                            .bigText(bigText + "\n\nMessage: " + body))
                    .setSmallIcon(icon)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();

            nm.notify((int) System.currentTimeMillis(), notification);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show notification", e);
        }
    }
}

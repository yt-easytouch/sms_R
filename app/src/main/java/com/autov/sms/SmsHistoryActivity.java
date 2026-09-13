package com.autov.sms;

import android.database.Cursor;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Context; // Added
import android.os.Build;       // Added

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.datepicker.MaterialDatePicker;

import org.json.JSONObject;

import android.view.Menu;
import android.view.MenuItem;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.EditText;
import android.view.LayoutInflater;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class SmsHistoryActivity extends AppCompatActivity implements SmsAdapter.OnResendClickListener {

    private RecyclerView recyclerView;
    private SmsAdapter adapter;
    private TextView tvDateFilter;
    private android.widget.EditText etSearch;
    private View searchContainer, emptyState;
    private MaterialButton btnClearFilter, btnSearch;
    private View btnCloseSearch;
    private String currentDateFilter = null;
    private boolean hasHistory = false;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefreshLayout;
    
    // BroadcastReceiver for SMS status updates
    private android.content.BroadcastReceiver smsStatusReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            // Refresh the list when SMS status is updated
            loadData();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        getWindow().setStatusBarColor(android.graphics.Color.parseColor("#00B09B"));
        new androidx.core.view.WindowInsetsControllerCompat(
                getWindow(), getWindow().getDecorView()
        ).setAppearanceLightStatusBars(false);

        super.onCreate(savedInstanceState);
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        setContentView(R.layout.activity_sms_history);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
        }

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SmsAdapter(this);
        recyclerView.setAdapter(adapter);

        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        swipeRefreshLayout.setColorSchemeColors(android.graphics.Color.parseColor("#00B09B"));
        swipeRefreshLayout.setOnRefreshListener(() -> {
            loadData();
            swipeRefreshLayout.setRefreshing(false);
        });

        tvDateFilter = findViewById(R.id.tvDateFilter);
        btnSearch = findViewById(R.id.btnSearch);
        searchContainer = findViewById(R.id.searchContainer);
        etSearch = findViewById(R.id.etSearch);
        btnCloseSearch = findViewById(R.id.btnCloseSearch);
        btnClearFilter = findViewById(R.id.btnClearFilter);
        emptyState = findViewById(R.id.emptyState);

        btnSearch.setOnClickListener(v -> {
            if (searchContainer.getVisibility() == View.VISIBLE) {
                // If already visible, hide it
                searchContainer.setVisibility(View.GONE);
                etSearch.setText(""); // Clear search when hiding? Optional.
                loadData();
            } else {
                searchContainer.setVisibility(View.VISIBLE);
                etSearch.requestFocus();
                // Show keyboard logic could be added here
            }
        });

        btnCloseSearch.setOnClickListener(v -> {
            etSearch.setText("");
            searchContainer.setVisibility(View.GONE);
            loadData();
        });

        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                loadData();
            }
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        findViewById(R.id.btnPickDate).setOnClickListener(v -> showDatePicker());
        btnClearFilter.setOnClickListener(v -> {
            currentDateFilter = null;
            tvDateFilter.setText("All Dates");
            btnClearFilter.setVisibility(View.GONE);
            loadData();
        });

        // Register broadcast receiver for SMS status updates
        android.content.IntentFilter filter = new android.content.IntentFilter(QueueUploader.ACTION_SMS_STATUS_UPDATED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(smsStatusReceiver, filter);
        }

        // Start outgoing SMS poller
        OutgoingSmsPoller.start(this);

        loadData();
    }

    private void showDatePicker() {
        MaterialDatePicker<Long> datePicker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Select Date")
                .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
                .build();

        datePicker.addOnPositiveButtonClickListener(selection -> {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            currentDateFilter = sdf.format(new Date(selection));
            tvDateFilter.setText("Date: " + currentDateFilter);
            btnClearFilter.setVisibility(View.VISIBLE);
            loadData();
        });

        datePicker.show(getSupportFragmentManager(), "DATE_PICKER");
    }

    private void loadData() {
        List<SmsAdapter.SmsRecord> records = new ArrayList<>();
        String searchText = etSearch != null ? etSearch.getText().toString() : null;
        Cursor cursor = SmsDatabaseHelper.getInstance(this).getAllSmsCursor(currentDateFilter, searchText);
        
        if (cursor != null) {
            while (cursor.moveToNext()) {
                records.add(new SmsAdapter.SmsRecord(
                        cursor.getLong(cursor.getColumnIndexOrThrow(SmsDatabaseHelper.COLUMN_ID)),
                        cursor.getString(cursor.getColumnIndexOrThrow(SmsDatabaseHelper.COLUMN_FROM)),
                        cursor.getString(cursor.getColumnIndexOrThrow(SmsDatabaseHelper.COLUMN_BODY)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(SmsDatabaseHelper.COLUMN_TIMESTAMP)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(SmsDatabaseHelper.COLUMN_STATUS)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(SmsDatabaseHelper.COLUMN_SIM_ID)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(SmsDatabaseHelper.COLUMN_SIM_INDEX)),
                        cursor.getString(cursor.getColumnIndexOrThrow(SmsDatabaseHelper.COLUMN_ISO_DATE)),
                        cursor.getString(cursor.getColumnIndexOrThrow(SmsDatabaseHelper.COLUMN_RESPONSE)),
                        cursor.getString(cursor.getColumnIndexOrThrow(SmsDatabaseHelper.COLUMN_URL)),
                        cursor.getString(cursor.getColumnIndexOrThrow(SmsDatabaseHelper.COLUMN_TOKEN))
                ));
            }
            cursor.close();
        }
        adapter.setItems(records);
        hasHistory = !records.isEmpty();
        invalidateOptionsMenu();
        
        if (!hasHistory) {
            recyclerView.setVisibility(View.GONE);
            emptyState.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyState.setVisibility(View.GONE);
        }
    }

    @Override
    public void onResendClick(SmsAdapter.SmsRecord record) {
        if (record.from != null && record.from.startsWith("To:")) {
            pendingSmsRecordToForward = record;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.SEND_SMS}, 102);
            } else {
                resendOutgoingSms(record);
            }
            return;
        }

        try {
        String mobile_address = DeviceIdUtil.get(this);
            SimInfoUtil.SimInfo si = SimInfoUtil.read(this, record.simId);
            String maskedNumber = SimInfoUtil.maskNumber(si.phoneNumber);

            int configuredSimIndex = getSharedPreferences(Const.PREF_NAME, MODE_PRIVATE)
                    .getInt(Const.PREF_SIM_INDEX, 1);

            int batteryLevel = -1;
            boolean isCharging = false;
            try {
                android.content.IntentFilter ifilter = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
                android.content.Intent batteryStatus = this.registerReceiver(null, ifilter);
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

            String token = "";
            List<SmsDatabaseHelper.Config> configs = SmsDatabaseHelper.getInstance(this).getAllConfigs();
            if (!configs.isEmpty()) {
                for (SmsDatabaseHelper.Config c : configs) {
                    if (c.isActive && c.token != null) {
                        token = c.token;
                        break;
                    }
                }
                if (token.isEmpty()) token = configs.get(0).token;
            }

            JSONObject payload = new JSONObject()
                    .put("type", "incoming_resend")
                    .put("token", token)
                    .put("mobile_address", mobile_address)
                    .put("battery_level", batteryLevel)
                    .put("is_charging", isCharging)
                    .put("from", record.from)
                    .put("body", record.body)
                    .put("sim_id", record.simId)
                    .put("sim_index", configuredSimIndex) // Use configured index
                    .put("line_number", maskedNumber)
                    .put("date", record.isoDate)
                    .put("_db_id", record.id);

            int matchCount = 0;
            
            // Mark as pending immediately for UI feedback
            SmsDatabaseHelper.getInstance(this).updateStatusResponseUrlAndToken(record.id, SmsDatabaseHelper.STATUS_PENDING, "Sending...", null, null);
            loadData(); // Refresh UI to show PENDING state
            
            for (SmsDatabaseHelper.Config config : configs) {
                if (!config.isActive) continue;

                // Match SIM matches...
                
                matchCount++;
                
                // Update the URL and Token in DB immediately so user sees the new URL/Token in details
                String targetUrl = config.url;
                if (config.serverType == 1) {
                    targetUrl = Const.AUTOV_SMS_UPLOAD;
                }
                SmsDatabaseHelper.getInstance(this).updateStatusResponseUrlAndToken(record.id, SmsDatabaseHelper.STATUS_PENDING, "Sending...", targetUrl, config.token);
                loadData(); // Refresh UI again to show new URL/Token
                
                QueueUploader.sendToConfigAsync(this, new JSONObject(payload.toString()), config, "resend-manual");
            }

            if (matchCount > 0) {
                Toast.makeText(this, "Sending...", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "No active configurations found!", Toast.LENGTH_SHORT).show();
                // Revert status if no configs
                SmsDatabaseHelper.getInstance(this).updateStatusResponseUrlAndToken(record.id, SmsDatabaseHelper.STATUS_FAILED, "No active config", null, null);
                loadData();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDetailClick(SmsAdapter.SmsRecord record) {
        String url = record.url;
        String resp = record.response;
        String token = record.token;

        // If URL is missing, it means SMS was NOT forwarded (no configuration matched)
        if (url == null || url.isEmpty()) {
            url = "Not forwarded - No configuration";
            resp = "This SMS was not sent to any server because:\n" +
                   "• No configuration existed, OR\n" +
                   "• All configurations were inactive, OR\n" +
                   "• No configuration matched (wrong SIM or sender not whitelisted)\n\n" +
                   "Create or activate a configuration to forward future SMS.";
        } else {
            // URL exists, show it
            if (resp == null || resp.isEmpty()) {
                resp = "No response data";
            }
        }

        // Format the message
        String msg;
        if (url.equals(Const.AUTOV_SMS_UPLOAD)) {
            msg = "Token: " + (token != null ? token : "None") + "\n\nResponse:\n" + resp;
        } else {
            msg = "URL:\n" + url + "\n\nToken: " + (token != null ? token : "None") + "\n\nResponse:\n" + resp;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("Server Details")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show();
    }
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_sms_history, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem clearItem = menu.findItem(R.id.action_clear_history);
        if (clearItem != null) {
            clearItem.setVisible(hasHistory);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_clear_history) {
            showClearHistoryDialog();
            return true;
        } else if (id == R.id.action_config) {
            startActivity(new android.content.Intent(this, DashboardActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Unregister broadcast receiver to prevent memory leaks
        try {
            unregisterReceiver(smsStatusReceiver);
        } catch (Exception e) {
            // Receiver might not be registered
        }
        
        OutgoingSmsPoller.stop();
    }

    private void showClearHistoryDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Clear History")
                .setMessage("Are you sure you want to delete all SMS history?")
                .setPositiveButton("Clear All", (dialog, which) -> {
                    SmsDatabaseHelper.getInstance(this).deleteAllSms();
                    loadData(); // Refresh the list
                    Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private SmsAdapter.SmsRecord pendingSmsRecordToForward = null;

    @Override
    public void onRequestPermissionsResult(int requestCode, @androidx.annotation.NonNull String[] permissions, @androidx.annotation.NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 102) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingSmsRecordToForward != null) {
                    resendOutgoingSms(pendingSmsRecordToForward);
                }
            } else {
                Toast.makeText(this, "Permission denied to send SMS", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void resendOutgoingSms(SmsAdapter.SmsRecord record) {
        String phone = record.from.replaceFirst("^To:\\s*", "").trim();
        int subId = -1;

        try {
            SubscriptionManager sm = SubscriptionManager.from(this);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                List<SubscriptionInfo> activeSims = sm.getActiveSubscriptionInfoList();
                if (activeSims != null) {
                    for (int i = 0; i < activeSims.size(); i++) {
                        SubscriptionInfo info = activeSims.get(i);
                        if (record.simIndex == 1 && info.getSimSlotIndex() == 0) subId = info.getSubscriptionId();
                        if (record.simIndex == 2 && info.getSimSlotIndex() == 1) subId = info.getSubscriptionId();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        Toast.makeText(this, "Resending SMS...", Toast.LENGTH_SHORT).show();
        sendSms(subId, phone, record.body, record.simIndex, record.id);
    }

    private void sendSms(int subId, String phone, String msg, int simIndex, long dbId) {
        // Record in Database
        SmsDatabaseHelper.getInstance(this).updateStatusResponseUrlAndToken(dbId, SmsDatabaseHelper.STATUS_PENDING, "Sending...", null, null);
        loadData();

        // Physical SMS sending logic
        boolean hardwareSuccess = true;
        try {
            SmsManager smsManager;
            if (subId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);
            } else {
                smsManager = SmsManager.getDefault();
            }
            if (smsManager != null) {
                smsManager.sendTextMessage(phone, null, msg, null, null);
                Toast.makeText(this, "SMS Sent", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Could not find SMS Manager", Toast.LENGTH_SHORT).show();
                hardwareSuccess = false;
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to send SMS: " + e.getMessage(), Toast.LENGTH_LONG).show();
            hardwareSuccess = false;
        }

        final boolean finalHardwareSuccess = hardwareSuccess;

        // POST confirmation to the active config endpoint (not any hardcoded test URL)
        new Thread(() -> {
            // Get battery info
            int batteryLevel = -1;
            boolean isCharging = false;
            try {
                android.content.IntentFilter ifilter = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
                android.content.Intent batteryStatus = SmsHistoryActivity.this.registerReceiver(null, ifilter);
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

            // Find the first active config to get the real endpoint + token
            List<SmsDatabaseHelper.Config> configs = SmsDatabaseHelper.getInstance(SmsHistoryActivity.this).getAllConfigs();
            SmsDatabaseHelper.Config activeConfig = null;
            for (SmsDatabaseHelper.Config c : configs) {
                if (c.isActive) { activeConfig = c; break; }
            }

            if (activeConfig == null) {
                int s = finalHardwareSuccess ? SmsDatabaseHelper.STATUS_SENT : SmsDatabaseHelper.STATUS_FAILED;
                SmsDatabaseHelper.getInstance(SmsHistoryActivity.this)
                        .updateStatusResponseUrlAndToken(dbId, s, "No active config to report to", null, null);
                runOnUiThread(SmsHistoryActivity.this::loadData);
                return;
            }

            final SmsDatabaseHelper.Config config = activeConfig;
            String endpoint = config.serverType == 1 ? Const.AUTOV_SMS_UPLOAD : config.url;
            if (endpoint == null || endpoint.isEmpty()) {
                int s = finalHardwareSuccess ? SmsDatabaseHelper.STATUS_SENT : SmsDatabaseHelper.STATUS_FAILED;
                SmsDatabaseHelper.getInstance(SmsHistoryActivity.this)
                        .updateStatusResponseUrlAndToken(dbId, s, "No URL configured", null, config.token);
                runOnUiThread(SmsHistoryActivity.this::loadData);
                return;
            }

            boolean success = false;
            String serverResponse = "";
            try {
                JSONObject payload = new JSONObject();
                payload.put("action", "send_sms");
                payload.put("battery_level", batteryLevel);
                payload.put("is_charging", isCharging);
                payload.put("to", phone);
                payload.put("message", msg);
                payload.put("sim_id", subId);
                payload.put("sim_index", simIndex);
                if (config.token != null && !config.token.isEmpty()) {
                    payload.put("token", config.token);
                }

                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("X-Source", "outgoing-resend");
                if (config.token != null && !config.token.isEmpty()) {
                    conn.setRequestProperty("X-Autov-Token", config.token);
                }
                conn.setDoOutput(true);

                byte[] out = payload.toString().getBytes("UTF-8");
                OutputStream os = conn.getOutputStream();
                os.write(out);
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                serverResponse = "HTTP " + responseCode;
                success = (responseCode >= 200 && responseCode < 300);
                android.util.Log.d("SendSMS", "POST to config endpoint response: " + responseCode);
                conn.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
                serverResponse = "Error: " + e.getMessage();
            }

            int finalStatus = (success && finalHardwareSuccess) ? SmsDatabaseHelper.STATUS_SENT : SmsDatabaseHelper.STATUS_FAILED;
            SmsDatabaseHelper.getInstance(SmsHistoryActivity.this)
                    .updateStatusResponseUrlAndToken(dbId, finalStatus, serverResponse, endpoint, config.token);
            runOnUiThread(SmsHistoryActivity.this::loadData);
        }).start();
    }
}

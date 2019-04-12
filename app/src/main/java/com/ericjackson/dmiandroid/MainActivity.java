package com.ericjackson.dmiandroid;

import androidx.appcompat.app.AppCompatActivity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.location.Location;
import android.os.Bundle;
import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelUuid;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    // UUIDs for UAT service and associated characteristics.
    public static UUID UART_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    public static String UART_UUIDstr = ("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    public static UUID TX_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    public static UUID RX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    // UUID for the BTLE client characteristic which is necessary for notifications.
    public static UUID CLIENT_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 1;

    // UI elements
    private TextView messages;
    private TextView input;
    private TextView statusMsg;
    private TextView arm;
    private EditText calibration;

    // BTLE state
    private BluetoothAdapter adapter;
    private List<ScanFilter> filters;
    private ScanSettings settings;
    private BluetoothLeScanner LEScanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic tx;
    private BluetoothGattCharacteristic rx;
    private ScanCallback scanCallback;
    private boolean scanning;
    private boolean connected=false;
    private static String TAG = "eric";
    private Handler tryConnectAgainHandler = new Handler();
    private Handler bleHandler = new Handler();
    private int numConnectionsCreated = 0;
    private boolean bleOnBeforeCreate=true;
    private boolean adaptorIsReady=false;
    private double armValue = 0;
    private final static int MAX_LINE = 15;
    private double calibrationValue = 1;
    private SharedPreferences sharedSettings;
    private milePostLocation nearestLocation ;
    private dbHelper db;

    // OnCreate, called once to initialize the activity.
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); // Always call the superclass first
        setContentView(R.layout.activity_main);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT); // Make to run your application only in portrait mode

        sharedSettings = PreferenceManager.getDefaultSharedPreferences(this);
        calibrationValue = sharedSettings.getFloat("calibrationValue", 1);

        // Grab references to UI elements.
        messages = findViewById(R.id.receivedText);
        statusMsg = findViewById(R.id.statusText);
        arm = findViewById(R.id.MPview);
        calibration = findViewById(R.id.calibText);
        calibration.setText(Double.toString(calibrationValue));

        messages.setMovementMethod(new ScrollingMovementMethod());
        messages.setFocusableInTouchMode(true);
        messages.requestFocus();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_COARSE_LOCATION);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        adapter = BluetoothAdapter.getDefaultAdapter();

        //Set a filter to only receive bluetooth state changed events.
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(mReceiver, filter);

        if(!adapter.isEnabled() && adapter != null){
            bleOnBeforeCreate=false;
            adapter.enable();
        } else {
            adaptorIsReady=true;
        }

        calibration.addTextChangedListener(new TextWatcher() {
            // the user's changes are saved here
            public void onTextChanged(CharSequence c, int start, int before, int count) {
                if (!c.toString().isEmpty()){
                    try {
                        calibrationValue = Double.parseDouble(c.toString());
                    } catch(NumberFormatException nfe) {
                        //System.out.println("Could not parse " + nfe);
                    }
                }
            }
            public void beforeTextChanged(CharSequence c, int start, int count, int after) {
                // this space intentionally left blank
            }
            public void afterTextChanged(Editable c) {
                // this one too
            }
        });

        //using this to deploy the database https://github.com/jgilfelt/android-sqlite-asset-helper
        db = new dbHelper(this);
        double myLat = 47.177189;
        double myLong = -123.097217;
        Location myLocation = new Location("");
        myLocation.setLatitude(myLat);
        myLocation.setLongitude(myLong);
        nearestLocation = db.getNearbySRMPlocations(myLocation); // you would not typically call this on the main thread
        milePostLocation nearestLocationSrmpInfo = db.getSRMPinfo(nearestLocation);

    }

    public void writeTerminal(String data) {
        messages.append(data);
        // Erase excessive lines to keep memory consumption of TextView low.
        //   this code was found on stackoverflow https://stackoverflow.com/questions/5078058/how-to-delete-the-old-lines-of-a-textview
        int excessLineNumber = messages.getLineCount() - MAX_LINE;
        if (excessLineNumber > 0) {
            int eolIndex = -1;
            CharSequence charSequence = messages.getText();
            for(int i=0; i<excessLineNumber; i++) {
                do {
                    eolIndex++;
                } while(eolIndex < charSequence.length() && charSequence.charAt(eolIndex) != '\n');
            }
            if (eolIndex < charSequence.length()) {
                messages.getEditableText().delete(0, eolIndex+1);
            }
            else {
                messages.setText("");
            }
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                final int bluetoothState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);
                switch (bluetoothState) {
                    case BluetoothAdapter.STATE_ON:
                        adaptorIsReady=true;
                        startLEscan();
                        break;
                    case BluetoothAdapter.STATE_OFF:
                        adaptorIsReady=false;
                        break;
                }
            }
        }
    };

    // Main BTLE device callback where much of the logic occurs.
    private BluetoothGattCallback myGattCallBack = new BluetoothGattCallback() {
        // Called whenever the device connection state changes, i.e. from disconnected to connected.
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                writeLine("Connected!");
                connected = true;
                bleHandler.postDelayed(discoverServicesRunnable,600);
            }
            else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                writeLine("Disconnected from Gatt!");
                disconnectGattServer();
                writeLine("Try again to connect");
                startLEscan();
            }
            else if (newState == BluetoothGatt.GATT_FAILURE) {
                writeLine("Failed to connect to Gatt!");
                disconnectGattServer();
            }
            else if (newState != BluetoothGatt.GATT_SUCCESS) {
                writeLine("Didnt connect to Gatt!");
                disconnectGattServer();
            }
            else {
                writeLine("Connection state changed.  New state: " + newState);
            }
        }
        Runnable discoverServicesRunnable = new Runnable() {
            @Override
            public void run() {
                if (!gatt.discoverServices()) {
                    writeLine("Failed to start discovering services!");
                    disconnectGattServer();
                } else {
                    writeLine("Discover Services returned true");
                }
            }
        };

        // Called when services have been discovered on the remote device.
        // It seems to be necessary to wait for this discovery to occur before
        // manipulating any services or characteristics.
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeLine("Service discovery completed!");
            }
            else {
                writeLine("Service discovery failed with status: " + status);
                return;
            }
            // Save reference to each characteristic.
            tx = gatt.getService(UART_UUID).getCharacteristic(TX_UUID);
            rx = gatt.getService(UART_UUID).getCharacteristic(RX_UUID);
            // Setup notifications on RX characteristic changes (i.e. data received).
            // First call setCharacteristicNotification to enable notification.
            if (!gatt.setCharacteristicNotification(rx, true)) {
                writeLine("Couldn't set notifications for RX characteristic!");
            }
            // Next update the RX characteristic's client descriptor to enable notifications.
            if (rx.getDescriptor(CLIENT_UUID) != null) {
                numConnectionsCreated=numConnectionsCreated+1;
                //messages.setText(numConnectionsCreated);
                statusMsg.post(new Runnable() {
                    public void run() {
                        statusMsg.setText(String.valueOf(numConnectionsCreated));
                    }
                });
                BluetoothGattDescriptor desc = rx.getDescriptor(CLIENT_UUID);
                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (!gatt.writeDescriptor(desc)) {
                    writeLine("Couldn't write RX client descriptor value!");
                }
            }
            else {
                writeLine("Couldn't get RX client descriptor!");
            }
        }

        // Called when a remote characteristic changes (like the RX characteristic).
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            String message = characteristic.getStringValue(0);
            // data comes in as strings in brackets like this {123}
            Matcher m = Pattern.compile("\\{(.*?)\\}").matcher(message);
            while(m.find()) {
                writeLine("Received: " + m.group(1).toString());
                armValue = Long.parseLong(m.group(1))/calibrationValue;
                arm.post(new Runnable() {
                    public void run() {
                        arm.setText(String.format("%.2f", armValue));
                    }
                });
            }
        }
    };

    private class BtleScanCallback extends ScanCallback {
        @Override
        public void onScanResult(int callbackType, ScanResult result){
            writeLine("Found device with UART service " + result.getDevice());
            // stop the scan and clear any existing gatt connections
            stopLEscan();
            // Control flow will now go to the callback functions when BTLE events occur.
            if (!connected) {
                gatt = result.getDevice().connectGatt(getApplicationContext(), false, myGattCallBack);
            }
        }
        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "BLE Scan Failed with code " + errorCode);
            writeLine("BLE Scan Failed with code " + errorCode);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        writeLine("");
        writeLine("Entering OnResume...");
        stopLEscan();
        startLEscan();
    }

    private void startLEscan() {
        if (scanning || connected || !adaptorIsReady || !adapter.isEnabled()) {
            return;
        }
        scanning = true;
        tryConnectAgainHandler.removeCallbacks(tryConnectAgainRunnable);
        // prepare to scan
        writeLine("Scanning for devices...");
        ScanFilter scanFilter = new ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString(UART_UUIDstr))
                .build();
        filters = new ArrayList<>();
        filters.add(scanFilter);
        scanCallback = new BtleScanCallback();
        LEScanner = adapter.getBluetoothLeScanner();
        settings = new ScanSettings.Builder().setScanMode(ScanSettings.CALLBACK_TYPE_FIRST_MATCH).build();
        LEScanner.startScan(filters, settings, scanCallback);
        // try again in 5 seconds if this one fails
        tryConnectAgainHandler.postDelayed(tryConnectAgainRunnable, 5000);
    }

    Runnable tryConnectAgainRunnable = new Runnable() {
        @Override
        public void run() {
            if (!connected) {
                stopLEscan();
                startLEscan();}
        }
    };

    private void stopLEscan(){
        if ( scanning && adapter != null && adapter.isEnabled() && LEScanner != null) {
            writeLine("Stopping scan...");
            LEScanner.stopScan(scanCallback);
            scanCallback = null;
            scanning=false;
            disconnectGattServer();
        }
    }

    private void disconnectGattServer() {
        connected = false;
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            tx = null;
            rx = null;
        }
    }

    protected void onPause() {
        super.onPause();
        stopLEscan();
        SharedPreferences.Editor edit = sharedSettings.edit();
        edit.putFloat("calibrationValue", (float)calibrationValue);
        edit.apply();
    }

    protected void onDestroy(){
        super.onDestroy();
        if (adapter != null && adapter.isEnabled() && !bleOnBeforeCreate){
            adapter.disable();
        }
        //Log.i(TAG, "onDestroy");
        unregisterReceiver(mReceiver);
        // I was calling disconnectGattServer but for some reason didnt work
        //  not not checking for null, seems like checking for null returns true but object really exists?
        //  and needs to be disconnected.  More to do.
        gatt.disconnect();
        gatt.close();
        tx = null;
        rx = null;
        db.close();
    }

    // Handler for mouse click on the send button.
    public void startClick(View view) {
        sendMessage("r");
    }

    // Handler for mouse click on the send button.
    public void stopClick(View view) {
        sendMessage("s");
    }

    // Handler for mouse click on the send button.
    public void clearClick(View view) {
        sendMessage("c");
    }

    private void sendMessage(String message) {
        if (tx == null) {
            return;
        }
        // Update TX characteristic value.  Note the setValue overload that takes a byte array must be used.
        tx.setValue(message.getBytes(Charset.forName("UTF-8")));
        if (gatt.writeCharacteristic(tx)) {
            writeLine("Sent: " + message);
        }
        else {
            writeLine("Couldn't write TX characteristic!");
        }
    }

    // Write some text to the messages text view.
    // Care is taken to do this on the main UI thread so writeLine can be called
    // from any thread (like the BTLE callback).
    private void writeLine(final CharSequence text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                writeTerminal(text + "\n");
                //messages.append(text);
                //messages.append("\n");
            }
        });
    }

//    @Override
//    public void onSaveInstanceState(Bundle savedInstanceState) {
//        super.onSaveInstanceState(savedInstanceState);
//        // Save UI state changes to the savedInstanceState.
//        // This bundle will be passed to onCreate if the process is
//        // killed and restarted.
//        savedInstanceState.putDouble("calibrationValue", calibrationValue);
//        writeLine("Save instance state");
//    }

//    @Override
//    public void onRestoreInstanceState(Bundle savedInstanceState) {
//        super.onRestoreInstanceState(savedInstanceState);
//        // Restore UI state from the savedInstanceState.
//        // This bundle has also been passed to onCreate.
//        calibrationValue = savedInstanceState.getDouble("calibrationValue");
//    }

}

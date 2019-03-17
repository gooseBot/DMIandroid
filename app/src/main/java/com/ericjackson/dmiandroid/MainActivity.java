package com.ericjackson.dmiandroid;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapter.LeScanCallback;
import android.bluetooth.BluetoothDevice;
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
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
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
    private boolean connected;
    private static String TAG = "Eric";
    private Handler tryConnectAgainHandler = new Handler();
    private Handler bleHandler = new Handler();
    private int numConnectionsCreated = 0;

    // Main BTLE device callback where much of the logic occurs.
    private BluetoothGattCallback callback = new BluetoothGattCallback() {
        // Called whenever the device connection state changes, i.e. from disconnected to connected.
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                writeLine("Connected!");
                connected = true;
                bleHandler.postDelayed(bleRunnable,600);
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
            Matcher m = Pattern.compile("\\{(.*?)\\}").matcher(message);
            while(m.find()) {
                writeLine("Received: " + m.group(1).toString());
            }
        }
    };

    private void disconnectGattServer() {
        connected = false;
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            tx = null;
            rx = null;
        }
    }
    private class BtleScanCallback extends ScanCallback {
        @Override
        public void onScanResult(int callbackType, ScanResult result){
            writeLine("Found device with UART service " + result.getDevice());
            // stop the scan and clear any existing gatt connections
            stopLEscan();
            // Control flow will now go to the callback functions when BTLE events occur.
            if (!connected) {
                gatt = result.getDevice().connectGatt(getApplicationContext(), false, callback);
            }
        }
        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "BLE Scan Failed with code " + errorCode);
            writeLine("BLE Scan Failed with code " + errorCode);
        }
    }

    // OnCreate, called once to initialize the activity.
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Grab references to UI elements.
        messages = findViewById(R.id.receivedText);
        input = findViewById(R.id.commandText);
        statusMsg = findViewById(R.id.statusText);

        messages.setMovementMethod(new ScrollingMovementMethod());
        messages.setFocusableInTouchMode(true);
        messages.requestFocus();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_COARSE_LOCATION);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        adapter = BluetoothAdapter.getDefaultAdapter();
    }

    // OnResume, called right before UI is displayed.  Start the BTLE connection.
    @Override
    protected void onResume() {
        super.onResume();
        writeLine("");
        writeLine("Entering OnResume...");
        stopLEscan();
        startLEscan();
    }

    private void startLEscan() {
        if (scanning || connected) {
            return;
        }
        scanning = true;
        tryConnectAgainHandler.removeCallbacks(tryConnectAgainRunnable);
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

        tryConnectAgainHandler.postDelayed(tryConnectAgainRunnable, 5000);
    }

    Runnable tryConnectAgainRunnable = new Runnable() {
        @Override
        public void run() {
            if (!connected) {onResume();}
        }
    };

    Runnable bleRunnable = new Runnable() {
        @Override
        public void run() {
            if (!gatt.discoverServices()) {
                writeLine("Failed to start discovering services!");
                disconnectGattServer();
            } else {
                writeLine("Discover Services returned true");
                //bleHandler.postDelayed(bleRunnable,600);
            }
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

    protected void onPause() {
        super.onPause();
        stopLEscan();
    }

    // Handler for mouse click on the send button.
    public void sendClick(View view) {

        String message = input.getText().toString();
        if (tx == null || message == null || message.isEmpty()) {
            // Do nothing if there is no device or message to send.
            return;
        }
        sendMessage(message);
    }

    private void sendMessage(String message) {
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
                messages.append(text);
                messages.append("\n");
            }
        });
    }

}

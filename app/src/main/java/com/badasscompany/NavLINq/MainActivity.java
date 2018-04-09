package com.badasscompany.NavLINq;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.badasscompany.NavLINq.OTAFirmwareUpdate.OTAFirmwareUpgradeActivity;
import com.badasscompany.NavLINq.OTAFirmwareUpdate.UUIDDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    public final static String TAG = "NavLINq";

    private ImageButton backButton;
    private ImageButton forwardButton;
    private ImageButton faultButton;
    private ImageButton settingsButton;
    private ImageButton connectButton;

    private TextView textView1;
    private TextView textView2;
    private TextView textView3;
    private TextView textView4;
    private TextView textView5;
    private TextView textView6;
    private TextView textView7;
    private TextView textView8;

    private SharedPreferences sharedPrefs;

    static boolean hasSensor = false;
    static boolean itsDark = false;
    private long darkTimer = 0;
    private long lightTimer = 0;

    private static Context mContext;

    private Intent gattServiceIntent;
    public static BluetoothAdapter mBluetoothAdapter;
    private Handler mHandler;
    private BluetoothLeService mBluetoothLeService;
    public static BluetoothGattCharacteristic gattDFUCharacteristic;
    private BluetoothGattCharacteristic mNotifyCharacteristic;
    List<BluetoothGattCharacteristic> gattCharacteristics;
    private String mDeviceAddress;
    private static final int REQUEST_ENABLE_BT = 1;
    // Stops scanning after 10 seconds.
    private static final long SCAN_PERIOD = 10000;

    public final static UUID UUID_MOTORCYCLE_SERVICE =
            UUID.fromString(GattAttributes.MOTORCYCLE_SERVICE);

    private static final int PERMISSION_REQUEST_FINE_LOCATION = 1;
    private static final int PERMISSION_REQUEST_WRITE_STORAGE = 112;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG,"In onCreate");
        // Keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        mContext = this;

        showActionBar();

        textView1 = (TextView) findViewById(R.id.textView1);
        textView2 = (TextView) findViewById(R.id.textView2);
        textView3 = (TextView) findViewById(R.id.textView3);
        textView4 = (TextView) findViewById(R.id.textView4);
        textView5 = (TextView) findViewById(R.id.textView5);
        textView6 = (TextView) findViewById(R.id.textView6);
        textView7 = (TextView) findViewById(R.id.textView7);
        textView8 = (TextView) findViewById(R.id.textView8);

        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        mHandler = new Handler();

        // Sensor Stuff
        SensorManager sensorManager
                = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        Sensor lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

        // Light
        if (lightSensor == null){
            Log.d(TAG,"Light sensor not found");
        }else {
            sensorManager.registerListener(sensorEventListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
            hasSensor = true;
        }

        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.toast_ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
        }

        // Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to
        // BluetoothAdapter through BluetoothManager.
        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();

        // Checks if Bluetooth is supported on the device.
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, R.string.toast_error_bluetooth_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        registerReceiver(mBondingBroadcast,new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
        gattServiceIntent = new Intent(MainActivity.this, BluetoothLeService.class);

        // Check read notification permissions
        if (!Settings.Secure.getString(this.getContentResolver(),"enabled_notification_listeners").contains(getApplicationContext().getPackageName())) {
            final AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(getString(R.string.notification_alert_title));
            builder.setMessage(getString(R.string.notification_alert_body));
            builder.setPositiveButton(android.R.string.ok, null);
            builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @TargetApi(23)
                public void onDismiss(DialogInterface dialog) {
                    getApplicationContext().startActivity(new Intent(
                            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                }
            });
            builder.show();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Check Write permissions
            if (this.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(getString(R.string.write_alert_title));
                builder.setMessage(getString(R.string.write_alert_body));
                builder.setPositiveButton(android.R.string.ok, null);
                builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @TargetApi(23)
                    public void onDismiss(DialogInterface dialog) {
                        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_WRITE_STORAGE);
                    }
                });
                builder.show();
            }
            // Check Location permissions
            if (this.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(getString(R.string.location_alert_title));
                builder.setMessage(getString(R.string.location_alert_body));
                builder.setPositiveButton(android.R.string.ok, null);
                builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @TargetApi(23)
                    public void onDismiss(DialogInterface dialog) {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST_FINE_LOCATION);
                    }
                });
                builder.show();
            }
        }
    }

    private void showActionBar(){
        LayoutInflater inflator = (LayoutInflater) this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View v = inflator.inflate(R.layout.actionbar_nav_main, null);
        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(false);
        actionBar.setDisplayShowHomeEnabled (false);
        actionBar.setDisplayShowCustomEnabled(true);
        actionBar.setDisplayShowTitleEnabled(false);

        actionBar.setCustomView(v);

        backButton = (ImageButton) findViewById(R.id.action_back);
        forwardButton = (ImageButton) findViewById(R.id.action_forward);
        settingsButton = (ImageButton) findViewById(R.id.action_settings);
        faultButton = (ImageButton) findViewById(R.id.action_faults);
        connectButton = (ImageButton) findViewById(R.id.action_connect);

        TextView navbarTitle;
        navbarTitle = (TextView) findViewById(R.id.action_title);
        navbarTitle.setText(R.string.main_title);

        backButton.setOnClickListener(mClickListener);
        forwardButton.setOnClickListener(mClickListener);
        faultButton.setOnClickListener(mClickListener);
        settingsButton.setOnClickListener(mClickListener);
    }

    private View.OnClickListener mClickListener = new View.OnClickListener() {

        @Override
        public void onClick(View v) {
            switch(v.getId()) {
                case R.id.action_back:
                    Intent backIntent = new Intent(MainActivity.this, TaskActivity.class);
                    startActivity(backIntent);
                    break;
                case R.id.action_forward:
                    Intent forwardIntent = new Intent(MainActivity.this, MusicActivity.class);
                    startActivity(forwardIntent);
                    break;
                case R.id.action_faults:
                    Intent faultIntent = new Intent(MainActivity.this, FaultActivity.class);
                    startActivity(faultIntent);
                    break;
                case R.id.action_settings:
                    Intent settingsIntent = new Intent(MainActivity.this, SettingsActivity.class);
                    startActivity(settingsIntent);
                    break;
            }
        }
    };

    // Listens for light sensor events
    private final SensorEventListener sensorEventListener
            = new SensorEventListener(){

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Do something
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (sharedPrefs.getBoolean("prefAutoNightMode", false) && (!sharedPrefs.getBoolean("prefNightMode", false))) {
                int delay = (Integer.parseInt(sharedPrefs.getString("prefAutoNightModeDelay", "30")) * 1000);
                if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
                    float currentReading = event.values[0];
                    double darkThreshold = 20.0;  // Light level to determine darkness
                    if (currentReading < darkThreshold) {
                        lightTimer = 0;
                        if (darkTimer == 0) {
                            darkTimer = System.currentTimeMillis();
                        } else {
                            long currentTime = System.currentTimeMillis();
                            long duration = (currentTime - darkTimer);
                            if ((duration >= delay) && (!itsDark)) {
                                itsDark = true;
                                Log.d(TAG, "Its dark");
                                // Update colors
                                updateColors();
                            }
                        }
                    } else {
                        darkTimer = 0;
                        if (lightTimer == 0) {
                            lightTimer = System.currentTimeMillis();
                        } else {
                            long currentTime = System.currentTimeMillis();
                            long duration = (currentTime - lightTimer);
                            if ((duration >= delay) && (itsDark)) {
                                itsDark = false;
                                Log.d(TAG, "Its light");
                                // Update colors
                                updateColors();
                            }
                        }
                    }
                }
            }
        }
    };

    public void updateColors(){
        //TODO: change colors for night mode
    }

    @Override
    protected void onResume() {
        Log.d(TAG,"In onResume");
        super.onResume();

        registerReceiver(mBondingBroadcast,new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBluetoothLeService != null) {
            //final boolean result =
            mBluetoothLeService.connect(mDeviceAddress,"NavLINq",this);
            //Log.d(TAG, "Connect request result=" + result);
        } else {
            setupBLE();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unbindService(mServiceConnection);
        } catch (IllegalArgumentException e){

        }
        mBluetoothLeService = null;
    }

    @Override
    protected void onPause() {
        Log.d(TAG,"In onPause");
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
        unregisterReceiver(mBondingBroadcast);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // User chose not to enable Bluetooth.
        if (requestCode == REQUEST_ENABLE_BT && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(mDeviceAddress,"NavLINq",MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };

    private void setupBLE() {
        Log.d(TAG,"In setupBLE()");
        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        if (!pairedDevices.isEmpty()) {
            for (BluetoothDevice devices : pairedDevices) {
                if (devices.getName().equals("NavLINq")){
                    Log.d(TAG,"NavLINq previously paired");
                    mDeviceAddress = devices.getAddress();
                    Log.d(TAG,"Address: " + mDeviceAddress);
                    bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
                    scanLeDevice(false);
                    return;
                }
            }
        }
        Log.d(TAG, "Previously Paired NavLINq not found");
        scanLeDevice(true);
    }

    private void scanLeDevice(final boolean enable) {
        final BluetoothLeScanner bluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (enable) {
            Log.d(TAG,"In scanLeDevice() Scanning On");
            // Stops scanning after a pre-defined scan period.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    bluetoothLeScanner.stopScan(mLeScanCallback);
                }
            }, SCAN_PERIOD);

            //scan specified devices only with ScanFilter
            ScanFilter scanFilter =
                    new ScanFilter.Builder()
                            .setDeviceName("NavLINq")
                            .build();
            List<ScanFilter> scanFilters = new ArrayList<>();
            scanFilters.add(scanFilter);

            ScanSettings scanSettings =
                    new ScanSettings.Builder().build();

            bluetoothLeScanner.startScan(scanFilters, scanSettings, mLeScanCallback);

        } else {
            Log.d(TAG,"In scanLeDevice() Scanning Off");
            bluetoothLeScanner.stopScan(mLeScanCallback);
        }
    }

    // Device scan callback.
    private ScanCallback mLeScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            String device = result.getDevice().getName();
            if (device != null) {
                if (device.contains("NavLINq")) {
                    Log.d(TAG, "NavLINq Device Found: " + device);
                    result.getDevice().createBond();
                    scanLeDevice(false);
                }
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
        }
    };

    BroadcastReceiver mBondingBroadcast = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);

            switch (state) {
                case BluetoothDevice.BOND_BONDING:
                    Log.d("Bondind Status:", " Bonding...");
                    break;

                case BluetoothDevice.BOND_BONDED:
                    Log.d("Bondind Status:", "Bonded!!");
                    setupBLE();
                    break;

                case BluetoothDevice.BOND_NONE:
                    Log.d("Bondind Status:", "Fail");

                    break;
            }
        }
    };

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                Log.d(TAG,"GATT_CONNECTED");
                mBluetoothLeService.discoverServices();
                //checkGattServices(mBluetoothLeService.getSupportedGattServices());
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                Log.d(TAG,"GATT_DISCONNECTED");
                connectButton.setImageResource(R.drawable.ic_bluetooth_off);
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                Log.d(TAG,"GATT_SERVICE_DISCOVERED");
                checkGattServices(mBluetoothLeService.getSupportedGattServices());
                connectButton.setImageResource(R.drawable.ic_bluetooth_on);
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                Log.d(TAG,"GATT_DATA_AVAILABLE");
                updateDisplay();
            }
        }
    };

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }

    private void checkGattServices(List<BluetoothGattService> gattServices) {
        Log.d(TAG,"In checkGattServices");
        if (gattServices == null) return;
        String uuid;
        // Loops through available GATT Services.
        for (BluetoothGattService gattService : gattServices) {
            Log.d(TAG,"In checkGattServices: for loop");
            if (UUID_MOTORCYCLE_SERVICE.equals(gattService.getUuid())){
                uuid = gattService.getUuid().toString();
                Log.d(TAG,"Motorcycle Service Found: " + uuid);
                gattCharacteristics = gattService.getCharacteristics();
                // Loops through available Characteristics.
                for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                    uuid = gattCharacteristic.getUuid().toString();
                    Log.d(TAG,"Characteristic Found: " + uuid);
                    if (UUID.fromString(GattAttributes.LIN_MESSAGE_CHARACTERISTIC).equals(gattCharacteristic.getUuid())) {
                        int charaProp = gattCharacteristic.getProperties();
                        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
                            // If there is an active notification on a characteristic, clear
                            // it first so it doesn't update the data field on the user interface.
                            if (mNotifyCharacteristic != null) {
                                mBluetoothLeService.setCharacteristicNotification(
                                        mNotifyCharacteristic, false);
                                mNotifyCharacteristic = null;
                            }
                            mBluetoothLeService.readCharacteristic(gattCharacteristic);
                        }
                        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                            mNotifyCharacteristic = gattCharacteristic;
                            mBluetoothLeService.setCharacteristicNotification(
                                    gattCharacteristic, true);
                        }
                    } else if (UUID.fromString(GattAttributes.DFU_CHARACTERISTIC).equals(gattCharacteristic.getUuid())){
                        //TODO
                        gattDFUCharacteristic = gattCharacteristic;
                    }
                }
            } else if (UUIDDatabase.UUID_OTA_UPDATE_SERVICE.equals(gattService.getUuid())){
                Log.d(TAG,"OTA Service Found");
                //mBluetoothLeService.disconnect();
                Intent oTAIntent = new Intent(MainActivity.this, OTAFirmwareUpgradeActivity.class);
                oTAIntent.putExtra("device", mDeviceAddress);
                startActivity(oTAIntent);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode)
        {
            case PERMISSION_REQUEST_WRITE_STORAGE: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                {
                    Log.d(TAG, "Write to storage permission granted");
                    setupBLE();
                } else
                {
                    final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle("Functionality limited");
                    builder.setMessage("Since write access has not been granted, this app will not be able to log data");
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                        }
                    });
                    builder.show();
                }
            }
            case PERMISSION_REQUEST_FINE_LOCATION: {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "coarse location permission granted");
                } else {
                    final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle("Functionality limited");
                    builder.setMessage("Since location access has not been granted, this app will not be able to discover your NavLINq when in the background.");
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                        }
                    });
                    builder.show();
                }
            }
        }

    }

    // Update Display
    private void updateDisplay(){
        String pressureUnit = "bar";
        String pressureFormat = sharedPrefs.getString("prefPressureF", "0");
        if (pressureFormat.contains("1")) {
            // KPa
            pressureUnit = "KPa";
        } else if (pressureFormat.contains("2")) {
            // Kg-f
            pressureUnit = "Kg-f";
        } else if (pressureFormat.contains("3")) {
            // Psi
            pressureUnit = "psi";
        }
        String temperatureUnit = "C";
        String temperatureFormat = sharedPrefs.getString("preftempf", "0");
        if (temperatureFormat.contains("0")) {
            // F
            temperatureUnit = "F";
        }
        String distanceUnit = "km";
        String distanceFormat = sharedPrefs.getString("prefdistance", "0");
        if (distanceFormat.contains("0")) {
            distanceUnit = "mi";
        }
        if(Data.getFrontTirePressure() != null){
            Double rdcFront = Data.getFrontTirePressure();
            Double rdcRear = Data.getRearTirePressure();
            if (pressureFormat.contains("1")) {
                // KPa
                rdcFront = barTokPa(rdcFront);
                rdcRear = barTokPa(rdcRear);
            } else if (pressureFormat.contains("2")) {
                // Kg-f
                rdcFront = barTokgf(rdcFront);
                rdcRear = barTokgf(rdcRear);
            } else if (pressureFormat.contains("3")) {
                // Psi
                Log.d(TAG,"Bar Selected: " + rdcFront +", " + rdcRear);
                rdcFront = barToPsi(rdcFront);
                rdcRear = barToPsi(rdcRear);
                Log.d(TAG,"PSI Selected: " + rdcFront +", " + rdcRear);
            }
            textView1.setText((int) Math.round(rdcFront) + " " + pressureUnit);
            textView5.setText((int) Math.round(rdcRear) + " " + pressureUnit);
        }
        if(Data.getGear() != null){
            textView3.setText(Data.getGear());
        }
        if(Data.getEngineTemperature() != null ){
            Double engineTemp = Data.getEngineTemperature();
            if (temperatureFormat.contains("0")) {
                // F
                engineTemp = celsiusToFahrenheit(engineTemp);
            }
            textView2.setText((int) Math.round(engineTemp) + " " + temperatureUnit);
        }
        if(Data.getAmbientTemperature() != null ){
            Double ambientTemp = Data.getAmbientTemperature();
            if (temperatureFormat.contains("0")) {
                // F
                ambientTemp = celsiusToFahrenheit(ambientTemp);
            }
            textView6.setText((int) Math.round(ambientTemp) + " " + temperatureUnit);
        }
        if(Data.getOdometer() != null){
            Double odometer = Data.getOdometer();
            if (distanceFormat.contains("0")) {
                odometer = kmToMiles(odometer);
            }
            textView7.setText(Math.round(odometer) + " " + distanceUnit);
        }
        if(Data.getTripOne() != null) {
            Double trip1 = Data.getTripOne();
            Double trip2 = Data.getTripTwo();
            if (distanceFormat.contains("0")) {
                trip1 = kmToMiles(trip1);
                trip2 = kmToMiles(trip2);
            }
            textView4.setText(Math.round(trip1) + " " + distanceUnit);
            textView8.setText(Math.round(trip2) + " " + distanceUnit);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        Log.d(TAG, "Keycode: " + keyCode);
        switch (keyCode) {
            case KeyEvent.KEYCODE_ESCAPE:
                Intent backIntent = new Intent(MainActivity.this, TaskActivity.class);
                startActivity(backIntent);
                return true;
            case KeyEvent.KEYCODE_ENTER:
                Intent forwardIntent = new Intent(MainActivity.this, MusicActivity.class);
                startActivity(forwardIntent);
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    public static Context getContext(){
        return mContext;
    }

    // Unit Conversion Functions
    // bar to psi
    public double barToPsi(double bar){
        return bar * 14.5037738;
    }
    // bar to kpa
    public double barTokPa(double bar){
        return bar * 100;
    }
    // bar to kg-f
    public double barTokgf(double bar){
        return bar * 1.0197162129779;
    }
    // kilometers to miles
    public double kmToMiles(double kilometers){
        return kilometers * 0.6214;
    }
    // Celsius to Fahrenheit
    public double celsiusToFahrenheit(double celsius){
        return (celsius * 1.8) + 32.0;
    }

}
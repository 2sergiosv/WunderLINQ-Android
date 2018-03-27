package com.badasscompany.NavLINq;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import java.util.List;
import java.util.UUID;

import static android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH;

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
public class BluetoothLeService extends Service {
    private final static String TAG = BluetoothLeService.class.getSimpleName();

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private Logger logger = null;
    final FaultStatus faults = (new FaultStatus(this));

    public final static String ACTION_GATT_CONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE =
            "com.example.bluetooth.le.ACTION_DATA_AVAILABLE";
    public final static String EXTRA_DATA =
            "com.example.bluetooth.le.EXTRA_DATA";

    public final static UUID UUID_LIN_MESSAGE =
            UUID.fromString(GattAttributes.LIN_MESSAGE_CHARACTERISTIC);

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                intentAction = ACTION_GATT_CONNECTED;
                broadcastUpdate(intentAction);
                Log.i(TAG, "Connected to GATT server.");
                // Attempts to discover services after successful connection.
                Log.i(TAG, "Attempting to start service discovery:" +
                        mBluetoothGatt.discoverServices());

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server.");
                broadcastUpdate(intentAction);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
        }
    };

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }

    private void broadcastUpdate(final String action,
                                 final BluetoothGattCharacteristic characteristic) {
        final Intent intent = new Intent(action);

        if (UUID_LIN_MESSAGE.equals(characteristic.getUuid())) {
            // For all other profiles, writes the data formatted in HEX.
            final byte[] data = characteristic.getValue();
            if (data != null) {
                intent.putExtra(EXTRA_DATA, data);
                final StringBuilder stringBuilder = new StringBuilder(data.length);
                for (byte byteChar : data)
                    stringBuilder.append(String.format("%02x", byteChar));

                SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
                if (sharedPrefs.getBoolean("prefDataLogging", false)) {
                    // Log data
                    if (logger == null) {
                        logger = new Logger();
                    }
                    logger.write("raw", stringBuilder.toString());
                }
                Log.d(TAG, "serviceData: " + stringBuilder.toString());

                byte msgID = data[0];
                switch (msgID) {
                    case 0x00:
                        Log.d(TAG, "Message ID 0");
                        break;
                    case 0x01:
                        Log.d(TAG, "Message ID 1");
                        break;
                    case 0x02:
                        Log.d(TAG, "Message ID 2");
                        break;
                    case 0x03:
                        Log.d(TAG, "Message ID 3");
                        break;
                    case 0x04:
                        Log.d(TAG, "Message ID 4");
                        break;
                    case 0x05:
                        Log.d(TAG, "Message ID 5");
                        // ABS Fault
                        // FC=ABS Fault
                        if ((data[3] & 0xFF) == 0xFB){
                            faults.setabsFaultActive(true);
                        } else {
                            faults.setabsFaultActive(false);
                        }
                        // Tire Pressure
                        if ((data[4] & 0xFF) != 0xFF){
                            double rdcFront = (data[4] & 0xFF) / 50;
                            Data.setFrontTirePressure(rdcFront);
                        }
                        if ((data[5] & 0xFF) != 0xFF){
                            double rdcRear = (data[5] & 0xFF) / 50;
                            Data.setRearTirePressure(rdcRear);
                        }

                        // Tire Pressure Faults
                        // C0=Resting, C9=Front Warning, D1=Front Critical, CA=Rear Warning, D2=Rear Critical, D3=Front/Rear Critical
                        if ((data[6] & 0xFF) == 0xC9 || (data[6] & 0xFF) == 0xD1){
                            faults.setfrontTirePressureActive(true);
                            faults.setrearTirePressureActive(false);
                        } else if ((data[6] & 0xFF) == 0xCA || (data[6] & 0xFF) == 0xD2){
                            faults.setfrontTirePressureActive(false);
                            faults.setrearTirePressureActive(true);
                        } else if ((data[6] & 0xFF) == 0xD3){
                            faults.setfrontTirePressureActive(true);
                            faults.setrearTirePressureActive(true);
                        } else if ((data[6] & 0xFF) == 0xC0){
                            faults.setfrontTirePressureActive(false);
                            faults.setrearTirePressureActive(false);
                        } else {

                        }

                        break;
                    case 0x06:
                        Log.d(TAG, "Message ID 6");
                        String gear;
                        switch (data[2] & 0xFF) {
                            case 0x10:
                                gear = "1";
                                break;
                            case 0x20:
                                gear = "N";
                                break;
                            case 0x40:
                                gear = "2";
                                break;
                            case 0x70:
                                gear = "3";
                                break;
                            case 0x80:
                                gear = "4";
                                break;
                            case 0xB0:
                                gear = "5";
                                break;
                            case 0xD0:
                                gear = "6";
                                break;
                            case 0xF0:
                                // Inbetween Gears
                                gear = "-";
                                break;
                            default:
                                gear = "--";
                                Log.d(TAG, "Unknown gear value");
                        }
                        Data.setGear(gear);

                        double engineTemp = ((data[4] & 0xFF) * 0.75) - 25;
                        Data.setEngineTemperature(engineTemp);
                        break;
                    case 0x07:
                        Log.d(TAG, "Message ID 7");
                        break;
                    case 0x08:
                        Log.d(TAG, "Message ID 8");
                        double ambientTemp = ((data[1] & 0xFF) * 0.50) - 40;
                        Data.setAmbientTemperature(ambientTemp);

                        // Front Signal Lamp Faults
                        // 00=Resting, 20=Left, 40=Right, 60=Both
                        if ((data[4] & 0xFF) == 0x20){
                            faults.setfrontLeftSignalActive(true);
                            faults.setfrontRightSignalActive(false);
                        } else if ((data[4] & 0xFF) == 0x40){
                            faults.setfrontLeftSignalActive(false);
                            faults.setfrontRightSignalActive(true);
                        } else if ((data[4] & 0xFF) == 0x60){
                            faults.setfrontLeftSignalActive(true);
                            faults.setfrontRightSignalActive(true);
                        } else {
                            faults.setfrontLeftSignalActive(false);
                            faults.setfrontRightSignalActive(false);
                        }
                        // Rear Signal Lamp Faults
                        // C0=Resting, C8=Left, D0=Right, D8=Both
                        if ((data[5] & 0xFF) == 0xC8){
                            faults.setrearLeftSignalActive(true);
                            faults.setrearRightSignalActive(false);
                        } else if ((data[5] & 0xFF) == 0xD0){
                            faults.setrearLeftSignalActive(false);
                            faults.setrearRightSignalActive(true);
                        } else if ((data[5] & 0xFF) == 0xD8){
                            faults.setrearLeftSignalActive(true);
                            faults.setrearRightSignalActive(true);
                        } else {
                            faults.setrearLeftSignalActive(false);
                            faults.setrearRightSignalActive(false);
                        }
                        break;
                    case 0x09:
                        Log.d(TAG, "Message ID 9");
                        break;
                    case 0x0a:
                        Log.d(TAG, "Message ID 10");
                        double odometer = bytesToInt(data[3],data[2],data[1]);
                        Data.setOdometer(odometer);
                        break;
                    case 0x0b:
                        Log.d(TAG, "Message ID 11");
                        break;
                    case 0x0c:
                        Log.d(TAG, "Message ID 12");
                        double trip1 = bytesToInt(data[3],data[2],data[1]) / 10;
                        double trip2 = bytesToInt(data[6],data[5],data[4]) / 10;
                        Data.setTripOne(trip1);
                        Data.setTripTwo(trip2);
                        break;
                    default:
                        Log.d(TAG, "Unknown Message ID: " + String.format("%02x", msgID));
                }
            }
        }
        sendBroadcast(intent);
    }

    public class LocalBinder extends Binder {
        BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        close();
        return super.onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initialize() {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     *         is reported asynchronously through the
     *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     *         callback.
     */
    public boolean connect(final String address) {
        Log.d(TAG, "in connect");
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress)
                && mBluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            mBluetoothGatt.requestConnectionPriority(CONNECTION_PRIORITY_HIGH);
            return mBluetoothGatt.connect();
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            return false;
        }
        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        Log.d(TAG, "Trying to create a new connection.");
        mBluetoothDeviceAddress = address;
        return true;
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.disconnect();
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        if (logger != null){
            logger.shutdown();
        }

        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification.  False otherwise.
     */
    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
                                              boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);

        if (UUID_LIN_MESSAGE.equals(characteristic.getUuid())) {
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                    UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            mBluetoothGatt.writeDescriptor(descriptor);
        }

    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices() {
        if (mBluetoothGatt == null) return null;

        return mBluetoothGatt.getServices();
    }

    private int bytesToInt(byte a, byte b, byte c) {
        int ia = (a & 0xFF);
        ia <<= 16;
        int ib = (b & 0xFF);
        ib <<= 8;
        int ic = (c & 0xFF);
        int ret = (short)(ia | ib | ic);
        return ret;
    }
}

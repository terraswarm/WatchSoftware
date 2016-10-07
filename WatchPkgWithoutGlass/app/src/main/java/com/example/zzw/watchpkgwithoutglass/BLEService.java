package com.example.zzw.watchpkgwithoutglass;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.UUID;

public class BLEService extends Service implements SensorEventListener{

    /**
     * variables related to the ble or glasses connection setup
     */
    private static final String TAG = "BLE Service" ;

    /**
     * variables related to the watch IMU setting
     */
    private static final int STATUS_ACC = 1;
    private static final int STATUS_GYR = 2;
    private static final int STATUS_NONE = 0;
    private SensorManager mSensorManager;
    private Sensor accSensor;
    private Sensor gyroSensor;
    private int OFFSET_ACC = 1000;
    private int OFFSET_GYR = 10000;
    private int sensorStatus = STATUS_NONE;
    private byte[] lastData = null;
    private int DELAY_20HZ = 50000;

    private MessageSender sender; // socket data sender object

    /**
     * variables related to the package sending frequency and number of samples in one package
     */
    private static final int DATA_NUM_LIMIT_SENSOR = 24;
    private static final int DATA_NUM_LIMIT_GLASS = 20;
    private static final int DATA_NUM_LIMIT_ENVIRONMENT = 60;
    private static final int DATA_UNIT_SENSOR = 24;
    private static final int DATA_UNIT_GLASS = 20;
    private static final int DATA_UNIT_ENVIRONMENT = 60;

    /**
     * sample' s size of each package
     */
    private static final int BYTE_NUM_DEV_ID_AND_TYPE = 4 + 1;
    private static final int BYTE_NUM_WATCH_SAMPLE = 2 * 6 + 3 + 1 + 6;
    private static final int BYTE_NUM_GLASS_SAMPLE = 2 * 3 + 6;
    private static final int BYTE_NUM_BATTERY_LIFE  = 1 + 6;
    private static final int BYTE_NUM_ENVIRONMENT_SAMPLE = 2 * 5 + 6;

    /**
     * indicators of samples number
     */
    private int sendSensorDataNum = 0;
    private int sendGlassDataNum = 0;
    private int sendEnvironmentDataNum = 0;

    /**
     * buffer of the package
     */
    private ArrayList<byte[]> sensorDataBuffer;
    private ArrayList<byte[]> glassDataBuffer;
    private ArrayList<byte[]> environmentDataBuffer;

    /**
     * variables related to package members
     */
    private static final String DEV_ID = Build.SERIAL.substring(6);
    private static final String TYPE_WATCH = "w";
    private static final String TYPE_GLASS = "g";
    private static final String TYPE_ENVIRONMENT = "e";
    private static final String TYPE_BATTERY = "b";
    private int ppg = 100000;
    private int hr = 70;
    private int lastBattery = -1;

    private boolean shouldStop = false;
    /**
     * waiting for the stop broadcast, when received start to disconnect the glasses and stop the service
     */
    private final BroadcastReceiver stopInfoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            String action = intent.getAction();
            if(action.equals(MainActivity.STOP_ACTION)){
                shouldStop = true;
                stopSelf();
            }
        }
    };

    /**
     * waiting for the battery life change broadcast, every 3% changed send a notification to the server
     */
    private BroadcastReceiver mBatInfoReceiver = new BroadcastReceiver(){
        @Override
        public void onReceive(Context ctxt, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            if (lastBattery == -1) {
                lastBattery = level;
                return;
            } else {
                if (level - lastBattery >= 3) {
                    Log.d(TAG, "battery " + String.valueOf(level));
                    ByteBuffer bb = ByteBuffer.allocate(BYTE_NUM_DEV_ID_AND_TYPE + BYTE_NUM_BATTERY_LIFE);
                    bb.put(DEV_ID.getBytes());
                    bb.put(TYPE_BATTERY.getBytes());
                    bb.put(((byte)(level & 0xff)));
                    bb.put(getTimeStampByteArray());
                    sender.send(bb.array());
                }
            }
        }
    };

    public BLEService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onCreate() {
        Log.v(TAG, "ServiceDemo onCreate");
        super.onCreate();
        startForeground(0, null); // make the server not able to be killed
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.v(TAG, "ServiceDemo onStartCommand");
        initialize();

        // register the two broadcast receivers
        IntentFilter filter = new IntentFilter();
        filter.addAction(MainActivity.STOP_ACTION);
        registerReceiver(stopInfoReceiver, filter);
        registerReceiver(mBatInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE); // get SensorManager
        accSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER); // get Accelerometer
        gyroSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE); // get Gyroscope
        mSensorManager.registerListener(this, accSensor, DELAY_20HZ); // 20hz
        mSensorManager.registerListener(this, gyroSensor, DELAY_20HZ); //20hz

        new Thread(new Runnable() { // create a thread to make fake glass acc data package and glass environment data package
            @Override
            public void run() {

                // fixed glass acc data
                ByteBuffer glassData = ByteBuffer.allocate(6);
                glassData.put((byte) 0x20);
                glassData.put((byte) 0x10);
                glassData.put((byte) 0xb3);
                glassData.put((byte) 0xfe);
                glassData.put((byte) 0x91);
                glassData.put((byte) 0xff);

                // fixed glass environment data
                ByteBuffer environmentData = ByteBuffer.allocate(10);
                environmentData.put((byte) 0x52);
                environmentData.put((byte) 0xe0);
                environmentData.put((byte) 0x24);
                environmentData.put((byte) 0x13);
                environmentData.put((byte) 0xf9);
                environmentData.put((byte) 0x0c);
                environmentData.put((byte) 0x00);
                environmentData.put((byte) 0x00);
                environmentData.put((byte) 0x00);
                environmentData.put((byte) 0x00);

                int ien = 0;
                while (!shouldStop) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    ien++;

                    // the frequency of the data sample is 20hz, the frequency of the sending package is 1hz
                    if (sendGlassDataNum == 0) {
                        glassDataBuffer = new ArrayList<>();
                        ByteBuffer tmpbb = ByteBuffer.allocate(BYTE_NUM_GLASS_SAMPLE);
                        tmpbb.put(glassData.array());
                        byte[] ar = getTimeStampByteArray();
                        tmpbb.put(ar);
                        glassDataBuffer.add(tmpbb.array());
                        sendGlassDataNum++;
                    } else if (sendGlassDataNum < DATA_NUM_LIMIT_GLASS) {
                        ByteBuffer tmpbb = ByteBuffer.allocate(BYTE_NUM_GLASS_SAMPLE);
                        tmpbb.put(glassData.array());
                        tmpbb.put(getTimeStampByteArray());
                        glassDataBuffer.add(tmpbb.array());
                        sendGlassDataNum++;
                    }
                    if (sendGlassDataNum == DATA_NUM_LIMIT_GLASS) {
                        for (int j = 0; j < (DATA_NUM_LIMIT_GLASS / DATA_UNIT_GLASS); j++) {
                            ByteBuffer sendData = ByteBuffer.allocate(DATA_NUM_LIMIT_GLASS * BYTE_NUM_GLASS_SAMPLE + BYTE_NUM_DEV_ID_AND_TYPE);
                            sendData.put(DEV_ID.getBytes());
                            sendData.put(TYPE_GLASS.getBytes());
                            for (int i = (j * DATA_UNIT_GLASS); i < (j + 1) * DATA_UNIT_GLASS; i++) {
                                sendData.put(glassDataBuffer.get(i));
                            }
                            Log.d("send message", "glass!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                            sender.send(sendData.array());
                        }
                        sendGlassDataNum = 0;
                    }

                    if (ien % 20 == 0) {
                        ien = 0;

                        // the frequency of the sample is 1 hz, the frequency of the sending data is 1 package every min
                        if (sendEnvironmentDataNum == 0) {
                            environmentDataBuffer = new ArrayList<>();
                            ByteBuffer tmpbb = ByteBuffer.allocate(BYTE_NUM_ENVIRONMENT_SAMPLE);
                            tmpbb.put(environmentData.array());
                            tmpbb.put(getTimeStampByteArray());
                            environmentDataBuffer.add(tmpbb.array());
                            sendEnvironmentDataNum++;
                        } else if (sendEnvironmentDataNum < DATA_NUM_LIMIT_ENVIRONMENT) {
                            ByteBuffer tmpbb = ByteBuffer.allocate(BYTE_NUM_ENVIRONMENT_SAMPLE);
                            tmpbb.put(environmentData.array());
                            tmpbb.put(getTimeStampByteArray());
                            environmentDataBuffer.add(tmpbb.array());
                            sendEnvironmentDataNum++;
                        }
                        if (sendEnvironmentDataNum == DATA_NUM_LIMIT_ENVIRONMENT) {
                            for (int j = 0; j < (DATA_NUM_LIMIT_ENVIRONMENT / DATA_UNIT_ENVIRONMENT); j++) {
                                ByteBuffer sendData = ByteBuffer.allocate(DATA_NUM_LIMIT_ENVIRONMENT * BYTE_NUM_ENVIRONMENT_SAMPLE + BYTE_NUM_DEV_ID_AND_TYPE);
                                sendData.put(DEV_ID.getBytes());
                                sendData.put(TYPE_ENVIRONMENT.getBytes());
                                for (int i = (j * DATA_UNIT_ENVIRONMENT); i < (j + 1) * DATA_UNIT_ENVIRONMENT; i++) {
                                    sendData.put(environmentDataBuffer.get(i));
                                }
                                Log.d("send message", "environment!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                                sender.send(sendData.array());
                            }
                            sendEnvironmentDataNum = 0;
                        }
                    }
                }
            }
        }).start();

        return super.onStartCommand(intent, flags, startId);
    }

    public boolean initialize() {
        // get the instance of the socket sender
        sender = MessageSender.getInstance();

        return true;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(stopInfoReceiver);
        unregisterReceiver(mBatInfoReceiver);
        mSensorManager.unregisterListener(this);
        stopForeground(true);
    }

    /**
     * when the sensor status changed, this function will be called
     * @param sensorEvent
     */
    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        int sensorType = sensorEvent.sensor.getType();

        //check the current data type, when acc and gyro sensor data are got, then add them to one sample
        // and push into the buffer, when one package is formed, then send the package to server
        if (sensorType == Sensor.TYPE_ACCELEROMETER) {
            if (sensorStatus == STATUS_NONE) { // only get the acc data, wait for the gyro data
                lastData = float2ByteArray(sensorEvent.values, OFFSET_ACC);
                sensorStatus = STATUS_ACC;
            } else if(sensorStatus == STATUS_ACC) { // override the last acc data
                lastData = float2ByteArray(sensorEvent.values, OFFSET_ACC);
            } else if(sensorStatus == STATUS_GYR) { // two types of data are both got, make a sample, put it to the buffer
                sensorStatus = STATUS_NONE;
                if (sendSensorDataNum == 0) {
                    sensorDataBuffer = new ArrayList<>();
                    ByteBuffer bb = ByteBuffer.allocate(BYTE_NUM_WATCH_SAMPLE);
                    bb.put(float2ByteArray(sensorEvent.values, OFFSET_GYR));
                    bb.put(lastData);
                    bb.put(int23ByteArray(ppg));
                    bb.put(((byte)(hr & 0xff)));
                    bb.put(getTimeStampByteArray());
                    sensorDataBuffer.add(bb.array());
                    sendSensorDataNum++;
                } else if (sendSensorDataNum < DATA_NUM_LIMIT_SENSOR) {
                    ByteBuffer bb = ByteBuffer.allocate(BYTE_NUM_WATCH_SAMPLE);
                    bb.put(float2ByteArray(sensorEvent.values, OFFSET_GYR));
                    bb.put(lastData);
                    bb.put(int23ByteArray(ppg));
                    bb.put(((byte)(hr & 0xff)));
                    bb.put(getTimeStampByteArray());
                    sensorDataBuffer.add(bb.array());
                    sendSensorDataNum++;
                }
                if (sendSensorDataNum == DATA_NUM_LIMIT_SENSOR) { // if the number of samples are enough, form the package and send
                    for (int j = 0; j < (DATA_NUM_LIMIT_SENSOR / DATA_UNIT_SENSOR); j++) {
                        ByteBuffer sendData = ByteBuffer.allocate(DATA_UNIT_SENSOR * BYTE_NUM_WATCH_SAMPLE + BYTE_NUM_DEV_ID_AND_TYPE);
                        sendData.put(DEV_ID.getBytes());
                        sendData.put(TYPE_WATCH.getBytes());
                        for (int i = (j * DATA_UNIT_SENSOR); i < ((j + 1) * DATA_UNIT_SENSOR); i++) {
                            sendData.put(sensorDataBuffer.get(i));
                        }

                        Log.d("send message", "sensor!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                        sender.send(sendData.array());
                    }
                    sendSensorDataNum = 0;
                }
            }
        } else if (sensorType == Sensor.TYPE_GYROSCOPE) { // the opposed operation to the above
            if (sensorStatus == STATUS_NONE) {
                lastData = float2ByteArray(sensorEvent.values, OFFSET_GYR);
                sensorStatus = STATUS_GYR;
            } else if(sensorStatus == STATUS_GYR) {
                lastData = float2ByteArray(sensorEvent.values, OFFSET_GYR);

            } else if(sensorStatus == STATUS_ACC) {
                sensorStatus = STATUS_NONE;
                if (sendSensorDataNum == 0) {
                    sensorDataBuffer = new ArrayList<>();
                    ByteBuffer bb = ByteBuffer.allocate(BYTE_NUM_WATCH_SAMPLE);
                    bb.put(lastData);
                    bb.put(float2ByteArray(sensorEvent.values, OFFSET_ACC));
                    bb.put(int23ByteArray(ppg));
                    bb.put(((byte)(hr & 0xff)));
                    bb.put(getTimeStampByteArray());
                    sensorDataBuffer.add(bb.array());
                    sendSensorDataNum++;
                } else if (sendSensorDataNum < DATA_NUM_LIMIT_SENSOR) {
                    ByteBuffer bb = ByteBuffer.allocate(BYTE_NUM_WATCH_SAMPLE);
                    bb.put(lastData);
                    bb.put(float2ByteArray(sensorEvent.values, OFFSET_ACC));
                    bb.put(int23ByteArray(ppg));
                    bb.put(((byte)(hr & 0xff)));
                    bb.put(getTimeStampByteArray());
                    sensorDataBuffer.add(bb.array());
                    sendSensorDataNum++;
                }
                if (sendSensorDataNum == DATA_NUM_LIMIT_SENSOR) {

                    for (int j = 0; j < (DATA_NUM_LIMIT_SENSOR / DATA_UNIT_SENSOR); j++) {
                        ByteBuffer sendData = ByteBuffer.allocate(DATA_UNIT_SENSOR * BYTE_NUM_WATCH_SAMPLE + BYTE_NUM_DEV_ID_AND_TYPE);
                        sendData.put(DEV_ID.getBytes());
                        sendData.put(TYPE_WATCH.getBytes());
                        for (int i = (j * DATA_UNIT_SENSOR); i < ((j + 1) * DATA_UNIT_SENSOR); i++) {
                            sendData.put(sensorDataBuffer.get(i));
                        }

                        Log.d("send message", "sensor!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                        sender.send(sendData.array());
                    }
                    sendSensorDataNum = 0;
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    /**
     * convert int value to 3 bytes array
     * @param value
     * @return 3 bytes array
     */
    private byte [] int23ByteArray (int value) {
        ByteBuffer bb = ByteBuffer.allocate(3);
        bb.put(((byte)(value & 0xff)));
        bb.put(((byte)((value >> 8) & 0xff)));
        bb.put(((byte)((value >> 16) & 0xff)));
        return bb.array();
    }

    /**
     * convert float array to 6 bytes array, here is for watches' acc and gyro data
     * @param values
     * @return 6 bytes array
     */
    private byte [] float2ByteArray (float[] values, int offset)
    {
        byte[] bytes = new byte[6];
        bytes[0] = (byte) (((short)(values[0] * offset)) & 0xff);
        bytes[1] = (byte) ((((short)(values[0] * offset)) & 0xff00) >> 8);
        bytes[2] = (byte) (((short)(values[1] * offset)) & 0xff);
        bytes[3] = (byte) ((((short)(values[1] * offset)) & 0xff00) >> 8);
        bytes[4] = (byte) (((short)(values[2] * offset)) & 0xff);
        bytes[5] = (byte) ((((short)(values[2] * offset)) & 0xff00) >> 8);
        return bytes;
    }

    /**
     * get the time stamp of 6 bytes array
     * @return 6 bytes array (the first 4 bytes for seconds, the last 2 bytes for milliseconds)
     */
    private static byte [] getTimeStampByteArray() {
        long timeStamp = System.currentTimeMillis();
        int t = (int) (timeStamp / 1000);
        int millis = (int) (timeStamp % 1000);
        ByteBuffer bb = ByteBuffer.allocate(6);
        bb.put(((byte)(t & 0xff)));
        bb.put(((byte)((t >> 8) & 0xff)));
        bb.put(((byte)((t >> 16) & 0xff)));
        bb.put(((byte)((t >> 24) & 0xff)));
        bb.put(((byte)(millis & 0xff)));
        bb.put(((byte)((millis >> 8) & 0xff)));
        return bb.array();
    }

    /**
     * convert the bytes array to string in order to print
     * @param bytes
     * @return hex string
     */
    public static String tohexString(byte[] bytes) {
        StringBuffer buffer = new StringBuffer();
        if (bytes == null) {
            return "(null)";
        }

        buffer.delete(0, buffer.length());
        for (byte b : bytes) {
            buffer.append(String.format("%02X", b));
        }
        return buffer.toString();
    }
}

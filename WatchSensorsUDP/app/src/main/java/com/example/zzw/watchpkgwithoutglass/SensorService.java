package com.example.zzw.watchpkgwithoutglass;

import android.app.Service;
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

/** Service that sends watch sensor data using the helper class MessageSender.
 *
 *  Based on code by Roozbeh Jafari and his group.
 *  @author Christopher Brooks and Edward A. Lee
 */
public class SensorService extends Service implements SensorEventListener{

    private static final String TAG = "Sensor Service" ;

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
    private static final int DELAY_20HZ = 50000;
    private static final int DELAY_10HZ = 100000;

    private MessageSender sender; // socket data sender object

    // Message types.
    private static final String ACCELEROMETER_MESSAGE = "a";
    private static final String GYRO_MESSAGE = "g";

    // Constants indicating sizes in bytes of payload data.
    private static final int ACCELEROMETER_DATA_SIZE = 6;
    private static final int DEV_ID_AND_TYPE_SIZE = 4 + 1;
    private static final int GYRO_DATA_SIZE = 6;
    private static final int TIME_STAMP_SIZE = 6;

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
                    ByteBuffer bb = ByteBuffer.allocate(DEV_ID_AND_TYPE_SIZE + BYTE_NUM_BATTERY_LIFE);
                    bb.put(DEV_ID.getBytes());
                    bb.put(TYPE_BATTERY.getBytes());
                    bb.put(((byte)(level & 0xff)));
                    bb.put(getTimeStampByteArray());
                    sender.send(bb.array());
                }
            }
        }
    };

    public SensorService() {
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
        mSensorManager.registerListener(this, accSensor, DELAY_10HZ); // 20hz
        mSensorManager.registerListener(this, gyroSensor, DELAY_10HZ); //20hz

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

        // If the accelerometer is reporting, prepare a UDP packet for it.
        if (sensorType == Sensor.TYPE_ACCELEROMETER) {
            Log.d("send message", "Accelerometer data received.");
            
            // FIXME: Check to see whether the data has changed significantly.
            // Currently this is done in the accessor, but it would be better to be done here.
            // But this requires communicating back to the watch from the accessor.
            // Same for gyro data below.

            // Construct the bytes of the message to send.
            ByteBuffer sendData = ByteBuffer.allocate(
                    DEV_ID_AND_TYPE_SIZE + ACCELEROMETER_DATA_SIZE + TIME_STAMP_SIZE);
            // Start with the device ID.
            sendData.put(DEV_ID.getBytes());
            // Start with the message type. Use "a" for accelerometer data.
            sendData.put(ACCELEROMETER_MESSAGE.getBytes());
            // Append accelerometer data.
            sendData.put(float2ByteArray(sensorEvent.values, OFFSET_ACC));
            // Append time stamp.
            sendData.put(getTimeStampByteArray());
            sender.send(sendData.array());

        } else if (sensorType == Sensor.TYPE_GYROSCOPE) {
            Log.d("send message", "Gyro data received.");

            // Construct the bytes of the message to send.
            ByteBuffer sendData = ByteBuffer.allocate(
                    DEV_ID_AND_TYPE_SIZE + GYRO_DATA_SIZE + TIME_STAMP_SIZE);
            // Start with the device ID.
            sendData.put(DEV_ID.getBytes());
            // Start with the message type. Use "g" for gyro data.
            sendData.put(GYRO_MESSAGE.getBytes());
            // Append accelerometer data.
            sendData.put(float2ByteArray(sensorEvent.values, OFFSET_GYR));
            // Append time stamp.
            sendData.put(getTimeStampByteArray());
            sender.send(sendData.array());
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

    /** Convert float array with three entries into a 6 byte array.
     *  @param values A three element array.
     *  @return A 6 byte array.
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

    /** Get the time stamp as a 6 byte array.
     *  @return A 6 byte array (the first 4 bytes represents seconds, the last 2 bytes milliseconds)
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

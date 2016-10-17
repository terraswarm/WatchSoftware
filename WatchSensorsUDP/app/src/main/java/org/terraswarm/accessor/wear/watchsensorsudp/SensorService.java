/*
@Copyright (c) 2016 The Regents of the University of California.
All rights reserved.

Permission is hereby granted, without written agreement and without
license or royalty fees, to use, copy, modify, and distribute this
software and its documentation for any purpose, provided that the
above copyright notice and the following two paragraphs appear in all
copies of this software.

IN NO EVENT SHALL THE UNIVERSITY OF CALIFORNIA BE LIABLE TO ANY PARTY
FOR DIRECT, INDIRECT, SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES
ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF
THE UNIVERSITY OF CALIFORNIA HAS BEEN ADVISED OF THE POSSIBILITY OF
SUCH DAMAGE.

THE UNIVERSITY OF CALIFORNIA SPECIFICALLY DISCLAIMS ANY WARRANTIES,
INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE. THE SOFTWARE
PROVIDED HEREUNDER IS ON AN "AS IS" BASIS, AND THE UNIVERSITY OF
CALIFORNIA HAS NO OBLIGATION TO PROVIDE MAINTENANCE, SUPPORT, UPDATES,
ENHANCEMENTS, OR MODIFICATIONS.

						PT_COPYRIGHT_VERSION_2
						COPYRIGHTENDKEY


*/
package org.terraswarm.accessor.wear.watchsensorsudp;

import android.app.Notification;
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
import android.os.PowerManager;
import android.util.Log;

import java.nio.ByteBuffer;

/** Service that sends watch sensor data using the helper class MessageSender.
 *  This service sends out UDP packets (datagrams) consisting of a four-byte
 *  watch ID, a one-byte message designator, followed by some number of bytes
 *  of payload, followed finally by a time stamp.
 *  The message designators currently supported are:
 *
 *  "A": Accelerometer data. The payload is six bytes, with two bytes each
 *       for x, y, and z data. The bytes are two's complement numbers with the
 *       lower-order byte being sent first. Each two-byte number has a value
 *       between -32768 and 32767. This is interpreted as representing
 *       acceleration in meters per second squared multiplied by a
 *       constant, SCALE_ACCELEROMETER = 836, so the receiver of these
 *       bytes should divide by 836 to get units of m/s^2.
 *
 *  "G": Gyroscope data. The payload is six bytes, with two bytes each
 *       for x, y, and z data. The bytes are two's complement numbers with the
 *       lower-order byte being sent first. Each two-byte number has a value
 *       between -32768 and 32767. This is interpreted as representing
 *       rotation in radians per second multiplied by a
 *       constant, SCALE_GYRO = 5208, so the receiver of these
 *       bytes should divide by 5208 to get units of radians/s.
 *
 *  The sample period with which sensor data is read is determined by the
 *  SAMPLE_PERIOD variable, which default to 100,000, for 10Hz samples.
 *  FIXME: This should be settable on the watch.
 *
 *  FIXME: Document the time stamp.
 *
 *  @author Christopher Brooks, Edward A. Lee, and Ziwei (William) Zhu
 */
public class SensorService extends Service implements SensorEventListener {

    public SensorService() {
    }

    ///////////////////////////////////////////////////////////////////
    ////                         public methods                    ////

    /** Get the accelerometer sensitivity as a fraction of full scale.
     *  This service will not send a UDP packet
     *  unless at least one of the accelerometer readings exceeds this value in magnitude.
     *  @return The sensitivity as a fraction of full scale, between 0.0 and 1.0.
     */
    public static double getAccelerometerSensitivity() {
        return _sensitivityAccelerometer * SCALE_ACCELEROMETER / 32768.0;
    }

    /** Get the gyroscope sensitivity as a fraction of full scale.
     *  This service will not send a UDP packet
     *  unless at least one of the gyro readings exceeds this value in magnitude.
     *  @return The sensitivity as a fraction of full scale, between 0.0 and 1.0.
     */
    public static double getGyroSensitivity() {
        return _sensitivityGyro * SCALE_GYRO / 32768.0;
    }

    /** Initialize the UDP socket _sender. */
    public boolean initialize() {
        // Get the instance of the socket _sender.
        _sender = MessageSender.getInstance();
        return true;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

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

    /** Unregister this class as a listener for sensor data. */
    @Override
    public void onDestroy() {
        unregisterReceiver(stopInfoReceiver);
        unregisterReceiver(mBatInfoReceiver);
        _mSensorManager.unregisterListener(this);
        _wakeLock.release();
        stopForeground(true);
    }

    /** React to new sensor data by sending a UDP packet. This is a
     *  callback function that will be called when sensor data is available.
     *  Note that this callback is misnamed. It is apparently called periodically
     *  and it has nothing to do with whether the sensor data has changed.
     *  @param sensorEvent An event containing the sensor type and data.
     */
    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        int sensorType = sensorEvent.sensor.getType();

        // If the accelerometer is reporting, prepare a UDP packet for it.
        if (sensorType == Sensor.TYPE_ACCELEROMETER) {
            // Check to see whether the data has changed significantly.
            // This is also done in the accessor, possibly with a different sensitivity.
            boolean changed = false;
            for (int i = 0; i < 3; i++) {
                if (Math.abs(sensorEvent.values[i] - _previousAccelerometer[i])
                        > _sensitivityAccelerometer) {
                    changed = true;
                }
            }
            if (changed) {
                for (int i = 0; i < 3; i++) {
                    // Record the previous value we send, not the previous value read.
                    _previousAccelerometer[i] = sensorEvent.values[i];
                }
                // Construct the bytes of the message to send.
                ByteBuffer sendData = ByteBuffer.allocate(
                        DEV_ID_AND_TYPE_SIZE + ACCELEROMETER_DATA_SIZE + TIME_STAMP_SIZE);
                // Start with the device ID.
                sendData.put(DEVICE_ID.getBytes());
                // Start with the message type. Use "A" for accelerometer data.
                sendData.put(ACCELEROMETER_MESSAGE.getBytes());
                // Append accelerometer data.
                sendData.put(float2ByteArray(sensorEvent.values, SCALE_ACCELEROMETER));
                // Append time stamp.
                sendData.put(getTimeStampByteArray());
                _sender.send(sendData.array());
            }

        } else if (sensorType == Sensor.TYPE_GYROSCOPE) {
            // Check to see whether the data has changed significantly.
            // This is also done in the accessor, possibly with a different sensitivity.
            boolean changed = false;
            for (int i = 0; i < 3; i++) {
                if (Math.abs(sensorEvent.values[i] - _previousGyro[i])
                        > _sensitivityGyro) {
                    changed = true;
                }
            }
            if (changed) {
                for (int i = 0; i < 3; i++) {
                    // Record the previous value sent, not the previous value read.
                    _previousGyro[i] = sensorEvent.values[i];
                }
                // Construct the bytes of the message to send.
                ByteBuffer sendData = ByteBuffer.allocate(
                        DEV_ID_AND_TYPE_SIZE + GYRO_DATA_SIZE + TIME_STAMP_SIZE);
                // Start with the device ID.
                sendData.put(DEVICE_ID.getBytes());
                // Start with the message type. Use "G" for gyro data.
                sendData.put(GYRO_MESSAGE.getBytes());
                // Append accelerometer data.
                sendData.put(float2ByteArray(sensorEvent.values, SCALE_GYRO));
                // Append time stamp.
                sendData.put(getTimeStampByteArray());
                _sender.send(sendData.array());
            }
        }
    }

    /** Register this class as a listener for sensor data and set the service to
     *  not sleep so that sensor data is continually sent.
     *
     * @param intent The Intent supplied to {@link android.content.Context#startService},
     * as given.  This may be null if the service is being restarted after
     * its process has gone away, and it had previously returned anything
     * except {@link #START_STICKY_COMPATIBILITY}.
     * @param flags Additional data about this start request.  Currently either
     * 0, {@link #START_FLAG_REDELIVERY}, or {@link #START_FLAG_RETRY}.
     * @param startId A unique integer representing this specific request to
     * start.  Use with {@link #stopSelfResult(int)}.
     *
     * @return The return value indicates what semantics the system should
     * use for the service's current started state.  It may be one of the
     * constants associated with the {@link #START_CONTINUATION_MASK} bits.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.v(TAG, "ServiceDemo onStartCommand");
        initialize();

        // Acquire a CPU wake lock.
        PowerManager mgr = (PowerManager) getSystemService(Context.POWER_SERVICE);
        _wakeLock = mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchletWakeLock");
        _wakeLock.acquire();

        // Create a default notification.
        Notification.Builder builder = new Notification.Builder(this);
        Notification note = builder.build();
        note.flags |= Notification.FLAG_NO_CLEAR;

        // Set the service as a foreground service with a notification to remind the user.
        // A foreground service will not be killed to reclaim memory.
        // The first argument is an ID, apparently arbitrary.
        startForeground(1234, note);

        // Register the two broadcast receivers.
        // FIXME: Are these still used?
        IntentFilter filter = new IntentFilter();
        filter.addAction(MainActivity.STOP_ACTION);
        registerReceiver(stopInfoReceiver, filter);
        registerReceiver(mBatInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        _mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE); // get SensorManager
        Sensor accSensor = _mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER); // get Accelerometer
        Sensor gyroSensor = _mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE); // get Gyroscope
        _mSensorManager.registerListener(this, accSensor, SAMPLE_PERIOD);
        _mSensorManager.registerListener(this, gyroSensor, SAMPLE_PERIOD);

        super.onStartCommand(intent, flags, startId);

        return Service.START_STICKY; // make the service not to be killed, if be killed it will restart itself again
    }

    /** Set the accelerometer sensitivity. This service will not send a UDP packet
     *  unless at least one of the accelerometer readings exceeds this value in magnitude.
     *  @param sensitivity The sensitivity on a scale of 0.0 to 1.0, interpreted as a fraction
     *                     of full scale.
     */
    public static void setAccelerometerSensitivity(double sensitivity) {
        _sensitivityAccelerometer = (float) (sensitivity * 32768.0 / SCALE_ACCELEROMETER);
    }

    /** Set the gyroscope sensitivity. This service will not send a UDP packet
     *  unless at least one of the gyro readings exceeds this value in magnitude.
     *  @param sensitivity The sensitivity on a scale of 0.0 to 1.0, interpreted as a fraction
     *                     of full scale.
     */
    public static void setGyroSensitivity(double sensitivity) {
        _sensitivityGyro = (float) (sensitivity * 32768.0 / SCALE_GYRO);
    }

    /** Convert a byte array to a string in hex in order to print.
     *  @param bytes The bytes to be converted.
     *  @return A hex string.
     */
    public static String toHexString(byte[] bytes) {
        StringBuilder buffer = new StringBuilder();
        if (bytes == null) {
            return "(null)";
        }

        buffer.delete(0, buffer.length());
        for (byte b : bytes) {
            buffer.append(String.format("%02X", b));
        }
        return buffer.toString();
    }

    ///////////////////////////////////////////////////////////////////
    ////                         private methods                   ////

    /** Convert float array with three entries into a 6 byte array.
     *  Each float is first scaled by the scale parameter.
     *  If the result is greater than 32767 (2^15-1), then it
     *  is first saturated to 32767. If the scaled result is less
     *  than -32768, then it is saturated to -32768.
     *
     *  @param values A three element array.
     *  @param scale A scaling factor to apply before converting.
     *  @return A 6 byte array.
     */
    private byte[] float2ByteArray(float[] values, int scale) {
        byte[] bytes = new byte[6];
        for (int i = 0; i < 3; i++) {
            float value = values[i] * scale;
            if (value > 32767) {
                value = 32767;
            } else if (value < -32768) {
                value = -32768;
            }
            values[i] = value;
        }
        bytes[0] = (byte) (((short) values[0]) & 0xff);
        bytes[1] = (byte) ((((short) values[0]) & 0xff00) >> 8);
        bytes[2] = (byte) (((short) values[1]) & 0xff);
        bytes[3] = (byte) ((((short) values[1]) & 0xff00) >> 8);
        bytes[4] = (byte) (((short) values[2]) & 0xff);
        bytes[5] = (byte) ((((short) values[2]) & 0xff00) >> 8);
        return bytes;
    }

    /** Get a time stamp from the current system time as a 6 byte array.
     *  @return A 6 byte array, where the first 4 bytes represents seconds
     *    and the last 2 bytes milliseconds since January 1, 1970.
     */
    private static byte[] getTimeStampByteArray() {
        long timeStamp = System.currentTimeMillis();
        int t = (int) (timeStamp / 1000);
        int millis = (int) (timeStamp % 1000);
        ByteBuffer bb = ByteBuffer.allocate(6);
        bb.put(((byte) (t & 0xff)));
        bb.put(((byte) ((t >> 8) & 0xff)));
        bb.put(((byte) ((t >> 16) & 0xff)));
        bb.put(((byte) ((t >> 24) & 0xff)));
        bb.put(((byte) (millis & 0xff)));
        bb.put(((byte) ((millis >> 8) & 0xff)));
        return bb.array();
    }

    /**
     * Waiting for the battery life change broadcast, every 3% changed
     * send a notification to the server.
     * FIXME: This needs a pass and better documentation.
     */
    private BroadcastReceiver mBatInfoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctxt, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            if (_lastBattery == -1) {
                _lastBattery = level;
            } else {
                if (level - _lastBattery >= 3) {
                    Log.d(TAG, "battery " + String.valueOf(level));
                    ByteBuffer bb = ByteBuffer.allocate(DEV_ID_AND_TYPE_SIZE + BATTERY_LIFE_SIZE);
                    bb.put(DEVICE_ID.getBytes());
                    bb.put(TYPE_BATTERY.getBytes());
                    bb.put(((byte) (level & 0xff)));
                    bb.put(getTimeStampByteArray());
                    _sender.send(bb.array());
                }
            }
        }
    };

    /**
     * Waiting for the stop broadcast, when received start to
     * disconnect the glasses and stop the service.
     * FIXME: Is this still needed? We don't have glasses.
     */
    private final BroadcastReceiver stopInfoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            String action = intent.getAction();
            if (action.equals(MainActivity.STOP_ACTION)) {
                stopSelf();
            }
        }
    };

    ///////////////////////////////////////////////////////////////////
    ////                         private variables                 ////

    private static final String TAG = "Sensor Service";

    /** Sensor manager for which this class is a listener.  */
    private SensorManager _mSensorManager;

    /** Scaling factor to apply to accelerometer data before
     *  converting to a two-byte integer. The accelerometer data
     *  is provided by the watch in SI units of meters per second squared,
     *  so one g is about 9.8 m/s^2.  If we want to be able to measure
     *  4 g's, then we need 9.8 * 4 * SCALE_ACCELEROMETER to be no larger
     *  than 32768 = 2^15.  So a reasonable scaling factor is
     *  32768 /(9.8 * 4) = 836.
     */
    private static final int SCALE_ACCELEROMETER = 836;

    /** The default sensitivity of the accelerometer in m/s^2.
     *  If no accelerometer reading differs from the previous
     *  accelerometer reading by more than this amount, then no
     *  message will be sent.
     */
    private static final float SENSITIVITY_ACCELEROMETER = 1.0f;

    /** The actual sensitivity of the accelerometer in m/s^2.
     *  This defaults to SENSITIVITY_ACCELEROMETER.
     */
    private static float _sensitivityAccelerometer = SENSITIVITY_ACCELEROMETER;

    /** The previous accelerometer reading, to be used to determine
     *  whether the data has changed by more than the sensitivity.
     */
    private float[] _previousAccelerometer = {-100.0f, -100.0f, -100.0f};

    /** Scaling factor to apply to gyroscope data before
     *  converting to a two-byte integer. The gyroscope data
     *  is provided by the watch in units of radians per second.
     *  If we want to be able to measure rotation velocities up to
     *  about one revolution per second (2 * PI radians per second),
     *  then we need 2 * 3.1459 * SCALE_GYRO to be no larger
     *  than 32768 = 2^15.  So a reasonable scaling factor is
     *  32768 /(2 * 3.1459) = 5208.
     */
    private static final int SCALE_GYRO = 5208;

    /** The sensitivity of the gyro in radians/s.
     *  If no gyro reading differs from the previous
     *  gyro reading by more than this amount, then no
     *  message will be sent.
     *  FIXME: This should be settable somehow on the watch.
     */
    private static final float SENSITIVITY_GYRO = 1.0f;

    /** The actual sensitivity of the gyroscope in radians/second.
     *  This defaults to SENSITIVITY_GYRO.
     */
    private static float _sensitivityGyro = SENSITIVITY_GYRO;

    /** The previous accelerometer reading, to be used to determine
     *  whether the data has changed by more than the sensitivity.
     */
    private float[] _previousGyro = {-100.0f, -100.0f, -100.0f};

    /** The message _sender that handles sending datagrams. */
    private MessageSender _sender;

    /** Message types designator for accelerometer data. */
    private static final String ACCELEROMETER_MESSAGE = "A";

    /** Message types designator for gyroscope data. */
    private static final String GYRO_MESSAGE = "G";

    /** Message types designator for battery data. */
    private static final String TYPE_BATTERY = "b";

    /** Sample period in microseconds. 100000 is 10Hz. */
    private static final int SAMPLE_PERIOD = 500000;

    // Constants indicating sizes in bytes of payload data.
    private static final int ACCELEROMETER_DATA_SIZE = 6;
    private static final int DEV_ID_AND_TYPE_SIZE = 4 + 1;
    private static final int GYRO_DATA_SIZE = 6;
    private static final int TIME_STAMP_SIZE = 6;
    private static final int BATTERY_LIFE_SIZE = 1 + 6;

    /** Device ID. */
    private static final String DEVICE_ID = Build.SERIAL.substring(6);

    private int _lastBattery = -1;

    /** Wake lock to make the CPU still work when the screen dims. */
    private PowerManager.WakeLock _wakeLock;
}

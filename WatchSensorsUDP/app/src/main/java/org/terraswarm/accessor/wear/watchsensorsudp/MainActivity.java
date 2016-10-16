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

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.wearable.activity.WearableActivity;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.util.Log;

/** A Moto 360 Watch activity that sends sensor data to a specified IP address
 *
 * @author Ziwei Zhu (Roozbeh Jafari and his group at TAMU), Edward A. Lee.  Contributor: Christopher Brooks
 */
public class MainActivity extends WearableActivity
        implements SeekBar.OnSeekBarChangeListener {
    public static final String STOP_ACTION = "STOP_BLE_SERVICE";

    final String id = " v2 " + MessageSender.Server_IP;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        _connectButton = (Button) findViewById(R.id.switch_connect_btn);
        if (isServiceRunning(SensorService.class)) {
            _connectButton.setText("disconnect" + id);
        } else {
            _connectButton.setText("connect" + id);
        }

        _connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isServiceRunning(SensorService.class)) {

                    // send broadcast to stop the service
                    sendBroadcast(new Intent(STOP_ACTION));
                    _connectButton.setText("connect" + id);
                } else {
                    // start the service
                    startSensorService();
                    if (isServiceRunning(SensorService.class)) {
                        _connectButton.setText("disconnect" + id);
                    }
                }
            }
        });

        _seekBar = (SeekBar) findViewById(R.id.seekBar);
        _seekBar.setOnSeekBarChangeListener(this);

        // Set the slider to indicate the sensitivity.
        // Note that we use the same slider for gyro and accelerometer,
        // but read the accelerometer sensitivity.
        double sensitivity = SensorService.getAccelerometerSensitivity();
        int percentage = sensitivityToPercentage(sensitivity);
        Log.d("setup", "Setting slider to " + percentage + " based on sensitivity " + sensitivity);
        _seekBar.setProgress(percentage);
    }

    /** React to a change in the seekBar slider setting by setting the sensitivity of the
     *  seensors.
     *  @param seekBar The seek bar.
     *  @param progress A number between 0 and 100 indicating the seek bar position.
     *  @param fromUser True if this comes from the user.
     */
    public void onProgressChanged (SeekBar seekBar,
                                   int progress,
                                   boolean fromUser) {
        double sensitivity = percentageToSensitivity(progress);
        Log.d("slider", "Slider set to " + progress + " resulting in sensitivity " + sensitivity);
        SensorService.setAccelerometerSensitivity(sensitivity);
        SensorService.setGyroSensitivity(sensitivity);
    };

    public void onStartTrackingTouch (SeekBar seekBar) {};

    public void onStopTrackingTouch (SeekBar seekBar) {};

    ///////////////////////////////////////////////////////////////////
    ////                         private methods                   ////

    /** Convert a percentage (from the SeekBar slider) to a sensitivity
     *  using a highly non-linear scale and a reversal, so that 100 maps
     *  to 0.0 (meaning full sensitivity... all measurements are above
     *  threshold) and 0 maps to 1.0 (no sensitivity).
     *  @param percentage The percentage of the slider bar.
     *  @return The sensitivity on a scale of 0.0 to 1.0.
     */
    private double percentageToSensitivity(int percentage) {
        if (percentage == 0) {
            return 1.0;
        } else if (percentage == 100) {
            return 0.0;
        } else {
            return 1.0 - Math.pow(percentage/100.0, 1.0/16);
        }
    }

    /** Convert a sensitivity as a fraction from 0.0 to 1.0
     *  to a percentage for the SeekBar slider using a highly non-linear scale
     *  and a reversal so that 0.0 maps to 100 and 1.0 maps to 0.
     *  @param sensitivity The sensitivity on a scale of 0.0 to 1.0.
     *  @return A percentage for the SeekerBar.
     */
    private int sensitivityToPercentage(double sensitivity) {
        double raw = Math.pow(1.0 - sensitivity, 16.0);
        if (raw <= 0.0) {
            return 0;
        } else if (raw >= 1.0) {
            return 100;
        } else {
            return (int) Math.round(100 * raw);
        }
    }

    /** Start the sensor service.
     */
    private void startSensorService() {
        startService(new Intent(this, SensorService.class));
    }

    /** Return true if the service is running.
     *  @return True if the service is running.
     */
    private boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    ///////////////////////////////////////////////////////////////////
    ////                         private variables                 ////

    private final static String TAG = "MainActiviy";

    /** The button to connect and disconnect. */
    private Button _connectButton;

    /** The slider used to set the sensitivity. */
    private SeekBar _seekBar;
}

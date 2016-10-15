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

/**
 * Create a Moto 360 Watch activity.
 *
 * @author Ziwei Zhu (Roozbeh Jafari and his group at TAMU), Edward A. Lee.  Contributor: Christopher Brooks
 */
public class MainActivity extends WearableActivity {
    public static final String STOP_ACTION = "STOP_BLE_SERVICE";

    final String id = " 20 " + MessageSender.Server_IP;

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
    }

    ///////////////////////////////////////////////////////////////////
    ////                         private methods                   ////

    private void startSensorService() {
        startService(new Intent(this, SensorService.class));
    }

    /**
     * Check whether the service is running.
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

    private Button _connectButton;
}

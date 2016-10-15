package com.example.zzw.watchpkgwithoutglass;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.wearable.activity.WearableActivity;
import android.view.View;
import android.widget.Button;

public class MainActivity extends WearableActivity {
    private final static String TAG = "MainActiviy";

    private Button connectButton;
    public static final String STOP_ACTION = "STOP_BLE_SERVICE";

    final String id = "_" + MessageSender.Server_IP;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        connectButton = (Button) findViewById(R.id.switch_connect_btn);
        if (isServiceRunning(SensorService.class)) {
            connectButton.setText("disconnect" + id);
        }else {
            connectButton.setText("connect" + id);
        }

        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isServiceRunning(SensorService.class)) {

                    // send broadcast to stop the service
                    sendBroadcast(new Intent(STOP_ACTION));
                    connectButton.setText("connect" + id);
                } else {
                    // start the service
                    startSensorService();
                    if (isServiceRunning(SensorService.class)) {
                        connectButton.setText("disconnect" + id);
                    }
                }
            }
        });
    }

    private void startSensorService() {
        startService(new Intent(this, SensorService.class));
    }

    /**
     *  check whether the service is running
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
}

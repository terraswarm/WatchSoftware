/* This class sends message from wearable to Android phone. Currently, we send
the training template to android phone as well as the notification whenever
a gesture is recognized.
 */
package com.example.zzw.watchpkgwithoutglass;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MessageSender {

    private DatagramSocket socket = null;
    private InetAddress serverAddress = null; // the address of server
    private int servPort = 4568;

    // the ip address of the server PC or android phone
    // static final String Server_IP = "192.168.1.237";
    //static final String Server_IP = "10.0.0.255";
    static final String Server_IP = "10.42.0.255";

    // Since asynchronous/blocking functions should not run on the UI thread.
    private ExecutorService executorService;

    private MessageSender() {
        executorService = Executors.newCachedThreadPool();
        try {
            serverAddress = InetAddress.getByName(Server_IP);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        try {
            socket = new DatagramSocket(servPort);
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    // It's a singleton class.
    private static MessageSender instance = null;
    public static synchronized MessageSender getInstance() {
        if (instance == null) {
            instance = new MessageSender();
        }
        return instance;
    }

    /**
     * send data to server by UDP
     * @param data the data need to be sent to server
     */
    public void send(final byte[] data) {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                Log.e("send bytes", "[send] " + SensorService.tohexString(data));
                DatagramPacket p = new DatagramPacket(data, data.length, serverAddress,
                        servPort);
                try {
                    socket.send(p);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }
}

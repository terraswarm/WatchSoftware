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

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Send a message from the wearable to the network.
 *
 * @author Ziwei Zhu (Roozbeh Jafari and his group at TAMU), Edward A. Lee.  Contributor: Christopher Brooks
 */
public class MessageSender {


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

    public static synchronized MessageSender getInstance() {
        if (instance == null) {
            instance = new MessageSender();
        }
        return instance;
    }

    /**
     * Send data to server by UDP.
     *
     * @param data the data need to be sent to server
     */
    public void send(final byte[] data) {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                Log.e("send bytes", "[send] " + SensorService.toHexString(data));
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

    ///////////////////////////////////////////////////////////////////
    ////                         package protected variables       ////

    // The ip address of the server PC or android phone
    // static final String Server_IP = "192.168.1.237";
    static final String Server_IP = "10.0.0.255";
    // static final String Server_IP = "10.42.0.255";

    ///////////////////////////////////////////////////////////////////
    ////                         private variables                 ////

    // This class is a singleton.
    private static MessageSender instance = null;

    private DatagramSocket socket = null;
    private InetAddress serverAddress = null; // The address of server
    private int servPort = 4568;

    // Since asynchronous/blocking functions should not run on the UI thread.
    private ExecutorService executorService;

}

/*
 * Copyright (C) 2014 Disrupted Systems
 *
 * This file is part of Rumble.
 *
 * Rumble is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Rumble is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rumble.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.disrupted.rumble.network.protocols.rumble;

import android.bluetooth.BluetoothSocket;
import android.util.Log;

import org.disrupted.rumble.network.NeighbourManager;
import org.disrupted.rumble.network.NetworkCoordinator;
import org.disrupted.rumble.network.ThreadPoolCoordinator;
import org.disrupted.rumble.network.exceptions.RecordNotFoundException;
import org.disrupted.rumble.network.linklayer.LinkLayerNeighbour;
import org.disrupted.rumble.network.NetworkThread;
import org.disrupted.rumble.network.linklayer.bluetooth.BluetoothConnection;
import org.disrupted.rumble.network.linklayer.bluetooth.BluetoothNeighbour;
import org.disrupted.rumble.network.linklayer.bluetooth.BluetoothServer;
import org.disrupted.rumble.network.linklayer.bluetooth.BluetoothServerConnection;

/**
 * @author Marlinski
 */
public class RumbleBTServer extends BluetoothServer {

    private static final String TAG = "RumbleBluetoothServer";

    public RumbleBTServer() {
        super(RumbleProtocol.RUMBLE_BT_UUID_128, RumbleProtocol.RUMBLE_BT_STR, false);
    }

    @Override
    public String getNetworkThreadID() {
        return RumbleProtocol.protocolID + super.getNetworkThreadID();
    }

    /*
     * onClientConnected may accept or not the connection depending on RumbleBTState
     */
    @Override
    protected NetworkThread onClientConnected(BluetoothSocket mmConnectedSocket) {
        LinkLayerNeighbour neighbour = new BluetoothNeighbour(mmConnectedSocket.getRemoteDevice().getAddress());
        NeighbourManager record;
        try {
            record = NetworkCoordinator.getInstance().getNeighbourRecordFromDeviceAddress(neighbour.getLinkLayerAddress());

            switch (record.getRumbleBTState().getState()) {
                case CONNECTION_INITIATED:
                    if (neighbour.getLinkLayerAddress().compareTo(localMacAddress) < 0) {
                        Log.d(TAG, "[-] refusing connection");
                        return null;
                    } else {
                        Log.d(TAG, "[-] cancelling network thread "+record.getRumbleBTState().getConnectionInitiatedThreadID());
                        ThreadPoolCoordinator.getInstance()
                                .killThreadID(record.getRumbleBTState().getConnectionInitiatedThreadID());
                    }
                case NOT_CONNECTED:
                    NetworkThread thread = new RumbleOverBluetooth(new BluetoothServerConnection(mmConnectedSocket));
                    record.getRumbleBTState().connectionAccepted(thread.getNetworkThreadID());
                    return thread;
                default: return null;
            }

        } catch(RecordNotFoundException e) {
            Log.e(TAG,"[!] record not found for neighbour "+neighbour.getLinkLayerAddress());
            return null;
        } catch (RumbleBTState.StateException e) {
            Log.e(TAG,"[!] Rumble Bluetooth State Exception");
            return null;
        }
    }

}

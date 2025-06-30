package functions;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.*;

import server.serverInfo;

public interface monitor_interface extends Remote{
    void registerServer(functions server, int ID) throws RemoteException;
    void sendHeartbeat(serverInfo status) throws RemoteException;
    //void unregisterServer(functions server) throws java.rmi.RemoteException;
}

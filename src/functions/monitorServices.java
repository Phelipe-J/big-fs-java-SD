package functions;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.Set;

import server.serverInfo;

public interface monitorServices extends Remote {

    void registerServer(serverServices server, int ID, Set<String> serverManifest) throws RemoteException;
    void sendHeartbeat(serverInfo status) throws RemoteException;
}

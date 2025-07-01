package functions;

import java.rmi.Remote;
import java.rmi.RemoteException;

import javax.security.auth.login.LoginException;

import client.clientServices;

public interface authenticationService extends Remote {
    clientServices login(String username, String password) throws LoginException, RemoteException;
    void registerUser(String username, String password) throws RemoteException;
}

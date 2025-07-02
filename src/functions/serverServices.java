package functions;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface serverServices extends Remote {  
    public void beginUpload(String fileName) throws RemoteException;
    public void uploadBlock(String fileName, byte[] block, int length) throws RemoteException;
    public void endUpload(String fileName) throws RemoteException;

    public byte[] download(String filePath, long offset, int fileSize) throws RemoteException;

    public boolean delete(String filePath) throws RemoteException;

    public boolean createFolder(String folderName) throws RemoteException;

    public int getServerID() throws RemoteException;

    void copyFileToPeer(String path, serverServices destinationPeer) throws RemoteException;
}

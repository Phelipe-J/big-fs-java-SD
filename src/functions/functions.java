package functions;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface functions extends Remote{
    public String[] list() throws RemoteException;
    
    public void beginUpload(String fileName) throws RemoteException;
    public void uploadBlock(String fileName, byte[] block, int length) throws RemoteException;
    public void endUpload(String fileName) throws RemoteException;

    public byte[] download(String filePath, long offset, int fileSize) throws RemoteException;
    public long downloadFileSize(String filePath) throws RemoteException;

    public boolean delete(String filePath) throws RemoteException;
}

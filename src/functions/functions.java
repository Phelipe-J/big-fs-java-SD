package functions;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.*;

public interface functions extends Remote{
    public String[] list() throws RemoteException;
    
    public void beginUpload(String fileName, user aUser) throws RemoteException;
    public void uploadBlock(String fileName, byte[] block, int length) throws RemoteException;
    public void uploadBlock(String fileName, byte[] block, int length, user aUser) throws RemoteException;
    public void endUpload(String fileName, long fileSize) throws RemoteException;
    public void endUpload(String fileName, long fileSize, user aUser) throws RemoteException;

    public byte[] download(String filePath, long offset, int fileSize) throws RemoteException;
    public byte[] download(String filePath, long offset, int fileSize, user aUser) throws RemoteException;
    public long downloadFileSize(String filePath) throws RemoteException;
    public long downloadFileSize(String filePath, user aUser) throws RemoteException;
    public boolean isFolder(String filePath) throws RemoteException;
    public boolean isFolder(String filePath, user aUser) throws RemoteException;
    public List<String> listFolderFiles(String folderPath) throws RemoteException;
    public List<String> listFolderFiles(String folderPath, user aUser) throws RemoteException;

    public boolean delete(String filePath) throws RemoteException;
    public boolean delete(String filePath, user aUser) throws RemoteException;

    public boolean createFolder(String folderName) throws RemoteException;
    public boolean createFolder(String folderName, user aUser) throws RemoteException;
    public boolean inFolder(String folderPath) throws RemoteException;
    public boolean inFolder(String folderPath, user aUser) throws RemoteException;
    public boolean backFolder() throws RemoteException;
    public boolean backFolder(user aUser) throws RemoteException;

    public int getServer_ID() throws RemoteException;
}

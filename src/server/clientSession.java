package server;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;

import client.clientServices;
import functions.file;
import functions.folder;
import functions.user;

public class clientSession implements clientServices {
    private user currentUser;
    private folder currentDirectory;
    private monitor mainMonitor;

    public clientSession(user user, monitor mainMonitor) throws RemoteException{
        this.currentUser = user;
        this.mainMonitor = mainMonitor;
        this.currentDirectory = user.getRootDir();

        UnicastRemoteObject.exportObject(this, 0);
    }

    private String buildLogicalPath(String clientPath){
        String[] remotePath = clientPath.split(":\\\\");       // Separa a raiz do resto do endereço

        if(remotePath[0].equals("remote")){ // Raiz remota? ==> endereço completo
            return remotePath[1];
        }
        else{       // endereço relativo
            if(currentDirectory.getFolderName().equals(currentUser.getRootDir().getFolderName())){
                return clientPath;
            }
            else{
                return currentDirectory.getFolderPath() + "\\" + clientPath;
            }
        }
    }

    public boolean inFolder(String folderName) throws RemoteException{
        for(folder sub : currentDirectory.getSubFolders()){
            if(sub.getFolderName().equalsIgnoreCase(folderName)){
                currentDirectory = sub;
                return true;
            }
        }
        System.out.println("Pasta não encontrada.");
        return false;
    }

    public boolean backFolder() throws RemoteException{
        if(currentDirectory.getParentFolder() != null){
            currentDirectory = currentDirectory.getParentFolder();
            return true;
        }
        System.out.println("Nao foi possivel voltar.");
        return false;
    }

    public String[] list() throws RemoteException {
        if(currentDirectory == null){
            return new String[]{"Erro: diretório atual não inicializado."};
        }

        List<folder> subFolders = currentDirectory.getSubFolders();
        List<file> files = currentDirectory.getFiles();

        String[] result = new String[subFolders.size() + files.size()];
        int index = 0;

        for (folder f : subFolders){
            result[index++] = "[DIR] " + f.getFolderName();
        }
        for(file f : files){
            result[index++] = "[ARQ] " + f.getFileName();
        }

        return result;
    }

    public void beginUpload(String filePath) throws RemoteException{
        String trueFilePath = buildLogicalPath(filePath);
        mainMonitor.beginUpload(trueFilePath, currentUser);
    }

    public void uploadBlock(String filePath, byte[] block, int length) throws RemoteException{
        String trueFilePath = buildLogicalPath(filePath);
        mainMonitor.uploadBlock(trueFilePath, block, length, currentUser);
    }

    public void endUpload(String filePath, long fileSize) throws RemoteException{
        String trueFilePath = buildLogicalPath(filePath);
        mainMonitor.endUpload(trueFilePath, fileSize, currentUser);
    }
    // asdsadasd
    public byte[] download(String filePath, long offset, int fileSize) throws RemoteException{
        String trueFilePath = buildLogicalPath(filePath);
        return mainMonitor.download(trueFilePath, offset, fileSize, currentUser);
    }

    public long downloadFileSize(String filePath) throws RemoteException{
        String trueFilePath = buildLogicalPath(filePath);
        return mainMonitor.downloadFileSize(trueFilePath, currentUser);
    }

    public boolean isFolder(String filePath) throws RemoteException{
        String trueFilePath = buildLogicalPath(filePath);
        return mainMonitor.isFolder(trueFilePath, currentUser);
    }

    public List<String> listFolderFiles(String folderPath) throws RemoteException{
        String trueFilePath = buildLogicalPath(folderPath);
        return mainMonitor.listFolderFiles(trueFilePath, currentUser);
    }

    public boolean delete(String filePath) throws RemoteException{
        String trueFilePath = buildLogicalPath(filePath);
        return mainMonitor.delete(trueFilePath, currentUser);
    }

    public boolean createFolder(String filePath) throws RemoteException{
        String trueFilePath = buildLogicalPath(filePath);
        return mainMonitor.createFolder(trueFilePath, currentUser);
    }
}

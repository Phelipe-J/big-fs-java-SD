package server;

import java.rmi.RemoteException;
import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.*;

import functions.functions;

public class server implements functions{

    private static final String rootFolderPath = "S:\\Sistemas_Distribuidos\\rmi\\server\\";
    private File rootFolder = new File(rootFolderPath);
    private File currentFolder = rootFolder;
    //private String currentPath = rootFolderPath;
    private Map<String, FileOutputStream> fileOutStreams = new HashMap<>();


    public String[] list(){
        //File folder = new File(rootFolderPath);
        File[] files = currentFolder.listFiles();

        String[] result = new String[files.length];

        for(int i = 0; i < files.length; i++){
            if(files[i].isFile()){
                result[i] = "[ARQ] " + files[i].getName();
                //System.out.println("Arq: " + files[i].getName());
            }
            else if(files[i].isDirectory()){
                result[i] = "[DIR] " + files[i].getName();
                //System.out.println("Dir: " + files[i].getName());
            }
        }

        return result;
    }

    public void beginUpload(String fileName){
        try{
            FileOutputStream fos = new FileOutputStream(rootFolderPath + fileName);
            fileOutStreams.put(fileName, fos);
            System.out.println("Inciando upload de: " + fileName);
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }

    public void uploadBlock(String fileName, byte[] block, int length){
        try{
            FileOutputStream fos = fileOutStreams.get(fileName);
            if(fos != null){
                fos.write(block, 0, length);
            }
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }

    public void endUpload(String fileName){
        try{
            FileOutputStream fos = fileOutStreams.remove(fileName);
            if (fos != null) {
                fos.close();
                System.out.println("Upload concluído de: " + fileName);
            }
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }


    public byte[] download(String filePath, long offset, int blockSize) throws RemoteException{
        try (RandomAccessFile file = new RandomAccessFile(rootFolderPath+filePath, "r")){
            byte[] buffer = new byte[blockSize];

            file.seek(offset);
            int bytesRead = file.read(buffer);

            if(bytesRead == -1){
                return new byte[0];
            }
            else if(bytesRead < blockSize){
                return Arrays.copyOf(buffer, bytesRead);
            }
            else{
                return buffer;
            }
        }
        catch(Exception e){
            e.printStackTrace();
            throw new RemoteException("Erro ao ler o arquivo.", e);
        }
    }

    public long downloadFileSize(String filePath) throws RemoteException{
        File file = new File(rootFolder, filePath);
        if(!file.exists() || !file.isFile()) return -1;
        return (long) file.length();
    }

    public List<String> listFolderFiles(String folderPath){
        try{
            List<String> fileList = new ArrayList<>();
            File root = new File(rootFolder, folderPath);
            int baseLen = root.getAbsolutePath().length()+1;

            if(!root.exists() || !root.isDirectory()) return fileList;

            listAllFiles(root, fileList, baseLen);

            return fileList;
        }
        catch(Exception e){
            e.printStackTrace();
            return null;
        }
    }

    private void listAllFiles(File dir, List<String> files, int baseLen){
        for(File f: dir.listFiles()){
            if(f.isDirectory()){
                listAllFiles(f, files, baseLen);
            }
            else{
                files.add(f.getAbsolutePath().substring(baseLen));
            }
        }
    }

    public boolean isFolder(String filePath){
        File file = new File(rootFolderPath + filePath);
        return file.isDirectory();
    }

    public boolean delete(String filePath) throws RemoteException{
        try{
            String[] folders = filePath.split(":\\\\");

            File file;

            if(folders.length == 2){        // Passado caminho inteiro desde a raiz
                file = new File(rootFolderPath + folders[1]);
            }
            else{
                file = new File(currentFolder, filePath);
            }

            
            if(file.delete()){
                return true;
            }
            return false;
        }
        catch(Exception e){
            e.printStackTrace();
            throw new RemoteException("Erro ao deletar arquivo.", e);
        }
    }

    public boolean createFolder(String folderName){
        try{
            String[] folders = folderName.split(":\\\\");

            if(folders.length == 2){        // Passado caminho inteiro desde a raiz
                new File(rootFolderPath + folders[1]).mkdirs();
            }
            else{
                new File(currentFolder, folderName).mkdirs();
            }
            return true;
        }
        catch(Exception e){
            e.printStackTrace();
            return false;
        }
    }

    public boolean inFolder(String folderPath){
        try{
            String[] folders = folderPath.split(":\\\\");

            File folder;

            if(folders.length == 2){                                // Passado caminho inteiro desde a raiz
                folder = new File(rootFolderPath + folders[1]);
            }
            else{
                folder = new File(currentFolder, folderPath);
            }

            
            if(folder.exists()){
                currentFolder = folder;
            }
            else{
                return false;
            }
            
            return true;
        }
        catch(Exception e){
            e.printStackTrace();
            return false;
        }
    }

    public boolean backFolder(){
        try{
            File parent = currentFolder.getParentFile();

            if(parent != null && parent.getCanonicalPath().startsWith(rootFolder.getCanonicalPath())){
                currentFolder = parent;
            }
            else{
                return false;
            }
            return true;
        }
        catch(Exception e){
            e.printStackTrace();
            return false;
        }
    }

    public static void main(String[] args){
        try{
            
            System.setProperty("java.rmi.server.hostname", "26.21.150.179");
            server obj = new server();
            functions stub = (functions) UnicastRemoteObject.exportObject((obj), 1100);
            Registry registry = LocateRegistry.createRegistry(1099);
            registry.rebind("server_functions", stub);
            System.out.println("registro: " + registry.toString());
            System.out.println("Servidor Pronto.");
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }
}

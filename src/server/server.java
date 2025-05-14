package server;

import java.rmi.RemoteException;
import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

import functions.functions;

public class server implements functions{

    private static final String folderPath = "S:\\Sistemas_Distribuidos\\rmi\\server\\";
    private Map<String, FileOutputStream> fileOutStreams = new HashMap<>();


    public String[] list(){
        File folder = new File(folderPath);
        File[] files = folder.listFiles();

        String[] result = new String[files.length];

        for(int i = 0; i < files.length; i++){
            if(files[i].isFile()){
                result[i] = "[ARQ] " + files[i].getName();
                System.out.println("Arq: " + files[i].getName());
            }
            else if(files[i].isDirectory()){
                result[i] = "[DIR] " + files[i].getName();
                System.out.println("Dir: " + files[i].getName());
            }
        }

        return result;
    }

    public void beginUpload(String fileName){
        try{
            FileOutputStream fos = new FileOutputStream(folderPath + fileName);
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
        try (RandomAccessFile file = new RandomAccessFile(folderPath+filePath, "r")){
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
        File file = new File(folderPath + filePath);
        return (long) file.length();
    }

    public boolean delete(String filePath) throws RemoteException{
        try{
            File file = new File(folderPath + filePath);
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

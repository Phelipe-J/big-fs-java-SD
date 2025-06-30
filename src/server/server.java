package server;

import java.rmi.RemoteException;
import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.management.monitor.Monitor;

import functions.functions;
import functions.monitor_interface;

public class server implements functions{

    private static final String basePath = "S:\\Sistemas_Distribuidos\\rmi\\server\\";

    private String rootFolderPath;
    private File rootFolder;
    private File currentFolder;

    private Map<String, FileOutputStream> fileOutStreams = new HashMap<>();
    private static int server_ID; 

    public server(int id){
        this.rootFolderPath = basePath+ id + File.separator;
        this.rootFolder = new File(this.rootFolderPath);
        this.currentFolder = this.rootFolder;

        if(!this.rootFolder.exists()){
            boolean created = this.rootFolder.mkdirs();

            if(created){
                System.out.println("Diretório do servidor [" + id + "] criado.");
            }
            else{
                System.err.println("ERRO: Diretório do servidor não foi possível de ser criado.");
            }
        }
    }

    // Controle do servidor
    public int getServer_ID() throws RemoteException {
        return server_ID;
    }

    private void startHeartbeatService(monitor_interface monitorStub) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Runnable heartbeatTask = () -> {
            try {
                File rootDir = new File(rootFolderPath);
                long totalSpace = rootDir.getTotalSpace();
                long freeSpace = rootDir.getFreeSpace();
                long usedSpace = totalSpace - freeSpace;

                float workload = 0.0f; // Placeholder for workload calculation

                serverInfo status = new serverInfo(server_ID, totalSpace, usedSpace, workload);
                status.setLastHeartbeatTimestamp(System.currentTimeMillis());

                monitorStub.sendHeartbeat(status);
                System.out.println("Heartbeat enviado: " + status);
            }
            catch (Exception e) {
                System.err.println("Ocorreu um erro inesperado na tarefa de heartbeat: " + e.getMessage());
            }
        };
        scheduler.scheduleAtFixedRate(heartbeatTask, 10, 300, TimeUnit.SECONDS);
    }



    // Requisições do cliente
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

    public void endUpload(String fileName, long fileSize){
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

    public boolean delete(String relativePath) throws RemoteException{
        try {
            File fileToDelete = new File(this.rootFolder, relativePath);

            if (!fileToDelete.exists()) {
                System.out.println("[Delete] Arquivo não encontrado para exclusão: " + fileToDelete.getAbsolutePath() + ". Considerado sucesso.");
                return true; // Se o arquivo já não existe, a operação teve o efeito desejado.
            }

            if (fileToDelete.delete()) {
                System.out.println("[Delete] Arquivo físico apagado: " + fileToDelete.getAbsolutePath());
                return true;
            }
            else {
                System.err.println("[Delete] Falha ao apagar o arquivo físico: " + fileToDelete.getAbsolutePath());
                return false;
            }
        }
        catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean createFolder(String relativePath){
        try{
            File newFolder = new File(this.rootFolder, relativePath);
            newFolder.mkdirs();

            if(newFolder.exists() && newFolder.isDirectory()){
                return true;
            }
            else{
                System.err.println("Falha ao criar estrutura de diretório.");
                return false;
            }
        }
        catch(Exception e){
            e.printStackTrace();
            return false;
        }
    }

    public boolean inFolder(String folderPath){
        try{
            String[] folders = folderPath.split(":\\");

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
            if(args.length != 1){
                System.out.println("Uso: java server.server <ID do servidor (0-9)>");
                return;
            }

            server_ID = Integer.parseInt(args[0]);
            if(server_ID < 0 || server_ID > 9){
                System.out.println("ID do servidor deve ser entre 0 e 9.");
                return;
            }
            
            System.setProperty("java.rmi.server.hostname", "26.21.150.179");
            server obj = new server(server_ID);
            // Porta dinamica para testes na mesma máquina
            functions stub = (functions) UnicastRemoteObject.exportObject((obj), 1100 + server_ID); 
            //Registry registry = LocateRegistry.createRegistry(1099);
            Registry registry = LocateRegistry.getRegistry("26.21.150.179", 1099);
            registry.rebind("server_functions_" + server_ID, stub);

            monitor_interface monitor = (monitor_interface) registry.lookup("MonitorService");
            monitor.registerServer(obj, server_ID);

            obj.startHeartbeatService(monitor);

            //System.out.println("registro: " + registry.toString());
            System.out.println("Servidor registrado com ID: " + server_ID);
            System.out.println("Servidor Pronto.");
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }
}

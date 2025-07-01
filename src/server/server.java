package server;

import java.rmi.RemoteException;
import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import functions.monitorServices;
import functions.serverServices;

public class server implements serverServices{

    private static final String basePath = "S:\\Sistemas_Distribuidos\\rmi\\server\\";
    private static final String MANIFEST_FILE = "manifest.dat";

    private Set<String> localFileManifest = ConcurrentHashMap.newKeySet();

    private String rootFolderPath;
    private File rootFolder;

    private Map<String, FileOutputStream> fileOutStreams = new HashMap<>();
    private static int server_ID; 

    public server(int id){
        this.rootFolderPath = basePath+ id + File.separator;
        this.rootFolder = new File(this.rootFolderPath);

        if(!this.rootFolder.exists()){
            boolean created = this.rootFolder.mkdirs();

            if(created){
                System.out.println("Diretório do servidor [" + id + "] criado.");
            }
            else{
                System.err.println("ERRO: Diretório do servidor não foi possível de ser criado.");
            }
        }

        loadManifest();
    }

    // Controle do servidor
    public int getServerID() throws RemoteException {
        return server_ID;
    }

    private void startHeartbeatService(monitorServices monitorStub) {
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

    public Set<String> getLocalFileManifest(){
        return this.localFileManifest;
    }

    private void saveManifest(){
        try (FileOutputStream fos = new FileOutputStream(rootFolderPath + MANIFEST_FILE); ObjectOutputStream oos = new ObjectOutputStream(fos)){
            oos.writeObject(this.localFileManifest);
        }
        catch(Exception e){
            System.err.println("Erro ao salvar o manifesto.");
            e.printStackTrace();
        }
    }

    private void loadManifest(){
        File manifestFile = new File(rootFolderPath + MANIFEST_FILE);
        if(manifestFile.exists()){
            try (FileInputStream fis = new FileInputStream(manifestFile); ObjectInputStream ois = new ObjectInputStream(fis)){
                this.localFileManifest = (Set<String>) ois.readObject();
            }
            catch(Exception e){
                System.err.println("Erro ao carregar o manifesto.");
                e.printStackTrace();
            }
        }
        
    }

    // Requisições do cliente

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
                localFileManifest.add(fileName);
                saveManifest();
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

    public boolean delete(String relativePath) throws RemoteException{
        try {
            File fileToDelete = new File(this.rootFolder, relativePath);

            if (!fileToDelete.exists()) {
                System.out.println("[Delete] Arquivo não encontrado para exclusão: " + fileToDelete.getAbsolutePath() + ". Considerado sucesso.");
                localFileManifest.remove(relativePath);
                saveManifest();
                return true; // Se o arquivo já não existe, a operação teve o efeito desejado.
            }

            if (fileToDelete.delete()) {
                System.out.println("[Delete] Arquivo físico apagado: " + fileToDelete.getAbsolutePath());
                localFileManifest.remove(relativePath);
                saveManifest();
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

    // Operações entre servidores
    public void copyFileToPeer(String path, serverServices destinationPeer) throws RemoteException{
        File fileToCopy = new File(this.rootFolder, path);
        if(!fileToCopy.exists()){
            throw new RemoteException("Arquivo de origem pra replicação não encontrado");
        }

        try(FileInputStream in = new FileInputStream(fileToCopy)){
            destinationPeer.beginUpload(path);

            byte[] buffer = new byte[4096];
            int bytesRead;
            while((bytesRead = in.read(buffer)) != -1){
                destinationPeer.uploadBlock(path, buffer, bytesRead);
            }

            destinationPeer.endUpload(path);

            System.out.println("Cópia de " + path + " para " + destinationPeer.getServerID() + " concluida.");
        }
        catch(Exception e){
            throw new RemoteException("Falha durante a cópia.");
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
            serverServices stub = (serverServices) UnicastRemoteObject.exportObject((obj), 1100 + server_ID); 
            //Registry registry = LocateRegistry.createRegistry(1099);
            Registry registry = LocateRegistry.getRegistry("26.21.150.179", 1099);
            registry.rebind("server_functions_" + server_ID, stub);

            monitorServices monitor = (monitorServices) registry.lookup("MonitorService");
            monitor.registerServer(stub, server_ID, obj.getLocalFileManifest());

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

package server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InvalidClassException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import functions.file;
import functions.folder;
import functions.functions;
import functions.monitor_interface;
import server.server;

public class monitor implements functions, monitor_interface {
    
    private static final double WEIGHT_SPACE = 0.7; // Peso para o espaço livre
    private static final double WEIGHT_WORKLOAD = 0.3; // Peso para a carga de trabalho

    private static final int REPLICATION_FACTOR = 3; // Fator de replicação

    private static final int MAX_RETRIES = 10; // Número máximo de tentativas para operações
    private static final long RETRY_INTERVAL_MS = 1000; // Tempo de espera entre tentativas em milissegundos

    private static final String PERSISTENCE_FILE = "S:\\Sistemas_Distribuidos\\rmi\\monitor\\monitor_fs.dat";
    private static folder fileSystemRoot = new folder("root", "", null);
    private static folder currentClientDirectory;

    private static Map<Integer, functions> serverStubs = new HashMap<>();
    private static Map<Integer, serverInfo> serverStatusMap = new HashMap<>();

    private static Map<String, List<functions>> uploadSessions = new HashMap<>();

    public monitor() {
        loadState();    // Carrega registros de arquivos do arquivo de estados
        currentClientDirectory = fileSystemRoot;
        startHeartbeatMonitor();    // Inicia o monitoramento de heartbeat dos servidores
    }

    // Monitor Methods

    public void registerServer(functions server, int ID) throws RemoteException {
        serverStubs.put(ID, server);
        System.out.println("Servidor registrado: " + ID);
    }

    public void sendHeartbeat(serverInfo status) throws RemoteException {
        int serverId = status.getServer_ID();

        if(serverStubs.containsKey(serverId)) {
            serverStatusMap.put(serverId, status);
            System.out.println("Heartbeat recebido do servidor: " + serverId);
        }
        else{
            System.err.println("Servidor com ID []" + serverId + "] não está registrado.");
        }
    }

    private List<functions> selectBestServers(int count, List<Integer> candidateIds) throws RemoteException{
        List<serverInfo> availableCandidates = new ArrayList<>();

        for(int id : candidateIds){
            serverInfo status = serverStatusMap.get(id);
            if(status != null && status.isAvailable()){
                availableCandidates.add(status);
            }
        }

        if(availableCandidates.size() < count){
            throw new RemoteException("Não há servidores disponíveis suficientes para atender a requisição.");
        }

        availableCandidates.sort(Comparator.comparingDouble(this::calculateServerScore).reversed());

        return availableCandidates.stream()
                .limit(count)
                .map(serverInfo -> serverStubs.get(serverInfo.getServer_ID()))
                .collect(Collectors.toList());
    }

    private List<functions> selectBestServers(int count) throws RemoteException {   // Sobrecarga da função acima, para pesquisar entre todos os servidores
        List<Integer> allServerIds = new ArrayList<>(serverStatusMap.keySet());

        return selectBestServers(count, allServerIds);
    }

    private double calculateServerScore(serverInfo status) {
        long totalSpace = status.getTotalSpace();
        if(totalSpace <= 0){
            return 0.0; // Evita divisão por zero
        }

        
        double freeSpacePercentage = (double) (totalSpace - status.getUsedSpace()) / totalSpace;
        double workloadFactor = 1.0 - status.getWorkload();

        return (WEIGHT_SPACE * freeSpacePercentage) + (WEIGHT_WORKLOAD * workloadFactor);
    }

    private void startHeartbeatMonitor() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        Runnable heartbeatTask = () -> {
            long T_TIMEOUT = TimeUnit.MINUTES.toMillis(6); // 6 minutos
            long currentTime = System.currentTimeMillis();

            for(serverInfo status : serverStatusMap.values()) {
                if(status.isAvailable()){
                    if (currentTime - status.getLastHeartbeatTimestamp() > T_TIMEOUT) {
                        System.out.println("Servidor " + status.getServer_ID() + " está offline.");
                        status.setAvailable(false);
                    }
                }
            }
        };
        scheduler.scheduleAtFixedRate(heartbeatTask, 2, 2, TimeUnit.MINUTES);
    }

    @FunctionalInterface
    private interface RemoteAction {
        void execute() throws RemoteException;
    }

    private void executeWithRetry(RemoteAction action) throws RemoteException {
        RemoteException lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                action.execute();
                return; // Sucesso, sai do método.
            } catch (RemoteException e) {
                lastException = e;
                System.err.println("Tentativa " + (attempt + 1) + "/" + MAX_RETRIES + " falhou... Tentando novamente em " + RETRY_INTERVAL_MS + "ms.");
                try {
                    TimeUnit.MILLISECONDS.sleep(RETRY_INTERVAL_MS);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt(); // Restaura o status de interrupção
                    throw new RemoteException("Thread interrompida durante a espera para retentativa.", interruptedException);
                }
            }
        }
        throw new RemoteException("Ação falhou após " + MAX_RETRIES + " tentativas.", lastException);
    }

    public int getServer_ID(){
        throw new UnsupportedOperationException("Essa operação não deve ser chamada no monitor.");  // Monitor não é servidor, não tem ID, essa função não deve ser chamada
    }

    // Virtual File System Methods

    private void rebuildTransientLinks(folder rootFolder){
        if(rootFolder == null) return;

        Queue<folder> foldersToProcess = new LinkedList<>();

        foldersToProcess.add(rootFolder);

        while(!foldersToProcess.isEmpty()){
            folder currentFolder = foldersToProcess.poll();

            for(file f : currentFolder.getFiles()){
                f.setParentFolder(currentFolder);

                //System.out.println("[Transient] Arquivo " + f.getFileName() + " foi salvo com o pai " + currentFolder.getFolderName() + ".");
            }

            for(folder sub : currentFolder.getSubFolders()){
                foldersToProcess.add(sub);
            }
        }
    }

    private String[] getPathParts(String path){
        if (path == null || path.trim().isEmpty()){
            return new String[0]; // Retorna array vazio
        }

        return path.trim().split("[/\\\\]+");
    }

    private folder findFolderByPath(String path){
        folder curretFolder = fileSystemRoot;

        if(path == null || path.isEmpty()){
            return curretFolder;
        }

        String[] parts = path.split("[/\\\\]+");

        for(String part: parts){
            if(part.isEmpty()) continue;

            folder nextFolder = null;

            // Procura subpasta
            for(folder sub : curretFolder.getSubFolders()){
                if(sub.getFolderName().equalsIgnoreCase(part)){
                    nextFolder = sub;
                    break;
                }
            }

            // Cria subpasta
            if(nextFolder == null){
                String newPath = curretFolder.getFolderPath().isEmpty() ? part : curretFolder.getFolderPath() + "\\" + part;
                nextFolder = new folder(part, newPath, curretFolder);
                curretFolder.addSubFolder(nextFolder);
                System.out.println("Pasta lógica criada: " + newPath);
            }
        }
        return curretFolder;
    }

    private file findFileByPath(String path){

        String filePath = path.replace('/', '\\');
        String parentPath = "";
        String fileName = filePath;

        int lastSeparator = filePath.lastIndexOf('\\');
        if(lastSeparator > -1){
            parentPath = filePath.substring(0, lastSeparator);
            fileName = filePath.substring(lastSeparator + 1);
        }

        folder currentLevel = fileSystemRoot;
        String[] parts = parentPath.split("\\\\");

        if(!parentPath.isEmpty()){
            for(String part: parts){
                folder nextLevel = null;
                for(folder sub : currentLevel.getSubFolders()){
                    if(sub.getFolderName().equalsIgnoreCase(part)){
                        nextLevel = sub;
                        break;
                    }
                }
                if(nextLevel == null) return null; // Caminho não encontrado
                currentLevel = nextLevel;
            }
        }

        // Na pasta de destino
        for(file f: currentLevel.getFiles()){
            if(f.getFileName().equalsIgnoreCase(fileName)){
                return f;
            }
        }

        return null;
    }

    private void saveState(){
        try (FileOutputStream fos = new FileOutputStream(PERSISTENCE_FILE); ObjectOutputStream oos = new ObjectOutputStream(fos)){
            oos.writeObject(fileSystemRoot);
            System.out.println("Registro salvo.");
        }
        catch(Exception e){
            System.err.println("Erro ao salvar registro.");
        }
    }

    private void loadState() {
    File stateFile = new File(PERSISTENCE_FILE);
    if (stateFile.exists()) {
        try (FileInputStream fis = new FileInputStream(stateFile);
             ObjectInputStream ois = new ObjectInputStream(fis)) {
            
            fileSystemRoot = (folder) ois.readObject();
            rebuildTransientLinks(fileSystemRoot);
            System.out.println("Estado do sistema de arquivos carregado com sucesso de " + PERSISTENCE_FILE);

        } catch (InvalidClassException | ClassNotFoundException e) {
            // Erro específico quando a classe mudou ou não foi encontrada
            System.err.println("ERRO: O arquivo de estado '" + PERSISTENCE_FILE + "' é incompatível com a versão atual do programa.");
            System.err.println("Isso geralmente acontece após uma atualização de código. Apague o arquivo e reinicie o monitor.");
            System.err.println("Detalhes do erro: " + e.getMessage());
            // Inicia com um sistema de arquivos vazio para evitar travar
            fileSystemRoot = new folder("root", "", null);

        } catch (Exception e) {
            // Captura qualquer outro erro de leitura
            System.err.println("ERRO desconhecido ao ler o arquivo de estado.");
            e.printStackTrace();
            fileSystemRoot = new folder("root", "", null);
        }
    } else {
        System.out.println("Nenhum arquivo de estado encontrado. Iniciando com um sistema de arquivos novo.");
        saveState();
        fileSystemRoot = new folder("root", "", null);
    }
}


    // Server Methods

    public void beginUpload(String fileName) throws RemoteException{
        try {
            // Se já existe uma sessão para este arquivo, é um erro.
            if (uploadSessions.containsKey(fileName)) {
                throw new RemoteException("Já existe um upload em andamento para o arquivo: " + fileName);
            }

            List<functions> targetServers = selectBestServers(REPLICATION_FACTOR);
            
            System.out.println("Iniciando upload de '" + fileName + "'. Servidores de destino selecionados.");

            // Chama beginUpload em todo os servidores selecionados
            for (functions server : targetServers) {
                try{
                    executeWithRetry(() -> server.beginUpload(fileName));
                }
                catch (RemoteException e) {
                    System.err.println("Erro ao iniciar upload no servidor " + server + ": " + e.getMessage());
                    throw new RemoteException("Falha ao iniciar upload no servidor " + server + ": " + e.getMessage(), e);
                }
            }

            // Salva a lista de servidores na sessão de upload
            uploadSessions.put(fileName, targetServers);
            System.out.println("Sessão de upload criada para: " + fileName);

        } catch (RemoteException e) {
            System.err.println("Erro ao iniciar upload para " + fileName + ": " + e.getMessage());
            throw e;
        }
    }

    public void uploadBlock(String fileName, byte[] block, int length) throws RemoteException{
        // Recupera a lista de servidores da sessão
        List<functions> targetServers = uploadSessions.get(fileName);

        // Se não houver sessão, o cliente não chamou beginUpload. É um erro.
        if (targetServers == null || targetServers.isEmpty()) {
            throw new RemoteException("Sessão de upload não encontrada para o arquivo: " + fileName + ". Chame beginUpload primeiro.");
        }

        // Envia o bloco para todos os servidores da sessão
        for (functions server : targetServers) {
            try {
                executeWithRetry(() -> server.uploadBlock(fileName, block, length));
            } catch (RemoteException e) {
                System.err.println("Falha ao enviar bloco para um dos servidores durante o upload de " + fileName + ". " + e.getMessage());
            }
        }
    }

    public void endUpload(String fileName, long fileSize) throws RemoteException{
        // Recupera a lista de servidores da sessão
        List<functions> targetServers = uploadSessions.get(fileName);

        if (targetServers == null || targetServers.isEmpty()) {
            throw new RemoteException("Sessão de upload não encontrada para finalizar: " + fileName);
        }

        System.out.println("Finalizando upload para: " + fileName);
        
        // Envia o sinal de finalização para todos os servidores da sessão
        for (functions server : targetServers) {
            try{
                executeWithRetry(() -> server.endUpload(fileName, fileSize));
                System.out.println("Upload finalizado com sucesso no servidor: " + server);
            }
            catch (RemoteException e) {
                System.err.println("Erro ao finalizar upload no servidor " + server + ": " + e.getMessage());
                throw new RemoteException("Falha ao finalizar upload no servidor " + server + ": " + e.getMessage(), e);
            }
        }
        // Sistema de arquivo lógico
        try{
            List<Integer> replicaIds = new ArrayList<>();

            for(functions stub : targetServers){
                try{
                    replicaIds.add(stub.getServer_ID());
                }
                catch(RemoteException e){
                    System.err.println("Falha ao obter ID de um servidor.");
                }
            }

            String parentPath = "";
            String justFileName = fileName;

            int lastSeparator = fileName.lastIndexOf("\\");
            if(lastSeparator == -1){
                lastSeparator = fileName.lastIndexOf("/");
            }
            if(lastSeparator > -1){
                parentPath = fileName.substring(0, lastSeparator);
                justFileName = fileName.substring(lastSeparator + 1);
            }

            folder destinationFolder = findFolderByPath(parentPath);

            file newFileInfo = new file(justFileName, fileName, fileSize, replicaIds);
            destinationFolder.addFile(newFileInfo);

            System.out.println("Arquivo \"" + newFileInfo.getFileName() + "foi salvo em: " + newFileInfo.getParentFolder().getFolderName() + ".");

            saveState();

            System.out.println("Arquivo " + justFileName + " registrado no monitor em " + destinationFolder.getFolderPath() + " .");
        }
        catch(Exception e){
            System.err.println("Erro terrível: Servidores salvaram o arquivo, mas monitor não registrou.");
            e.printStackTrace();
        }

        // Remove a sessão do mapa para liberar memória
        uploadSessions.remove(fileName);
        System.out.println("Sessão de upload para '" + fileName + "' encerrada e limpa.");
    }

    public byte[] download(String filePath, long offset, int blockSize) throws RemoteException{
        file fileInfo = findFileByPath(filePath);

        if(fileInfo == null){
            throw new RemoteException("Arquivo não encontrado: " + filePath);
        }

        List<Integer> replicaServerIds = fileInfo.getReplicaServerIds();
        if(replicaServerIds == null || replicaServerIds.isEmpty()){
            throw new RemoteException("Nenhuma réplica encontrada para o arquivo: " + filePath + ". O arquivo está corrompido no sistema.");
        }

        List<functions> bestServerList = selectBestServers(1, replicaServerIds);

        if(bestServerList.isEmpty()){
            throw new RemoteException("Nenhum servidor com réplica do arquivo '" + filePath + "' está disponível no momento.");
        }

        functions bestServerStub = bestServerList.get(0);

        return bestServerStub.download(filePath, offset, blockSize);
    }

    public long downloadFileSize(String filePath) throws RemoteException{
        file fileInfo = findFileByPath(filePath);
        if(fileInfo != null){
            return fileInfo.getFileSize();
        }

        System.err.println("Arquivo não foi encontrado.");
        throw new RemoteException("Arquivo não encontrado: " + filePath);
    }

    public boolean isFolder(String filePath) throws RemoteException{
        throw new UnsupportedOperationException("Essa operação ainda não foi feita.");
    }

    public List<String> listFolderFiles(String folderPath) throws RemoteException{
        throw new UnsupportedOperationException("Essa operação ainda não foi feita.");
    }

    public boolean delete(String filePath) throws RemoteException{
        file fileToDelete = findFileByPath(filePath);

        System.out.println("Path dado:" + filePath);
        System.out.println("Arquivo encontrado: " + fileToDelete);

        if(fileToDelete == null){
            throw new RemoteException("Arquivo nao encontrado.");
        }

        List<Integer> replicaServerIds = fileToDelete.getReplicaServerIds();
        int sucessCount = 0;

        for(int serverId : replicaServerIds){
            functions serverStub = serverStubs.get(serverId);
            if(serverStub != null){
                try{
                    executeWithRetry(() -> serverStub.delete(fileToDelete.getFilePath()));
                    sucessCount++;
                }
                catch(Exception e){
                    System.err.println("Falha ao apagar arquivo no servidor [" + serverId + "]." + e.getMessage());
                }
            }
        }

        if(sucessCount > 0){
            folder parentFolder = fileToDelete.getParentFolder();
            parentFolder.getFiles().remove(fileToDelete);
        }

        saveState();
        return true;
    }

    public boolean createFolder(String folderPath) throws RemoteException{
        if (folderPath == null || folderPath.trim().isEmpty()) {
            System.err.println("Tentativa de criar pasta com nome vazio.");
            return false;
        }
    
        folder parentFolder = currentClientDirectory;
        folder targetFolder = null;

        String[] parts = getPathParts(folderPath);

        for (String part : parts) {
            if (part.isEmpty()) continue;

            // Procura se a subpasta já existe no nível atual (parentFolder)
            targetFolder = null;
            for (folder sub : parentFolder.getSubFolders()) {
                if (sub.getFolderName().equalsIgnoreCase(part)) {
                    targetFolder = sub;
                    break;
                }
            }

            // Se a pasta não existe, cria uma nova
            if (targetFolder == null) {
                String newPath = parentFolder.getFolderPath().isEmpty() ? part : parentFolder.getFolderPath() + "\\" + part;
                targetFolder = new folder(part, newPath, parentFolder);
                parentFolder.addSubFolder(targetFolder);
                //System.out.println("Pasta lógica criada: " + newPath);
            }
            parentFolder = targetFolder;
        }
    
        String finalPathToCreate = targetFolder.getFolderPath();
        int successCount = 0;

        if (serverStubs.isEmpty()) {
            System.out.println("Nenhum servidor conectado, criando apenas a pasta lógica.");
            successCount = 1;
        }
        else {
            for (functions serverStub : serverStubs.values()) {
                try {
                    executeWithRetry(() -> serverStub.createFolder(finalPathToCreate));
                    successCount++;
                } catch (Exception e) {
                    System.err.println("Falha ao criar pasta física em um dos servidores. " + e.getMessage());
                }
            }
        }

        if (successCount > 0) {
            saveState();
            return true;
        }
        else {
            System.err.println("ERRO: Nenhum servidor conseguiu criar a pasta física.");
            return false;
        }
    }

    public boolean inFolder(String folderName) throws RemoteException{
        for(folder sub : currentClientDirectory.getSubFolders()){
            if(sub.getFolderName().equalsIgnoreCase(folderName)){
                currentClientDirectory = sub;
                return true;
            }
        }
        System.out.println("Pasta não encontrada.");
        return false;
    }

    public boolean backFolder() throws RemoteException{
        if(currentClientDirectory.getParentFolder() != null){
            currentClientDirectory = currentClientDirectory.getParentFolder();
            return true;
        }
        System.out.println("Nao foi possivel voltar.");
        return false;
    }

    public String[] list() throws RemoteException {
        if(currentClientDirectory == null){
            return new String[]{"Erro: diretório atual não inicializado."};
        }

        List<folder> subFolders = currentClientDirectory.getSubFolders();
        List<file> files = currentClientDirectory.getFiles();

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

    public static void main(String[] args){

        try{
            System.setProperty("java.rmi.server.hostname", "26.21.150.179");

            monitor obj = new monitor();

            //functions clientStub = (functions) UnicastRemoteObject.exportObject((obj), 1101);
            //monitor_interface serverStub = (monitor_interface) UnicastRemoteObject.exportObject((obj), 1102);

            Remote remoteStub = UnicastRemoteObject.exportObject(obj, 1101);

            Registry registry = LocateRegistry.createRegistry(1099);
            
            registry.rebind("ClientService", remoteStub);
            registry.rebind("MonitorService", remoteStub);

            System.out.println("Monitor iniciado.");
        }
        catch(Exception e){
            System.out.println("Erro ao iniciar o monitor: " + e.getMessage());
            e.printStackTrace();
        }

    }
}

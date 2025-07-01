package server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.security.auth.login.LoginException;

import client.clientServices;
import functions.authenticationService;
import functions.file;
import functions.folder;
import functions.monitorServices;
import functions.serverServices;
import functions.user;

public class monitor implements monitorServices, authenticationService {
    
    private static final double WEIGHT_SPACE = 0.7; // Peso para o espaço livre
    private static final double WEIGHT_WORKLOAD = 0.3; // Peso para a carga de trabalho

    private static final int REPLICATION_FACTOR = 3; // Fator de replicação

    private static final int MAX_RETRIES = 10; // Número máximo de tentativas para operações
    private static final long RETRY_INTERVAL_MS = 1000; // Tempo de espera entre tentativas em milissegundos

    private static final String PERSISTENCE_FILES = "S:\\Sistemas_Distribuidos\\rmi\\monitor\\";
    private static final String USERS_DB_FILE = "S:\\Sistemas_Distribuidos\\rmi\\monitor\\db\\users.db.dat";
    private Map<String, user> userDatabase = new ConcurrentHashMap<>();
    private Map<String, user> loggedInUsers = new ConcurrentHashMap<>();

    private static Map<Integer, serverServices> serverStubs = new ConcurrentHashMap<>();
    private static Map<Integer, serverInfo> serverStatusMap = new ConcurrentHashMap<>();

    private static Map<String, List<serverServices>> uploadSessions = new ConcurrentHashMap<>();

    public monitor() {
        loadUsers();

        startHeartbeatMonitor();    // Inicia o monitoramento de heartbeat dos servidores
    }

    // Monitor Methods

    public void registerServer(serverServices server, int ID, Set<String> serverManifest) throws RemoteException {
        serverStubs.put(ID, server);
        System.out.println("Servidor registrado: " + ID);

        // Criação de pastas de usuário

        loadUsers();
        if(userDatabase != null && !userDatabase.isEmpty()){
            for(String username : userDatabase.keySet()){
                try{
                    server.createFolder(username);
                }
                catch(Exception e){
                    System.err.println("Falha ao criar pasta para o usuário: " + username);
                }
            }
        }

        synchronizeServerState(ID, server, serverManifest);
    }

    private void synchronizeServerState(int serverId, serverServices serverStub, Set<String> serverManifest){
        //TODO: Fazer sistema de comparar arquivos e corrigir inconsistências
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

    private List<serverServices> selectBestServers(int count, List<Integer> candidateIds) throws RemoteException{
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

    private List<serverServices> selectBestServers(int count) throws RemoteException {   // Sobrecarga da função acima, para pesquisar entre todos os servidores
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

                        initiateReplicaCorrection(status.getServer_ID());
                    }
                }
            }
        };
        scheduler.scheduleAtFixedRate(heartbeatTask, 2, 2, TimeUnit.MINUTES);
    }

    private void initiateReplicaCorrection(int deadServerId){
        //TODO: implementar lógica para cópia de réplicas
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

    private folder findFolderByPath(folder startingFolder, String path){
        if(path == null || path.trim().isEmpty()){
            return startingFolder;
        }

        folder currentLevel = startingFolder;
        String[] parts = getPathParts(path);

        for(String part: parts){
            folder nextLevel = null;
            for(folder sub : currentLevel.getSubFolders()){
                if(sub.getFolderName().equalsIgnoreCase(part)){
                    nextLevel = sub;
                    break;
                }
            }
            if(nextLevel == null) return null; // Pasta não encontrada
        }
        return currentLevel;
    }

    private file findFileByPath(folder userRoot, String path){

        String filePath = path.replace('/', '\\');
        String parentPath = "";
        String fileName = filePath;

        int lastSeparator = filePath.lastIndexOf('\\');
        if(lastSeparator > -1){
            parentPath = filePath.substring(0, lastSeparator);
            fileName = filePath.substring(lastSeparator + 1);
        }

        folder parentFolder = findFolderByPath(userRoot, parentPath);

        if(parentFolder == null){
            return null;
        }

        for(file f : parentFolder.getFiles()){
            if(f.getFileName().equalsIgnoreCase(fileName)){
                return f;
            }
        }

        return null;
    }

    // Client Methods

    private void saveUsers(){
        try (FileOutputStream fos = new FileOutputStream(USERS_DB_FILE); ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(userDatabase);
        }
        catch(Exception e){
            System.err.println("Erro ao salvar usuários");
            e.printStackTrace();
        }
    }

    private void loadUsers(){
        File dbFile = new File(USERS_DB_FILE);

        if(dbFile.exists()){
            try (FileInputStream fis = new FileInputStream(dbFile); ObjectInputStream ois = new ObjectInputStream(fis)){
                this.userDatabase = (Map<String, user>) ois.readObject();
            }
            catch (Exception e){
                System.err.println("Erro ao carregar usuários");
                e.printStackTrace();
            }
        }
    }

    private void saveUserFileSystem(user aUser){
        try (FileOutputStream fos = new FileOutputStream(PERSISTENCE_FILES + aUser.getUsername() + ".dat"); ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(aUser.getRootDir());
            System.out.println("Registrodo usuário " + aUser.getUsername() + " salvo.");
        }
        catch(Exception e){
            System.err.println("Erro ao salvar arquivo de estado do usuário");
            e.printStackTrace();
        }
    }

    private folder loadUserFileSystem(user aUser){
        File userStateFile = new File(PERSISTENCE_FILES + aUser.getUsername() + ".dat");
        if(userStateFile.exists()){
            try (FileInputStream fis = new FileInputStream(userStateFile); ObjectInputStream ois = new ObjectInputStream(fis)) {
                folder userRoot = (folder) ois.readObject();
                rebuildTransientLinks(userRoot);
                System.out.println("Sistema de arquivos carregado do usuário: " + aUser.getUsername());
                return userRoot;
            }
            catch(Exception e){
                System.err.println("Erro ao carregar os arquivos do usuário: " + aUser.getUsername());
                e.printStackTrace();
                return null;
            }
        }
        return null; // arquivo não existe
    }

    public void registerUser(String username, String password){
        if(username == null || username.trim().isEmpty() || password == null || password.isEmpty()){
            throw new IllegalArgumentException("Usuario e/ou senha não podem ser vazios.");
        }

        if(userDatabase.containsKey(username)){
            throw new IllegalArgumentException("Este usuário já existe");
        }

        user newUser = new user(username, password);
        userDatabase.put(username, newUser);
        System.out.println("Novo usuario registrado: " + username);

        folder newUserRoot = new folder(newUser.getUsername(), "", null);
        newUser.setRootDir(newUserRoot);

        saveUsers();
        saveUserFileSystem(newUser);

        String userRootPath = newUser.getUsername();

        for(serverServices server : serverStubs.values()){
            try{
                server.createFolder(userRootPath);
            }
            catch(RemoteException e){
                System.err.println(("Falha ao criar pasta raiz de " + username + " em um dos servers"));
            }
        }
    }

    public clientServices login(String username, String password) throws LoginException, RemoteException{

        user registeredUser = userDatabase.get(username);

        if(registeredUser == null){
            throw new LoginException("Usuario não encontrado.");
        }
        if(!registeredUser.getPassword().equals(password)){
            throw new LoginException("Senha incorreta.");
        }

        folder userRoot = loadUserFileSystem(registeredUser);
        if(userRoot == null){
            System.out.println("isso não deveria acontecer de jeito nenhum");
            userRoot = new folder(username, "", null);
        }

        registeredUser.setRootDir(userRoot);
        loggedInUsers.put(username, registeredUser);

        return new clientSession(registeredUser, this);
    }

    // Server Methods

    public void beginUpload(String fileName, user aUser) throws RemoteException{
        try {
            // Se já existe uma sessão para este arquivo, é um erro.
            if (uploadSessions.containsKey(fileName)) {
                throw new RemoteException("Já existe um upload em andamento para o arquivo: " + fileName);
            }

            List<serverServices> targetServers = selectBestServers(REPLICATION_FACTOR);
            
            System.out.println("Iniciando upload de '" + fileName + "'. Servidores de destino selecionados.");

            String pathInServer = aUser.getUsername() + "\\" + fileName;

            // Chama beginUpload em todo os servidores selecionados
            for (serverServices server : targetServers) {
                try{
                    executeWithRetry(() -> server.beginUpload(pathInServer));
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

    public void uploadBlock(String fileName, byte[] block, int length, user aUser) throws RemoteException{
        // Recupera a lista de servidores da sessão
        List<serverServices> targetServers = uploadSessions.get(fileName);

        // Se não houver sessão, o cliente não chamou beginUpload. É um erro.
        if (targetServers == null || targetServers.isEmpty()) {
            throw new RemoteException("Sessão de upload não encontrada para o arquivo: " + fileName + ". Chame beginUpload primeiro.");
        }

        String pathInServer = aUser.getUsername() + "\\" + fileName;

        // Envia o bloco para todos os servidores da sessão
        for (serverServices server : targetServers) {
            try {
                executeWithRetry(() -> server.uploadBlock(pathInServer, block, length));
            } catch (RemoteException e) {
                System.err.println("Falha ao enviar bloco para um dos servidores durante o upload de " + fileName + ". " + e.getMessage());
            }
        }
    }

    public void endUpload(String fileName, long fileSize, user aUser) throws RemoteException{
        // Recupera a lista de servidores da sessão
        List<serverServices> targetServers = uploadSessions.get(fileName);

        if (targetServers == null || targetServers.isEmpty()) {
            throw new RemoteException("Sessão de upload não encontrada para finalizar: " + fileName);
        }

        System.out.println("Finalizando upload para: " + fileName);
        
        String pathInServer = aUser.getUsername() + "\\" + fileName;

        // Envia o sinal de finalização para todos os servidores da sessão
        for (serverServices server : targetServers) {
            try{
                executeWithRetry(() -> server.endUpload(pathInServer));
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

            for(serverServices stub : targetServers){
                try{
                    replicaIds.add(stub.getServerID());
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

            folder destinationFolder = findFolderByPath(aUser.getRootDir(), parentPath);

            file newFileInfo = new file(justFileName, fileName, fileSize, replicaIds);
            destinationFolder.addFile(newFileInfo);

            System.out.println("Arquivo \"" + newFileInfo.getFileName() + "foi salvo em: " + newFileInfo.getParentFolder().getFolderName() + ".");

            saveUserFileSystem(aUser);

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

    public byte[] download(String filePath, long offset, int blockSize, user aUser) throws RemoteException{
        file fileInfo = findFileByPath(aUser.getRootDir(), filePath);

        if(fileInfo == null){
            throw new RemoteException("Arquivo não encontrado: " + filePath);
        }

        List<Integer> replicaServerIds = fileInfo.getReplicaServerIds();
        if(replicaServerIds == null || replicaServerIds.isEmpty()){
            throw new RemoteException("Nenhuma réplica encontrada para o arquivo: " + filePath + ". O arquivo está corrompido no sistema.");
        }

        List<serverServices> bestServerList = selectBestServers(1, replicaServerIds);

        if(bestServerList.isEmpty()){
            throw new RemoteException("Nenhum servidor com réplica do arquivo '" + filePath + "' está disponível no momento.");
        }

        serverServices bestServerStub = bestServerList.get(0);

        String pathInServer = aUser.getUsername() + "\\" + filePath;
        return bestServerStub.download(pathInServer, offset, blockSize);
    }

    public long downloadFileSize(String filePath, user aUser) throws RemoteException{
        file fileInfo = findFileByPath(aUser.getRootDir(), filePath);
        if(fileInfo != null){
            return fileInfo.getFileSize();
        }

        System.err.println("Arquivo não foi encontrado.");
        throw new RemoteException("Arquivo não encontrado: " + filePath);
    }

    public boolean isFolder(String filePath, user aUser) throws RemoteException{
        throw new UnsupportedOperationException("Essa operação ainda não foi feita.");
    }

    public List<String> listFolderFiles(String folderPath, user aUser) throws RemoteException{
        throw new UnsupportedOperationException("Essa operação ainda não foi feita.");
    }

    public boolean delete(String filePath, user aUser) throws RemoteException{
        file fileToDelete = findFileByPath(aUser.getRootDir(), filePath);

        System.out.println("Path dado:" + filePath);
        System.out.println("Arquivo encontrado: " + fileToDelete);

        if(fileToDelete == null){
            throw new RemoteException("Arquivo nao encontrado.");
        }

        List<Integer> replicaServerIds = fileToDelete.getReplicaServerIds();
        int sucessCount = 0;

        String pathInServer = aUser.getUsername() + "\\" + fileToDelete.getFilePath();

        for(int serverId : replicaServerIds){
            serverServices serverStub = serverStubs.get(serverId);
            if(serverStub != null){
                try{
                    executeWithRetry(() -> serverStub.delete(pathInServer));
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

        saveUserFileSystem(aUser);
        return true;
    }

    public boolean createFolder(String folderPath, user aUser) throws RemoteException{
        if (folderPath == null || folderPath.trim().isEmpty()) {
            System.err.println("Tentativa de criar pasta com nome vazio.");
            return false;
        }
    
        folder parentFolder = aUser.getRootDir();
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
    
        String finalPathToCreate = aUser.getUsername() + "\\" + targetFolder.getFolderPath();
        int successCount = 0;

        if (serverStubs.isEmpty()) {
            System.out.println("Nenhum servidor conectado, criando apenas a pasta lógica.");
            successCount = 1;
        }
        else {
            for (serverServices serverStub : serverStubs.values()) {
                try {
                    executeWithRetry(() -> serverStub.createFolder(finalPathToCreate));
                    successCount++;
                } catch (Exception e) {
                    System.err.println("Falha ao criar pasta física em um dos servidores. " + e.getMessage());
                }
            }
        }

        if (successCount > 0) {
            saveUserFileSystem(aUser);
            return true;
        }
        else {
            System.err.println("ERRO: Nenhum servidor conseguiu criar a pasta física.");
            return false;
        }
    }

    public static void main(String[] args){

        try{
            System.setProperty("java.rmi.server.hostname", "26.21.150.179");

            monitor obj = new monitor();

            Remote remoteStub = UnicastRemoteObject.exportObject(obj, 1101);

            Registry registry = LocateRegistry.createRegistry(1099);
            
            registry.rebind("AuthService", remoteStub);
            registry.rebind("MonitorService", remoteStub);

            System.out.println("Monitor iniciado.");
        }
        catch(Exception e){
            System.out.println("Erro ao iniciar o monitor: " + e.getMessage());
            e.printStackTrace();
        }

    }
}

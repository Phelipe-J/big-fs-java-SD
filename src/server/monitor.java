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
import java.util.HashMap;
import java.util.HashSet;
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

    private Map<String, List<Integer>> buildMasterFileIndex(){
        Map<String, List<Integer>> masterIndex = new HashMap<>();
        File usersDir = new File(PERSISTENCE_FILES);
        File[] userFiles = usersDir.listFiles((dir, name) -> name.endsWith(".dat"));

        if (userFiles == null) return masterIndex;

        for (File userFile : userFiles) {
            String username = userFile.getName().replace(".dat", "");
            try (FileInputStream fis = new FileInputStream(userFile);
                ObjectInputStream ois = new ObjectInputStream(fis)) {
            
                folder userRoot = (folder) ois.readObject();
                // Inicia a varredura recursiva para este usuário
                traverseAndIndex(userRoot, username, masterIndex);
            } catch (Exception e) {
                System.err.println("Falha ao ler o arquivo de estado para o usuário " + username + " ao construir o índice mestre.");
            }
        }
        return masterIndex;
    }

    private void traverseAndIndex(folder currentFolder, String username, Map<String, List<Integer>> index){
        for(file f : currentFolder.getFiles()){
            String physicalPath = username + "\\" + f.getFilePath();
            index.put(physicalPath, f.getReplicaServerIds());
        }
        
        for(folder sub : currentFolder.getSubFolders()){
            traverseAndIndex(sub, username, index);
        }
    }

    private void synchronizeServerState(int serverId, serverServices serverStub, Set<String> serverManifest){
        Map<String, List<Integer>> masterIndex = buildMasterFileIndex();

        Set<String> monitorExpectedFiles = new HashSet<>();
        
        for (Map.Entry<String, List<Integer>> entry : masterIndex.entrySet()) {
            if (entry.getValue().contains(serverId)) {
                monitorExpectedFiles.add(entry.getKey());
            }
        }

        // Encontra arquivos pra apagar
        Set<String> filesToDelete = new HashSet<>(serverManifest);
        filesToDelete.removeAll(monitorExpectedFiles);

        if(!filesToDelete.isEmpty()){
            System.out.println("Servidor " + serverId + " tem " + filesToDelete.size() + " arquivos órfãos. Comandando exclusão...");
            for (String orphanFile : filesToDelete) {
                try {
                    System.out.println("  - Apagando órfão: " + orphanFile);
                    serverStub.delete(orphanFile);
                } catch (RemoteException e) {
                    System.err.println("Falha ao apagar arquivo órfão " + orphanFile + " no servidor " + serverId);
                }
            }
        }

        // Encontra arquivos pra replicar
        Set<String> filesToReplicate = new HashSet<>(monitorExpectedFiles);
        filesToReplicate.removeAll(serverManifest);
        if (!filesToReplicate.isEmpty()) {
            System.out.println("Servidor " + serverId + " está sem " + filesToReplicate.size() + " arquivos. Iniciando re-replicação...");
            for (String missingFile : filesToReplicate) {
                System.out.println("  - Replicando arquivo faltante: " + missingFile);
                List<Integer> replicas = masterIndex.get(missingFile);
            
                // Encontra uma fonte saudável para copiar o arquivo
                Integer sourceServerId = -1;
                for (Integer id : replicas) {
                    if (!id.equals(serverId) && serverStubs.containsKey(id)) {
                        sourceServerId = id;
                        break;
                    }
                }
            
                if (sourceServerId != -1) {
                    try {
                        serverServices sourceStub = serverStubs.get(sourceServerId);
                        sourceStub.copyFileToPeer(missingFile, serverStub);
                    }
                    catch (RemoteException e) {
                        System.err.println("Falha ao iniciar a cópia de " + missingFile + " da fonte " + sourceServerId);
                    }
                }
                else {
                    System.err.println("ERRO CRÍTICO: Não foi encontrada nenhuma fonte saudável para replicar o arquivo " + missingFile);
                }
            }
        }

        System.out.println("Sincronição do servidor " + serverId + " concluída.");
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
        Map<String, List<Integer>> masterIndex = buildMasterFileIndex();

        for(Map.Entry<String, List<Integer>> entry : masterIndex.entrySet()){
            String physicalPath = entry.getKey();
            List<Integer> replicaIds = entry.getValue();

            if(replicaIds.contains(deadServerId)){

                List<Integer> healthySourceIds = new ArrayList<>(replicaIds);
                healthySourceIds.remove(Integer.valueOf(deadServerId));

                if(healthySourceIds.isEmpty()){
                    System.err.println("Dados perdidos, todas as réplicas deixaram de existir.");
                    continue;
                }

                try{
                    serverServices sourceStub = serverStubs.get(healthySourceIds.get(0));

                    List<Integer> serversToExclude = new ArrayList<>(healthySourceIds);
                    serversToExclude.add(deadServerId);

                    List<Integer> allPossibleTargetIds = new ArrayList<>(serverStubs.keySet());
                    allPossibleTargetIds.removeAll(serversToExclude);

                    if(allPossibleTargetIds.isEmpty()){
                        System.err.println("Não há servidores disponíveis para criar as réplicas");
                        continue;
                    }

                    List<serverServices> bestNewServer = selectBestServers(1, allPossibleTargetIds);
                    serverServices destinantionStub = bestNewServer.get(0);
                    int destinationId = destinantionStub.getServerID();

                    sourceStub.copyFileToPeer(physicalPath, destinantionStub);
                    updateReplicaInfo(physicalPath, deadServerId, destinationId);
                }
                catch(Exception e){
                    System.err.println("Falha ao tentar replicar o arquivo.");
                    e.printStackTrace();
                }
            }
        }
    }

    private void updateReplicaInfo(String physicalPath, int oldId, int newId){
        String username = physicalPath.substring(0, physicalPath.indexOf('\\'));
        String logicalPath = physicalPath.substring(physicalPath.indexOf('\\') + 1);

        user aUser = new user(username, "");
        folder userRoot = loadUserFileSystem(aUser);

        if(userRoot != null){
            file fileToUpdate = findFileByPath(userRoot, logicalPath);
            if(fileToUpdate != null){
                fileToUpdate.getReplicaServerIds().remove(Integer.valueOf(oldId));
                fileToUpdate.getReplicaServerIds().add(Integer.valueOf(newId));
                aUser.setRootDir(userRoot);
                saveUserFileSystem(aUser);
            }
        }
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
            currentLevel = nextLevel;
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

    @SuppressWarnings("unchecked")
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
            System.out.println("Registro do usuário " + aUser.getUsername() + " salvo.");
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

            String parentPath = "";
            int lastSeparator = fileName.lastIndexOf('\\');
            if(lastSeparator > -1){
                parentPath = fileName.substring(0, lastSeparator);
            }

            folder destinationFolder = findFolderByPath(aUser.getRootDir(), parentPath);

            if(destinationFolder == null){
                throw new RemoteException("Diretorio não existe:" + parentPath);
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

        if (fileInfo == null) {
            throw new RemoteException("Arquivo não encontrado: " + filePath);
        }

        List<Integer> replicaServerIds = fileInfo.getReplicaServerIds();
        if (replicaServerIds == null || replicaServerIds.isEmpty()) {
            throw new RemoteException("Nenhuma réplica encontrada para o arquivo: " + filePath + ". O arquivo está corrompido no sistema.");
        }

        List<serverServices> availableReplicaStubs = selectBestServers(replicaServerIds.size(), replicaServerIds);

        if (availableReplicaStubs.isEmpty()) {
            throw new RemoteException("Nenhum servidor com réplica do arquivo '" + filePath + "' está disponível no momento.");
        }

        String pathInServer = aUser.getUsername() + "\\" + filePath;

        // Itera sobre cada servidor disponível, do melhor para o pior.
        for (serverServices serverStub : availableReplicaStubs) {
            try {
                // Tenta baixar o bloco do servidor atual.
                int serverId = serverStub.getServerID(); // Pega o ID para logs
            
                byte[] block = serverStub.download(pathInServer, offset, blockSize);
            
                return block;

            } catch (RemoteException e) {
                // Servidor falhou, vai ir pro próximo
                try {
                    System.err.println("AVISO: Falha ao baixar bloco do Servidor " + serverStub.getServerID() + ". Tentando o próximo servidor disponível...");
                } catch (RemoteException idEx) {
                    System.err.println("AVISO: Falha ao baixar bloco de um servidor que também falhou ao retornar seu ID. Tentando o próximo...");
                }
            }
        }

        // FALHA TOTAL: todos os servidores disponíveis falharam.
        throw new RemoteException("Todas as réplicas disponíveis para o arquivo '" + filePath + "' falharam ao entregar o bloco solicitado.");
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
        folder foundFolder = findFolderByPath(aUser.getRootDir(), filePath);

        return foundFolder != null;
    }

    private void recursiveFileSearch(folder currentFolder, String baseFolderPath, List<String> results){
        for(file f : currentFolder.getFiles()){
            String fullPath = f.getFilePath();
            String relativePath = fullPath.substring(baseFolderPath.length() + 1);
            results.add(relativePath);
        }

        for(folder sub : currentFolder.getSubFolders()){
            recursiveFileSearch(sub, baseFolderPath, results);
        }
    }

    public List<String> listFolderFiles(String folderPath, user aUser) throws RemoteException{
        List<String> allFilePaths = new ArrayList<>();

        folder startFolder = findFolderByPath(aUser.getRootDir(), folderPath);

        if(startFolder != null){
            recursiveFileSearch(startFolder, startFolder.getFolderPath(), allFilePaths);
        }
        else{
            throw new RemoteException("Pasta não encontrada: " + folderPath);
        }

        return allFilePaths;
    }

    public boolean delete(String logicalPath, user aUser) throws RemoteException {
        System.out.println("Usuário '" + aUser.getUsername() + "' solicitou apagar: " + logicalPath);
    
        // Verifica se o caminho é um arquivo
        file fileToDelete = findFileByPath(aUser.getRootDir(), logicalPath);
        if (fileToDelete != null) {
            return deleteSingleFile(fileToDelete, aUser);
        }
    
        // Se não for um arquivo, verifica se é uma pasta
        folder folderToDelete = findFolderByPath(aUser.getRootDir(), logicalPath);
        if (folderToDelete != null) {
            return deleteFolderRecursively(folderToDelete, aUser);
        }

        // Se não for nenhum dos dois, o caminho não existe.
        throw new RemoteException("Caminho não encontrado: " + logicalPath);
    }

    private boolean deleteSingleFile(file fileToDelete, user aUser) throws RemoteException {
        String physicalPath = aUser.getUsername() + "\\" + fileToDelete.getFilePath();
        List<Integer> replicaServerIds = fileToDelete.getReplicaServerIds();
        int successCount = 0;

        for (int serverId : replicaServerIds) {
            serverServices serverStub = serverStubs.get(serverId);
            if (serverStub != null) {
                try {
                    executeWithRetry(() -> serverStub.delete(physicalPath));
                    successCount++;
                }
                catch (Exception e) {
                    System.err.println("Falha ao apagar arquivo no servidor [" + serverId + "]: " + e.getMessage());
                }
            }
        }

        if (successCount >= replicaServerIds.size()) {
            // Remove da árvore lógica
            fileToDelete.getParentFolder().getFiles().remove(fileToDelete);
            System.out.println("[Delete] Arquivo '" + fileToDelete.getFileName() + "' removido da árvore lógica.");
            return true;
        }
        return false;
    }

    private boolean deleteFolderRecursively(folder folderToDelete, user aUser) throws RemoteException {
        List<folder> subfoldersCopy = new ArrayList<>(folderToDelete.getSubFolders());
        for (folder sub : subfoldersCopy) {
            deleteFolderRecursively(sub, aUser);
        }
        
        List<file> filesCopy = new ArrayList<>(folderToDelete.getFiles());
        for (file f : filesCopy) {
            deleteSingleFile(f, aUser);
        }
        
        String physicalPath = aUser.getUsername() + "\\" + folderToDelete.getFolderPath();
        System.out.println("[Delete] Comandando servidores para apagar o diretório físico: " + physicalPath);
        for(serverServices server : serverStubs.values()){
            try{
                server.delete(physicalPath);
            }
            catch(RemoteException e) {
                // Faz nada
            }
        }
        
        // Remove pasta lógica
        if (folderToDelete.getParentFolder() != null) {
            folderToDelete.getParentFolder().getSubFolders().remove(folderToDelete);
            System.out.println("[Delete] Pasta '" + folderToDelete.getFolderName() + "' removida da árvore lógica.");
        }

        saveUserFileSystem(aUser);
        return true;
    }

    public boolean createFolder(String fullLogicalPath, user aUser) throws RemoteException{
        String parentPath = "";
        String newFolderName = fullLogicalPath;

        int lastSeparator = fullLogicalPath.replace('/', '\\').lastIndexOf('\\');
        if (lastSeparator > -1) {
            parentPath = fullLogicalPath.substring(0, lastSeparator);
            newFolderName = fullLogicalPath.substring(lastSeparator + 1);
        }
    
        // Encontrar a pasta pai na árvore lógica. A pasta PAI deve existir.
        folder parentFolder = findFolderByPath(aUser.getRootDir(), parentPath);
        if (parentFolder == null) {
            throw new RemoteException("Caminho inválido: a pasta pai '" + parentPath + "' não existe.");
        }
    
        // Verificar se já existe uma pasta com o mesmo nome no destino.
        for (folder sub : parentFolder.getSubFolders()) {
            if (sub.getFolderName().equalsIgnoreCase(newFolderName)) {
                return true; // Se já existe, é considerado sucesso.
            }
        }
    
        folder newFolder = new folder(newFolderName, fullLogicalPath, parentFolder);
        parentFolder.addSubFolder(newFolder);

        // Comando para os servidores criarem a pasta física.
        String physicalPath = aUser.getUsername() + "\\" + fullLogicalPath;
        int successCount = 0;
    
        if (serverStubs.isEmpty()) {
            successCount = 1; // Permite a criação lógica mesmo sem servidores online
        } else {
            for (serverServices serverStub : serverStubs.values()) {
                try {
                    executeWithRetry(() -> serverStub.createFolder(physicalPath));
                    successCount++;
                } catch (Exception e) {
                    System.err.println("Falha ao criar pasta física em um dos servidores: " + e.getMessage());
                }
            }
    }

    // Se a criação física funcionar, salva o estado
    if (successCount > 0) {
        saveUserFileSystem(aUser);
        return true;
    } else {
        // Se nenhum servidor conseguiu criar, a pasta é removida do FS
        parentFolder.getSubFolders().remove(newFolder);
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

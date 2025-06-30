package client;

import functions.functions;

import java.rmi.registry.*;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.regex.*;
import java.util.*;

public class client{
    public static void list(functions stub){
        try{
            String[] files = stub.list();
            for(String print : files){
                System.out.println(print);
            }
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }

    public static void copy(functions stub, String sourcePath, String destinationPath){
        try{
            String[] addressPath = sourcePath.split(":\\\\");   // Separa a raiz do resto do endereço

            if(addressPath[0].equals("remote")){            // Checa se é a raiz remota
                if(stub.isFolder(addressPath[1])){
                    downloadFolder(stub, addressPath[1], destinationPath);
                    return;
                } 
                String localFilePath = destinationPath + "\\" + sourcePath.substring(sourcePath.lastIndexOf("\\")+1);   // Pega o endereço local e junta com o nome do arquivo
                download(stub, addressPath[1], localFilePath);
                return;
            }

            String[] remotePath = destinationPath.split(":\\\\", -1);       // Separa a raiz do resto do endereço

            File file = new File(sourcePath);
            if(file.isDirectory()){
                uploadFolder(stub, remotePath[1], file);
            }
            else{
                upload(stub, remotePath[1], sourcePath);
            }
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }

    public static void download(functions stub, String remotePath, String localPath){
        try(FileOutputStream fos = new FileOutputStream(localPath)){
            long fileSize = stub.downloadFileSize(remotePath);
            long offset = 0;

            while(offset < fileSize){
                byte[] block = stub.download(remotePath, offset, 4096);
                fos.write(block);
                offset += block.length;
            }

            System.out.println("Download Concluido.");
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }

    public static void downloadFolder(functions stub, String remotePath, String localPath){
        try{
            String downloadRootName = remotePath.substring(remotePath.lastIndexOf("\\")+1);
            File downloadRoot = new File(localPath + "\\" + downloadRootName);              // Criar a pasta raiz sendo baixada
            downloadRoot.mkdir();
            
            List<String> addressesStrings = stub.listFolderFiles(remotePath);

            ExecutorService executor = Executors.newFixedThreadPool(4);

            for(String relativePath : addressesStrings){
                executor.submit(() -> {
                    try{
                        File downloadFile = new File(downloadRoot, relativePath);
                        downloadFile.getParentFile().mkdirs();          // Cria as subpastas se não existirem pra cada arquivo.

                        String fullLocalPath = localPath + "\\" + downloadRootName + "\\" + relativePath;
                        String fullRemotePath = remotePath + "\\"  + relativePath;

                        download(stub, fullRemotePath, fullLocalPath);
                    }
                    catch(Exception e){
                        e.printStackTrace();
                    }
                });
            }

            executor.shutdown();
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }

    public static void upload(functions stub, String remotePath, String localPath){
        try{
            File file = new File(localPath);
            FileInputStream in = new FileInputStream(file);
            byte[] buffer = new byte[4096];
            int bytesRead = 0;

            String fullFilePath = remotePath + "\\" + file.getName();

            System.out.println("Full server file path: " + fullFilePath);

            stub.beginUpload(fullFilePath);

            while((bytesRead = in.read(buffer)) != -1){
                stub.uploadBlock(fullFilePath, buffer, bytesRead);
            }

            long fileSize = file.length();

            stub.endUpload(fullFilePath, fileSize);
            in.close();
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }

    public static void uploadFolder(functions stub, String remotePath, File folder){
        try{
            String newRemotePath = remotePath + "\\" + folder.getName();
            boolean folderResult = createFolder(stub, newRemotePath);
            if(folderResult == false){
                return;
            }

            File[] files = folder.listFiles();

            ExecutorService executor = Executors.newFixedThreadPool(4);

            for(File file : files){
                executor.submit(() -> {
                    try{
                        if(file.isFile()){
                            System.out.println("Caminho arquivo: " + file.getAbsolutePath());
                            upload(stub, newRemotePath, file.getAbsolutePath());
                        }
                        else if(file.isDirectory()){
                            uploadFolder(stub, newRemotePath, file);
                        }
                    }
                    catch(Exception e){
                        e.printStackTrace();
                    }
                });
            }
            executor.shutdown();
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        }
        catch(Exception e){
            e.printStackTrace();
        }

    }

    public static void delete(functions stub, String filePath){
        try{
            boolean result = stub.delete(filePath);
            if(result == true){
                System.out.println("Arquivo excluido com sucesso.");
            }
            else{
                System.out.println("Erro ao deletar arquivo.");
            }
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }

    public static boolean createFolder(functions stub, String folderName){
        try{
            System.out.println("Criar pasta em: " + folderName);
            boolean result = stub.createFolder(folderName);

            if(result == true){
                System.out.println("Pasta criada com sucesso.");
            }
            else{
                System.out.println("Erro ao criar pasta.");
            }
            return result;
        }
        catch(Exception e){
            e.printStackTrace();
            return false;
        }
    }

    public static void backFolder(functions stub){
        try{
            stub.backFolder();
            list(stub);
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }

    public static void inFolder(functions stub, String folderPath){
        try{
            stub.inFolder(folderPath);
            list(stub);
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }

    public static void help(){
        System.out.println("Comandos disponiveis:");
        System.out.println("copy <origem> <destino>");
        System.out.println("Copia um arquivo para o servidor remoto, ou do servidor remoto\n");

        System.out.println("list");
        System.out.println("lista os arquivos do diretorio atual no servidor.\n");

        System.out.println("delete <caminho do arquivo>  ||  delete <nome do arquivo>");
        System.out.println("Deleta o arquivo no diretorio atual com o nome dado, ou deleta o arquivo no caminho dado.\n");

        System.out.println("newFolder <caminho da nova pasta>  ||  newFolder <nome da nova pasta>");
        System.out.println("Cria uma nova pasta no diretorio atual, ou na caminho dado.\n");

        System.out.println("enter <caminho da pasta>   ||   enter <nome da pasta>");
        System.out.println("Navega para dentro da pasta especificada.\n");

        System.out.println("back");
        System.out.println("Retorna para a pasta anterior.\n");

        System.out.println("help");
        System.out.println("Mostra essa mensagem.\n");

        System.out.println("leave");
        System.out.println("Encerra o programa.\n");
    }

    private static void mainLoop(functions stub){
        try{
            Scanner input = new Scanner(System.in);
        
            loop: while (true) {
                
                System.out.print("> ");

                String inputString = input.nextLine();
                List<String> inputParts = new ArrayList<>();
                Matcher matcher = Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(inputString);

                while(matcher.find()){
                    if(matcher.group(1) != null){
                        inputParts.add(matcher.group(1));   // Entre aspas
                    }
                    else{
                        inputParts.add(matcher.group(2));   // sem aspas
                    }
                }

                if(inputParts.isEmpty()) continue;

                String command = inputParts.get(0);

                switch (command) {
                    case "list":
                        list(stub);
                        break;
                    
                    case "copy":
                        if(inputParts.size() == 3){
                            copy(stub, inputParts.get(1), inputParts.get(2));
                        }
                        else{
                            System.out.println("Formato do comando: copy <origem> <destino>");
                        }
                        break;

                    case "delete":
                        if(inputParts.size() == 2){
                            delete(stub, inputParts.get(1));
                        }
                        else{
                            System.out.println("Formato do comando: delete <arquivo>");
                        }
                        break;

                    case "newFolder":
                        if(inputParts.size() == 2){
                            createFolder(stub, inputParts.get(1));
                        }
                        else{
                            System.out.println("Formato do comando: newFolder <nome>");
                        }
                    break;

                    case "enter":
                        if(inputParts.size() == 2){
                            inFolder(stub, inputParts.get(1));
                        }
                        else{
                            System.out.println("Formato do comando: enter <nome>");
                        }
                    break;

                    case "back":
                        backFolder(stub);
                    break;

                    case "help":
                        help();
                    break;

                    case "leave":
                        break loop;
                    default:
                        System.out.println("Comando invalido.");
                        break;
                }
            }

            input.close();
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }

    public static void main(String[] args){
        try{
            
            Registry registry = LocateRegistry.getRegistry("26.21.150.179", 1099);
            functions stub = (functions) registry.lookup("ClientService");
            
            mainLoop(stub);
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }
}

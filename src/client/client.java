package client;

import functions.functions;

import java.rmi.registry.*;
import java.util.Scanner;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

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
            String[] addressPath = sourcePath.split(":");   // Separa a raiz do resto do endereço

            //System.out.println("SourcePath: " + sourcePath);

            if(addressPath[0].equals("remote")){            // Checa se é a raiz remota
                String localFilePath = destinationPath + "\\" + sourcePath.substring(sourcePath.lastIndexOf("\\")+1);   // Pega o endereço local e junta com o nome do arquivo
                download(stub, addressPath[1], localFilePath);
                return;
            }

            String[] remotePath = destinationPath.split(":");       // Separa a raiz do resto do endereço

            upload(stub, remotePath[1], sourcePath);
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

    public static void upload(functions stub, String remotePath, String localPath){
        try{
            File file = new File(localPath);
            FileInputStream in = new FileInputStream(file);
            byte[] buffer = new byte[4096];
            int bytesRead = 0;

            String fullFilePath = remotePath + "\\" + file.getName();

            System.out.println("Full file path: " + fullFilePath);

            stub.beginUpload(fullFilePath);

            while((bytesRead = in.read(buffer)) != -1){
                stub.uploadBlock(fullFilePath, buffer, bytesRead);
            }

            stub.endUpload(fullFilePath);
            in.close();
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }

    public static void delete(functions stub, String filePath){
        try{
            String[] address = filePath.split(":");

            boolean result = stub.delete(address[1]);
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

    private static void mainLoop(functions stub){
        try{
            Scanner input = new Scanner(System.in);
        
            loop: while (true) {
                String inputString = input.nextLine();
                String[] inputParts = inputString.split(" ", 3); // comando - origem - destino

                switch (inputParts[0]) {
                    case "list":
                        list(stub);
                        break;
                    
                    case "copy":
                        copy(stub, inputParts[1], inputParts[2]);
                        break;

                    case "delete":
                        delete(stub, inputParts[1]);
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
            functions stub = (functions) registry.lookup("server_functions");
            
            mainLoop(stub);
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }
}

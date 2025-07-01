package functions;

import java.io.Serializable;
import java.util.List;

public class file implements Serializable {
    
    private String fileName;
    private String filePath;
    private long fileSize;

    //private int blockAmount;
    private List<Integer> replicaServerIds;

    private transient folder parentFolder;

    public file(String fileName, String filePath, long fileSize, List<Integer> replicaServerIds) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileSize = fileSize;

        //this.blockAmount = blockAmount;
        this.replicaServerIds = replicaServerIds;
    }

    public String getFileName() {
        return fileName;
    }

    public String getFilePath() {
        return filePath;
    }
    
    public long getFileSize() {
        return fileSize;
    }

    /*
    public int getBlockAmount() {
        return blockAmount;
    }
    */

    public List<Integer> getReplicaServerIds() {
        return replicaServerIds;
    }

    public folder getParentFolder(){
        return parentFolder;
    }

    public void setParentFolder(folder parentFolder){
        this.parentFolder = parentFolder;
    }

    // TODO: Adicionar fragmentação de arquivos
    /*
    public void addBlockLocation(int index, int node) {
        if(replicaServerIds.get(index) == null){
            replicaServerIds.set(index, new ArrayList<>());
        }
        
        replicaServerIds.get(index).add(node);
    }
    */
}

package functions;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class folder implements Serializable {
    private String folderName;
    private String folderPath;
    private folder parentFolder;
    private List<folder> subFolders = new ArrayList<>();
    private List<file> files = new ArrayList<>();

    public folder(String folderName, String folderPath, folder parentFolder) {
        this.folderName = folderName;
        this.folderPath = folderPath;
        this.parentFolder = parentFolder;
    }

    public String getFolderName() {
        return folderName;
    }
    
    public String getFolderPath() {
        return folderPath;
    }

    public folder getParentFolder() {
        return parentFolder;
    }

    public List<folder> getSubFolders() {
        return subFolders;
    }

    public List<file> getFiles() {
        return files;
    }

    public void addSubFolder(folder subFolder) {
        subFolders.add(subFolder);
    }

    public void addFile(file newFile) {
        newFile.setParentFolder(this);
        files.add(newFile);
    }
}

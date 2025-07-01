package functions;

import java.io.Serializable;

public class user implements Serializable {
    private static final long serialVersionUID = 5L;
    private String username;
    private String password;
    private folder rootDir;

    public user(String username, String password){
        this.username = username;
        this.password = password;
    }

    public String getUsername(){
        return username;
    }

    public String getPassword(){
        return password;
    }

    public void setRootDir(folder root){
        this.rootDir = root;
    }

    public folder getRootDir(){
        return rootDir;
    }
}

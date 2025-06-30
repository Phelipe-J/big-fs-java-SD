package functions;

import java.io.Serializable;
import java.util.List;

public class user implements Serializable {
    private static final long seriaVersionUID = 5L;
    private String username;
    private String password;
    private folder rootDir;

    public user(String username, String password){
        this.username = username;
        this.password = password;

        this.rootDir = new folder(username, username, null);
    }

    public String getUsername(){
        return username;
    }

    public String getPassword(){
        return password;
    }

    public folder getRootDir(){
        return rootDir;
    }
}

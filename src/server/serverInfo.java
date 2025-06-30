package server;

import java.io.Serializable;

public class serverInfo implements Serializable {
    private int server_ID;
    private long totalSpace;
    private long usedSpace;
    private float workload;
    private long lastHeartbeatTimestamp;

    boolean isAvailable;

    public serverInfo(int server_ID, long totalSpace, long usedSpace, float workload) {
        this.server_ID = server_ID;
        this.totalSpace = totalSpace;
        this.usedSpace = usedSpace;
        this.workload = workload;
        this.isAvailable = true;
        this.lastHeartbeatTimestamp = System.currentTimeMillis();
    }

    public int getServer_ID() {
        return server_ID;
    }
    public long getTotalSpace() {
        return totalSpace;
    }
    public long getUsedSpace() {
        return usedSpace;
    }
    public float getWorkload() {
        return workload;
    }
    public boolean isAvailable() {
        return isAvailable;
    }
    public void setAvailable(boolean available) {
        isAvailable = available;
    }
    public long getLastHeartbeatTimestamp() {
        return lastHeartbeatTimestamp;
    }
    public void setLastHeartbeatTimestamp(long lastHeartbeatTimestamp) {
        this.lastHeartbeatTimestamp = lastHeartbeatTimestamp;
    }

    @Override
    public String toString() {
        return "ServerInfo [ID=" + server_ID + ", UsedSpace=" + (usedSpace / (1024*1024)) + "MB" +
               ", TotalSpace=" + (totalSpace / (1024*1024)) + "MB" + ", Workload=" + workload + 
               ", Available=" + isAvailable + "]";
    }
}

package functions;

import java.util.ArrayList;
import java.util.List;

public class fileBlock {
    
    public static final double MAX_BLOCK_SIZE = 1024 * 1024; // 1 MB in bytes


    public fileBlock(int blockSize, int index){
        this.blockSize = blockSize;
        this.index = index;
        this.block = new byte[blockSize];
        this.nodesUsed = new ArrayList<>();
    }

    private int index;
    private int blockSize;
    private byte[] block;
    private List<Integer> nodesUsed;


    public int getIndex() {
        return index;
    }

    public int getBlockSize() {
        return blockSize;
    }

    public byte[] getBlock() {
        return block;
    }

    public List<Integer> getNodesUsed() {
        return nodesUsed;
    }

    public void setBlock(byte[] block) {
        this.block = block;
    }

    public void addNodeUsed(int node) {
        this.nodesUsed.add(node);
    }
}
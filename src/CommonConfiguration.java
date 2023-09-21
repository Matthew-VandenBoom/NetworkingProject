public class CommonConfiguration {

    private final int numberOfPreferredNeighbors;
    private final int unchokingInterval;
    private final int optimisticUnchokingInterval;
    private final String fileName;
    private final long fileSize;
    private final long pieceSize;

    public CommonConfiguration(int numberOfPreferredNeighbors, int unchokingInterval,
                               int optimisticUnchokingInterval, String fileName, long fileSize, long pieceSize) {
        this.numberOfPreferredNeighbors = numberOfPreferredNeighbors;
        this.unchokingInterval = unchokingInterval;
        this.optimisticUnchokingInterval = optimisticUnchokingInterval;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.pieceSize = pieceSize;
    }


    public int getNumberOfPreferredNeighbors() {
        return numberOfPreferredNeighbors;
    }

    public int getUnchokingInterval() {
        return unchokingInterval;
    }

    public int getOptimisticUnchokingInterval() {
        return optimisticUnchokingInterval;
    }

    public String getFileName() {
        return fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public long getPieceSize() {
        return pieceSize;
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("# Prefered Neighbors: " + this.numberOfPreferredNeighbors + "\n");
        sb.append("Unchoking Interval: " + this.unchokingInterval + "\n");
        sb.append("Optimistic Unchoking: " + this.optimisticUnchokingInterval + "\n");
        sb.append("File Name: " + this.fileName + "\n");
        sb.append("File Size: " + this.fileSize + "\n");
        sb.append("Piece Size: " + this.pieceSize + "\n");
        return sb.toString();
    }
}

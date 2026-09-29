package com.moral.csv;

/**
 * 大表分片的字节区间 [start, end)。
 * 边界由 SplitPlanner 保证落在行首，因此每个区间都包含整数条完整记录。
 */
public final class ByteRange {

    private final long start;
    private final long end;
    private final int index;
    private final int total;

    public ByteRange(long start, long end, int index, int total) {
        this.start = start;
        this.end = end;
        this.index = index;
        this.total = total;
    }

    public long getStart() {
        return start;
    }

    public long getEnd() {
        return end;
    }

    public int getIndex() {
        return index;
    }

    public int getTotal() {
        return total;
    }

    public long size() {
        return Math.max(0, end - start);
    }

    @Override
    public String toString() {
        return "分片 " + (index + 1) + "/" + total + " [" + start + ", " + end + ")";
    }
}

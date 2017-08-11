package cache.mx.com.cachedemo.cache;

/**
 * 创建人： zhangmengxiong
 * 创建时间： 2017/8/10.
 * 联系方式: zmx_final@163.com
 */

class JournalBuild {
    static final int LENGTH = 24;
    int type = 0;
    long key = 0;
    int length = 0;
    long start = 0;

    JournalBuild(int type, long key, int length, long start) {
        this.type = type;
        this.key = key;
        this.length = length;
        this.start = start;
    }

    JournalBuild(int type, long key) {
        this.type = type;
        this.key = key;
    }

    JournalBuild() {
    }

    byte[] getBytes() {
        byte[] bytes = new byte[4 + 4 + 8 + 8];
        Utils.writeInt(bytes, 0, type);
        Utils.writeLong(bytes, 4, key);
        Utils.writeInt(bytes, 4 + 8, length);
        Utils.writeLong(bytes, 4 + 8 + 4, start);
        return bytes;
    }

    void fillBytes(byte[] bytes) {
        type = Utils.readInt(bytes, 0);
        key = Utils.readLong(bytes, 4);
        length = Utils.readInt(bytes, 4 + 8);
        start = Utils.readLong(bytes, 4 + 8 + 4);
    }
}

package cache.mx.com.cachedemo.cache;

/**
 * 创建人： zhangmengxiong
 * 创建时间： 2017/8/10.
 * 联系方式: zmx_final@163.com
 */

class ReadWriterBuild {
    static final int LENGTH = 16;

    MXCache.Entry entry;

    long key = 0;
    long time = 0;
    byte[] value = null;

    public ReadWriterBuild() {

    }

    public ReadWriterBuild(MXCache.Entry entry) {
        this.entry = entry;
        key = entry.getKey();
    }

    void readBytes(byte[] bytes) {
        key = Utils.readLong(bytes, 0);
        time = Utils.readLong(bytes, 8);
        value = Utils.readBytes(bytes, LENGTH, bytes.length - LENGTH);
    }


    byte[] getBytes() {
        byte[] bytes = new byte[LENGTH + value.length];
        Utils.writeLong(bytes, 0, key);
        Utils.writeLong(bytes, 8, time);
        System.arraycopy(value, 0, bytes, LENGTH, value.length);
        return bytes;
    }

    public int getLength() {
        return value.length + LENGTH;
    }
}

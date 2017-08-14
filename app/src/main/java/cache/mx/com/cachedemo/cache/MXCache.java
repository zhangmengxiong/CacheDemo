package cache.mx.com.cachedemo.cache;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 创建人： zhangmengxiong
 * 创建时间： 2017/8/10.
 * 联系方式: zmx_final@163.com
 */

class MXCache {
    private static final String JOURNAL_FILE = "journal.ids";
    private static final String CACHE_FILE = "journal.cache";
    private static final byte[] MAGIC = "com.zmx.MXCache".getBytes(Utils.UTF_8);

    private static final byte CLEAN = 21;
    private static final byte DIRTY = 23;
    private static final byte REMOVE = 25;
    private static final Object WRITE_SYNC = new Object(); // 写文件锁
    private static final byte[] BYTE_JOURNAL_HEAD = new byte[MAGIC.length + 4 + 8 + 8];
    private static final int HEAD_DATA_ITEM_LENGTH = 33;

    private final int version;
    private final int maxSize;
    private final long maxLength;
    private final File directory;
    private final File journalFile;
    private RandomAccessFile journalWriter;
    private FileChannel journalChannel;
    private MappedByteBuffer journalBuffer;
    private final File cacheFile;
    private final RandomAccessFile cacheRandomFile;
    private final AtomicInteger indexCount = new AtomicInteger(0);

    private final LinkedHashMap<Long, Integer> lruEntries = new LinkedHashMap<>(0, 0.75f, true);
    private final ExecutorService THREAD_POOL = Executors.newSingleThreadExecutor();

    MXCache(File directory, int version, int maxSize, long maxLength) throws IOException {
        this.version = version;
        this.directory = directory;
        this.journalFile = new File(directory, JOURNAL_FILE);
        if (!journalFile.exists()) {
            journalFile.getParentFile().mkdirs();
            journalFile.createNewFile();
        }
        this.journalWriter = new RandomAccessFile(journalFile, "rw");

        cacheFile = new File(directory, CACHE_FILE);
        if (!cacheFile.exists()) {
            cacheFile.getParentFile().mkdirs();
            cacheFile.createNewFile();
        }
        this.cacheRandomFile = new RandomAccessFile(cacheFile, "rw");
        this.maxSize = maxSize;
        this.maxLength = maxLength;

        initCache();
    }

    private void initCache() {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize <= 0");
        }
        if (journalFile.exists()) {
            try {
                readJournal();
//                processJournal();
                THREAD_POOL.submit(cacheRun);
                return;
            } catch (Exception journalIsCorrupt) {
                System.out.println("DiskLruCache " + directory + " is corrupt: " + journalIsCorrupt.getMessage() + ", removing");
                delete();
            }
        }

        rebuildJournal();
    }

    private void readJournal() throws Exception {
        BufferedInputStream bis = new BufferedInputStream(new FileInputStream(journalFile));
        try {
            byte[] bytes = BYTE_JOURNAL_HEAD;
            if ((bis.read(bytes)) != BYTE_JOURNAL_HEAD.length) {
                throw new IOException("unexpected journal header: [" + bytes + "]");
            }
            byte[] magic = Utils.readBytes(bytes, 0, MAGIC.length);
            int version = Utils.readInt(bytes, MAGIC.length);
            long maxsize = Utils.readLong(bytes, MAGIC.length + 4);
            long maxlength = Utils.readLong(bytes, MAGIC.length + 4 + 8);
            if (!Arrays.equals(magic, MAGIC) || version != this.version || maxlength != this.maxLength || maxsize != this.maxSize) {
                throw new IOException("unexpected journal header: [" + version + ", " + maxlength + "]");
            }

            if (journalWriter.length() != BYTE_JOURNAL_HEAD.length + maxSize * HEAD_DATA_ITEM_LENGTH) {
                throw new IOException("unexpected journal header size is error");
            }

            journalChannel = journalWriter.getChannel();
            journalBuffer = journalChannel.map(FileChannel.MapMode.READ_WRITE, 0, journalFile.length());
            journalBuffer.order(ByteOrder.LITTLE_ENDIAN);
        } finally {
            Utils.closeQuietly(bis);
        }
    }

    /**
     * 头文件布局：
     * MAGIC + version + maxSize + maxLength + lastFindIndex
     * key + status + length + time + seek
     */
    private void rebuildJournal() {
        try {
            journalWriter.seek(0);
            journalWriter.setLength(BYTE_JOURNAL_HEAD.length + maxSize * HEAD_DATA_ITEM_LENGTH);

            journalChannel = journalWriter.getChannel();
            journalBuffer = journalChannel.map(FileChannel.MapMode.READ_WRITE, 0, journalFile.length());
            journalBuffer.order(ByteOrder.LITTLE_ENDIAN);

            System.arraycopy(MAGIC, 0, BYTE_JOURNAL_HEAD, 0, MAGIC.length);
            Utils.writeInt(BYTE_JOURNAL_HEAD, MAGIC.length, version);
            Utils.writeLong(BYTE_JOURNAL_HEAD, MAGIC.length + 4, maxSize);
            Utils.writeLong(BYTE_JOURNAL_HEAD, MAGIC.length + 4 + 8, maxLength);

            journalBuffer.position(0);
            journalBuffer.put(BYTE_JOURNAL_HEAD);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Runnable cacheRun = new Runnable() {
        @Override
        public void run() {
            int start = BYTE_JOURNAL_HEAD.length;
            int position;
            indexCount.set(0);
            for (int i = 0; i < maxSize; i++) {
                position = start + i * HEAD_DATA_ITEM_LENGTH;
                byte status = journalBuffer.get(position + 8);
                if (status == DIRTY || status == CLEAN) {
                    long k = journalBuffer.getLong(position);
                    lruEntries.put(k, position);
                    indexCount.incrementAndGet();
                }
            }
        }
    };

    private void delete() {

    }

    void setString(String k, String value) {
        if (k == null) return;

        long key = Utils.getKey(k);
        synchronized (WRITE_SYNC) {
            Entry entry;
            try {
                entry = findOrNewEntry(key);
                if (entry == null) {
                    entry = new Entry(key);
                    entry.startIndex = findEmptyIndex();
                }
                entry.addIndex = indexCount.incrementAndGet();
                entry.writeValue(value.getBytes(Utils.UTF_8));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        checkOverFlow();
    }

    /**
     * 数据溢出判断
     */
    private void checkOverFlow() {

    }

    /**
     * 获取字符串时寻找字符串位置~
     *
     * @param key
     * @return
     */
    private Entry findEntry(long key) {
        Integer p = lruEntries.get(key);
        if (p != null) {
            long k = journalBuffer.getLong(p);
            byte status = journalBuffer.get(p + 8);
            int length = journalBuffer.getInt(p + 8 + 1);
            long time = journalBuffer.getLong(p + 8 + 1 + 4);
            long seek = journalBuffer.getLong(p + 8 + 1 + 4 + 8);
            int index = journalBuffer.getInt(p + 8 + 1 + 4 + 8 + 8);

            if (k == key) {
                Entry entry = new Entry(key);
                entry.startIndex = p;
                entry.length = length;
                entry.start = seek;
                entry.time = time;
                entry.status = status;
                entry.addIndex = index;
                return entry;
            }
        }

        int start = BYTE_JOURNAL_HEAD.length;
        int position;
        for (int i = 0; i < maxSize; i++) {
            position = start + i * HEAD_DATA_ITEM_LENGTH;
            long k = journalBuffer.getLong(position);
            byte status = journalBuffer.get(position + 8);
            int length = journalBuffer.getInt(position + 8 + 1);
            long time = journalBuffer.getLong(position + 8 + 1 + 4);
            long seek = journalBuffer.getLong(position + 8 + 1 + 4 + 8);
            int index = journalBuffer.getInt(position + 8 + 1 + 4 + 8 + 8);

            if (k == key) {
                Entry entry = new Entry(key);
                entry.startIndex = position;
                entry.length = length;
                entry.start = seek;
                entry.time = time;
                entry.status = status;
                entry.addIndex = index;

                lruEntries.remove(key);
                lruEntries.put(key, position);
                return entry;
            }
            if (status != DIRTY && status != CLEAN && status != REMOVE) {
                break;
            }
        }
        return null;
    }

    private Entry findOrNewEntry(long key) {
        Entry entry = null;
        Integer p = lruEntries.get(key);
        if (p != null) {
            long k = journalBuffer.getLong(p);
            byte status = journalBuffer.get(p + 8);
            int length = journalBuffer.getInt(p + 8 + 1);
            long time = journalBuffer.getLong(p + 8 + 1 + 4);
            long seek = journalBuffer.getLong(p + 8 + 1 + 4 + 8);
            int index = journalBuffer.getInt(p + 8 + 1 + 4 + 8 + 8);

            if (k == key) {
                entry = new Entry(key);
                entry.startIndex = p;
                entry.length = length;
                entry.start = seek;
                entry.time = time;
                entry.status = status;
                entry.addIndex = index;
            }
        }

        if (entry == null) {
            int start = BYTE_JOURNAL_HEAD.length;
            int position;
            int emptyPosition = -1;
            for (int i = 0; i < maxSize; i++) {
                position = start + i * HEAD_DATA_ITEM_LENGTH;
                long k = journalBuffer.getLong(position);
                byte status = journalBuffer.get(position + 8);

                if (k == key) {
                    int length = journalBuffer.getInt(position + 8 + 1);
                    long time = journalBuffer.getLong(position + 8 + 1 + 4);
                    long seek = journalBuffer.getLong(position + 8 + 1 + 4 + 8);
                    int index = journalBuffer.getInt(position + 8 + 1 + 4 + 8 + 8);

                    entry = new Entry(key);
                    entry.startIndex = position;
                    entry.length = length;
                    entry.start = seek;
                    entry.time = time;
                    entry.status = status;
                    entry.addIndex = index;

                    lruEntries.remove(key);
                    lruEntries.put(key, position);
                    break;
                }
                if (status == REMOVE && emptyPosition < 0) {
                    emptyPosition = position;
                }

                if (status != DIRTY && status != CLEAN && status != REMOVE) {
                    entry = new Entry(key);
                    entry.startIndex = emptyPosition > 0 ? emptyPosition : position;
                    break;
                }
            }
        }
        return entry;
    }

    private int lastFindIndex = -1;

    private int findEmptyIndex() throws IOException {
        int start = BYTE_JOURNAL_HEAD.length;
        int position;
        int i = (--lastFindIndex) > 0 ? lastFindIndex : 0;

        Log.v("aa", "lastFindIndex = " + lastFindIndex);
        for (; i < maxSize; i++) {
            position = start + i * HEAD_DATA_ITEM_LENGTH;
            byte status = journalBuffer.get(position + 8);
            if ((status != DIRTY && status != CLEAN)) {
                lastFindIndex = i;
                return position;
            }
        }
        throw new IOException("没有找到可以存储的位置~");
    }

    public String getString(String k) {
        if (k == null) return null;

        long key = Utils.getKey(k);
        Entry entry = findEntry(key);
        if (entry == null) {
            return null;
        }

        if (entry.status != CLEAN) {
            return null;
        }
        try {
            byte[] bytes = entry.readValue();
            return bytes == null ? null : new String(bytes, Utils.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    class Entry {
        private final long key;

        /**
         * Lengths of this entry's files.
         */
        private int length;

        /**
         * 流的开始位置
         */
        private long start;

        long time;
        byte status = 0;
        int startIndex = -1;
        int addIndex = 0;

        private Entry(long key) {
            this.key = key;
            this.length = 0;
            this.start = 0;
        }

        void writeValue(byte[] bytes) throws IOException {
            journalBuffer.putLong(startIndex, key);
            journalBuffer.put(startIndex + 8, DIRTY);

            long newStart = cacheFile.length();
            if (start > 0 && length > 0 && bytes.length <= length) {
                newStart = start;
            }

            cacheRandomFile.seek(newStart);
            cacheRandomFile.write(bytes);

            journalBuffer.put(startIndex + 8, CLEAN);
            journalBuffer.putInt(startIndex + 8 + 1, bytes.length);
            journalBuffer.putLong(startIndex + 8 + 1 + 4, System.currentTimeMillis());
            journalBuffer.putLong(startIndex + 8 + 1 + 4 + 8, newStart);
            journalBuffer.putInt(startIndex + 8 + 1 + 4 + 8 + 8, addIndex);

            length = bytes.length;
            start = newStart;
        }

        long getKey() {
            return key;
        }

        byte[] readValue() throws Exception {
            RandomAccessFile file = null;
            byte[] bytes = new byte[length];
            try {
                file = new RandomAccessFile(cacheFile, "r");
                file.seek(start);
                if ((file.read(bytes)) == bytes.length) {
                    return bytes;
                }
                return null;
            } finally {
                Utils.closeQuietly(file);
            }
        }
    }
}

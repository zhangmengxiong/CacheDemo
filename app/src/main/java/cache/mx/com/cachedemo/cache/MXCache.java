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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 创建人： zhangmengxiong
 * 创建时间： 2017/8/10.
 * 联系方式: zmx_final@163.com
 */

class MXCache {
    private static final String TAG = MXCache.class.getSimpleName();

    private static final String JOURNAL_FILE = "journal.ids";
    private static final String CACHE_FILE = "journal.cache";
    private static final byte[] MAGIC = "com.zmx.MXCache".getBytes(Utils.UTF_8);

    static final byte CLEAN = 21;
    static final byte DIRTY = 23;
    static final byte REMOVE = 25;
    private static final Object WRITE_SYNC = new Object(); // 写文件锁
    private static final byte[] BYTE_JOURNAL_HEAD = new byte[MAGIC.length + 4 + 8 + 8];

    private final int version;
    private final int maxSize;
    private final int safeSize;
    private final long maxLength;
    private final File directory;
    private final File journalFile;
    private RandomAccessFile journalWriter;
    private FileChannel journalChannel;
    private MappedByteBuffer journalBuffer;
    private final File cacheFile;
    private final RandomAccessFile cacheRandomFile;
    private final AtomicInteger indexCount = new AtomicInteger(0);
    private final AtomicBoolean isRecycle = new AtomicBoolean(false);

    private final LinkedHashMap<Long, Integer> cacheEntry = new LinkedHashMap<>(0, 0.75f, true);
    private final ArrayList<Integer> emptyEntry = new ArrayList<>();
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
        this.maxSize = maxSize * 2;
        this.maxLength = maxLength;
        this.safeSize = maxSize;

        initCache();
    }

    private void initCache() {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize <= 0");
        }
        if (journalFile.exists()) {
            try {
                readJournal();
                processJournal();
//                THREAD_POOL.submit(cacheRun);

                Log.v(TAG, "init success !");
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

            if (journalWriter.length() != BYTE_JOURNAL_HEAD.length + maxSize * IndexBuild.LENGTH) {
                throw new IOException("unexpected journal header size is error");
            }

            journalChannel = journalWriter.getChannel();
            journalBuffer = journalChannel.map(FileChannel.MapMode.READ_WRITE, 0, journalFile.length());
            journalBuffer.order(ByteOrder.LITTLE_ENDIAN);
        } finally {
            Utils.closeQuietly(bis);
        }
    }

    private void processJournal() {
        int start = BYTE_JOURNAL_HEAD.length;
        int position;
        indexCount.set(0);
        emptyEntry.clear();
        cacheEntry.clear();
        for (int i = 0; i < maxSize; i++) {
            position = start + i * IndexBuild.LENGTH;
            byte status = IndexBuild.getStatus(journalBuffer, position);
            if (status == DIRTY || status == CLEAN) {
                long k = IndexBuild.getKey(journalBuffer, position);
                int index = IndexBuild.getOrderIndex(journalBuffer, position);
                cacheEntry.put(k, position);
                if (index > indexCount.get())
                    indexCount.set(index);
            } else {
                emptyEntry.add(position);
            }
        }
    }

    /**
     * 头文件布局：
     * MAGIC + version + maxSize + maxLength + lastFindIndex
     * key + status + length + insertTime + seek
     */
    private void rebuildJournal() {
        try {
            journalWriter.seek(0);
            journalWriter.setLength(BYTE_JOURNAL_HEAD.length + maxSize * IndexBuild.LENGTH);

            journalChannel = journalWriter.getChannel();
            journalBuffer = journalChannel.map(FileChannel.MapMode.READ_WRITE, 0, journalFile.length());
            journalBuffer.order(ByteOrder.LITTLE_ENDIAN);

            System.arraycopy(MAGIC, 0, BYTE_JOURNAL_HEAD, 0, MAGIC.length);
            Utils.writeInt(BYTE_JOURNAL_HEAD, MAGIC.length, version);
            Utils.writeLong(BYTE_JOURNAL_HEAD, MAGIC.length + 4, maxSize);
            Utils.writeLong(BYTE_JOURNAL_HEAD, MAGIC.length + 4 + 8, maxLength);

            journalBuffer.position(0);
            journalBuffer.put(BYTE_JOURNAL_HEAD);


            int start = BYTE_JOURNAL_HEAD.length;
            int position;
            for (int i = 0; i < maxSize; i++) {
                position = start + i * IndexBuild.LENGTH;
                emptyEntry.add(position);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Runnable cleanAndCheckRun = new Runnable() {
        @Override
        public void run() {
            isRecycle.set(true);
            try {
                int start = BYTE_JOURNAL_HEAD.length;
                int curIndex = indexCount.get();
                int position;
                Entry entry = new Entry();
                for (int i = 0; i < maxSize; i++) {
                    position = start + i * IndexBuild.LENGTH;
                    IndexBuild.fillEntry(journalBuffer, entry, position);
                    if (entry.isInUse() && Math.abs(curIndex - entry.sortIndex) > safeSize) {
//                        Log.v(TAG, "remove:" + entry.sortIndex);
                        remove(entry.key);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                isRecycle.set(false);
            }
        }
    };

    private void delete() {
    }

    void remove(String key) {
        remove(Utils.getKey(key));
    }

    private void remove(long key) {
        if (cacheEntry.containsKey(key)) {
            Integer position = cacheEntry.remove(key);
            if (position > 0) {
                IndexBuild.deleteKey(journalBuffer, position, REMOVE);
                emptyEntry.add(position);
            }
        }
    }

    void setString(String k, String value) {
        if (k == null) return;

        long key = Utils.getKey(k);
        synchronized (WRITE_SYNC) {
            Entry entry;
            try {
                entry = findOrNewEntryForInsert(key);
                if (entry == null) {
                    throw new IOException("没有找到空白的位置插入~");
                }
                entry.sortIndex = indexCount.incrementAndGet();
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
        if (cacheEntry.size() > safeSize && !isRecycle.get()) {
            Log.v(TAG, "缓存数量超过安全值");
            THREAD_POOL.submit(cleanAndCheckRun);
        }
    }

    /**
     * 获取字符串时寻找字符串位置~
     *
     * @param key
     * @return
     */
    private Entry findEntry(long key) {
        Integer p = cacheEntry.get(key);
        Entry entry = new Entry();
        if (p != null) {
            IndexBuild.fillEntry(journalBuffer, entry, p);
            if (entry.key == key) {
                return entry;
            }
        }

        int start = BYTE_JOURNAL_HEAD.length;
        int position;
        for (int i = 0; i < maxSize; i++) {
            position = start + i * IndexBuild.LENGTH;
            IndexBuild.fillEntry(journalBuffer, entry, position);

            if (entry.key == key) {
                cacheEntry.remove(key);
                cacheEntry.put(key, position);
                return entry;
            }
            if (entry.status != DIRTY && entry.status != CLEAN && entry.status != REMOVE) {
                break;
            }
        }
        return null;
    }

    /**
     * 寻找可以插入的Entry
     *
     * @param key
     * @return
     */
    private Entry findOrNewEntryForInsert(long key) {
        Integer p = cacheEntry.get(key);

        Entry tmp = new Entry();
        if (p != null) {
            IndexBuild.fillEntry(journalBuffer, tmp, p);
            if (tmp.key == key) {
                return tmp;
            }
        }
        if (emptyEntry.size() <= 0) return null;

        Entry entry = new Entry(key);
        entry.keyStartPosition = emptyEntry.remove(0);

        cacheEntry.remove(key);
        cacheEntry.put(key, entry.keyStartPosition);
        return entry;
    }

    String getString(String k) {
        if (k == null) return null;

        long key = Utils.getKey(k);
        Entry entry = findEntry(key);
        if (entry == null) {
            return null;
        }

        if (entry.status != CLEAN) {
            return null;
        }
//        Log.v(TAG, "find sort Index = " + entry.sortIndex);
        try {
            byte[] bytes = entry.readValue();
            return bytes == null ? null : new String(bytes, Utils.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    class Entry {
        /**
         * 唯一标识符
         */
        long key;

        /**
         * 内容的长度（byte[]长度）
         */
        int length;

        /**
         * 流在 .cache 中的开始位置
         */
        long valueStartPosition;

        /**
         * ids文件中的记录开始位置
         */
        int keyStartPosition = -1;

        /**
         * 时间点 System.currentTimeMillis()
         */
        long insertTime;

        /**
         * 状态 DIRTY  CLEAR  REMOVE
         */
        byte status = 0;

        /**
         * 插入顺序
         */
        int sortIndex = 0;

        private Entry(long key) {
            this.key = key;
            this.length = 0;
            this.valueStartPosition = 0;
        }

        Entry() {
        }

        boolean isInUse() {
            return status == DIRTY || status == CLEAN;
        }

        void writeValue(byte[] bytes) throws Exception {
            IndexBuild.editStart(journalBuffer, keyStartPosition, key, DIRTY);
            long newStart = cacheFile.length();
            if (valueStartPosition > 0 && length > 0 && bytes.length <= length) {
                Log.v(TAG, "new length = " + bytes.length + ",old length = " + length + ",convert old value~");
                newStart = valueStartPosition;
            } else {
                cacheRandomFile.setLength(newStart + bytes.length);
            }
            cacheRandomFile.seek(newStart);
            cacheRandomFile.write(bytes);

            IndexBuild.editEnd(journalBuffer, keyStartPosition,
                    key,
                    CLEAN,
                    bytes.length,
                    System.currentTimeMillis(),
                    newStart,
                    sortIndex);
            length = bytes.length;
            valueStartPosition = newStart;
        }

        byte[] readValue() throws Exception {
            RandomAccessFile file = null;
            byte[] bytes = new byte[length];
            try {
                file = new RandomAccessFile(cacheFile, "r");
                file.seek(valueStartPosition);
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

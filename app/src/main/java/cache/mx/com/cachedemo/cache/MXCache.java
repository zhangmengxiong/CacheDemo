package cache.mx.com.cachedemo.cache;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * 创建人： zhangmengxiong
 * 创建时间： 2017/8/10.
 * 联系方式: zmx_final@163.com
 */

public class MXCache {
    private static final String JOURNAL_FILE = "journal";
    private static final String CACHE_FILE = "journal.cache";
    private static final byte[] MAGIC = "com.zmx.MXCache".getBytes(Utils.UTF_8);

    private static final int CLEAN = 0x11;
    private static final int DIRTY = 0x22;
    private static final int REMOVE = 0x33;
    private static final Object WRITE_SYNC = new Object(); // 写文件锁
    private static final byte[] BYTE_JOURNAL_HEAD = new byte[MAGIC.length + 4 + 8 + 8];

    private final int version;
    private final File directory;
    private final File journalFile;
    private FileOutputStream journalWriter;
    private final File cacheFile;
    private final RandomAccessFile cacheRandomFile;
    private final long maxSize;
    private final long maxLength;

    private final LinkedHashMap<Long, Entry> lruEntries = new LinkedHashMap<>(0, 0.75f, true);

    public MXCache(File directory, int version, long maxSize, long maxLength) throws IOException {
        this.version = version;
        this.directory = directory;
        this.journalFile = new File(directory, JOURNAL_FILE);
        if (!journalFile.exists()) {
            journalFile.getParentFile().mkdirs();
            journalFile.createNewFile();
        }
        this.journalWriter = new FileOutputStream(journalFile, true);

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
                processJournal();
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

            bytes = new byte[JournalBuild.LENGTH];
            JournalBuild build = new JournalBuild();
            try {
                while (true) {
                    if (bis.read(bytes) != JournalBuild.LENGTH) {
                        throw new IOException("unexpected read index journal file");
                    }
                    build.fillBytes(bytes);
                    readJournalLine(build);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } finally {
            Utils.closeQuietly(bis);
        }
    }

    private void readJournalLine(JournalBuild build) throws IOException {
        Log.v("aaa", "read: " + build.type + " " + build.key + " " + build.length + " " + build.start);
        if (build.type == REMOVE) {
            lruEntries.remove(build.key);
            return;
        }

        Entry entry = lruEntries.get(build.key);
        if (entry == null) {
            entry = new Entry(build.key);
            lruEntries.put(build.key, entry);
        }

        if (build.type == DIRTY) {
            entry.currentWriter = new ReadWriterBuild();
        } else if (build.type == CLEAN) {
            entry.length = build.length;
            entry.start = build.start;
            entry.readable = true;
            entry.currentWriter = null;
        } else {
            throw new IOException("unexpected journal build: " + build);
        }
    }

    private void processJournal() throws Exception {
        for (Iterator<Entry> i = lruEntries.values().iterator(); i.hasNext(); ) {
            Entry entry = i.next();
            if (entry.currentWriter != null) {
                entry.currentWriter = null;
                i.remove();
            }
        }
    }

    private void rebuildJournal() {
        try {
            journalWriter.write(MAGIC);
            journalWriter.write(Utils.getBytes(version));
            journalWriter.write(Utils.getBytes(maxSize));
            journalWriter.write(Utils.getBytes(maxLength));

            for (Entry entry : lruEntries.values()) {
                if (entry.currentWriter != null) {
                    journalWriter.write(new JournalBuild(DIRTY, entry.key).getBytes());
                } else {
                    journalWriter.write(new JournalBuild(CLEAN, entry.key, entry.length, entry.start).getBytes());
                }
            }
            journalWriter.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void delete() {

    }

    public void setString(String k, String value) {
        if (k == null) return;

        long key = Utils.getKey(k);
        synchronized (WRITE_SYNC) {
            Entry entry = null;
            try {
                entry = lruEntries.get(key);
                boolean isNewKey = false;
                if (entry == null) {
                    entry = new Entry(key);
                    isNewKey = true;
                }
                entry.readable = false;
                entry.writeValue(value.getBytes(Utils.UTF_8));
                if (isNewKey) {
                    lruEntries.put(key, entry);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (entry != null) {
                    entry.readable = true;
                }
            }
        }
    }

    public String getString(String k) {
        if (k == null) return null;

        long key = Utils.getKey(k);
        Entry entry = lruEntries.get(key);
        if (entry == null) {
            return null;
        }

        if (!entry.readable) {
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

        /**
         * True if this entry has ever been published.
         */
        private boolean readable;

        /**
         * The ongoing edit or null if this entry is not being edited.
         */
        private ReadWriterBuild currentWriter;

        private Entry(long key) {
            this.key = key;
            this.length = 0;
            this.start = 0;
            readable = false;
            currentWriter = null;
        }

        void writeValue(byte[] bytes) throws IOException {
            journalWriter.write(new JournalBuild(DIRTY, key).getBytes());
            currentWriter = new ReadWriterBuild(this);
            currentWriter.time = System.currentTimeMillis();
            currentWriter.value = bytes;

            long newStart = 0;
            if (length <= 0 || currentWriter.getLength() > length) {
                newStart = cacheFile.length();
            }

            cacheRandomFile.seek(newStart);
            cacheRandomFile.write(currentWriter.getBytes());

            journalWriter.write(new JournalBuild(CLEAN, key, bytes.length, newStart).getBytes());
            journalWriter.flush();

            length = bytes.length;
            start = newStart;
        }

        long getKey() {
            return key;
        }

        byte[] readValue() throws Exception {
            RandomAccessFile file = null;
            byte[] bytes = new byte[length + ReadWriterBuild.LENGTH];
            try {
                file = new RandomAccessFile(cacheFile, "r");
                file.seek(start);
                if ((file.read(bytes)) == bytes.length) {
                    ReadWriterBuild build = new ReadWriterBuild();
                    build.readBytes(bytes);
                    return build.value;
                }
                return null;
            } finally {
                Utils.closeQuietly(file);
            }
        }
    }
}

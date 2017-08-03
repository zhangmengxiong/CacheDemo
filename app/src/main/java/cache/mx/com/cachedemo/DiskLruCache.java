/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cache.mx.com.cachedemo;
/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A cache that uses a bounded amount of space on a filesystem. Each cache
 * entry has a string key and a fixed number of values. Each key must match
 * the regex <strong>[a-z0-9_-]{1,120}</strong>. Values are byte sequences,
 * accessible as streams or files. Each value must be between {@code 0} and
 * {@code Integer.MAX_VALUE} bytes in length.
 * <p>
 * <p>The cache stores its data in a directory on the filesystem. This
 * directory must be exclusive to the cache; the cache may delete or overwrite
 * files from its directory. It is an error for multiple processes to use the
 * same cache directory at the same time.
 * <p>
 * <p>This cache limits the number of bytes that it will store on the
 * filesystem. When the number of stored bytes exceeds the limit, the cache will
 * remove entries in the background until the limit is satisfied. The limit is
 * not strict: the cache may temporarily exceed it while waiting for files to be
 * deleted. The limit does not include filesystem overhead or the cache
 * journal so space-sensitive applications should set a conservative limit.
 * <p>
 * <p>Clients call {@link #edit} to create or update the values of an entry. An
 * entry may have only one editor at one time; if a value is not available to be
 * edited then {@link #edit} will return null.
 * <ul>
 * <li>When an entry is being <strong>created</strong> it is necessary to
 * supply a full set of values; the empty value should be used as a
 * placeholder if necessary.
 * <li>When an entry is being <strong>edited</strong>, it is not necessary
 * to supply data for every value; values default to their previous
 * value.
 * </ul>
 * Every {@link #edit} call must be matched by a call to {@link Editor#commit}
 * or {@link Editor#abort}. Committing is atomic: a read observes the full set
 * of values as they were before or after the commit, but never a mix of values.
 * <p>
 * <p>Clients call {@link #get} to read a snapshot of an entry. The read will
 * observe the value at the time that {@link #get} was called. Updates and
 * removals after the call do not impact ongoing reads.
 * <p>
 * <p>This class is tolerant of some I/O errors. If files are missing from the
 * filesystem, the corresponding entries will be dropped from the cache. If
 * an error occurs while writing a cache value, the edit will fail silently.
 * Callers should handle other problems by catching {@code IOException} and
 * responding appropriately.
 */
public final class DiskLruCache implements Closeable {
    static final String JOURNAL_FILE = "journal";
    static final String JOURNAL_FILE_TEMP = "journal.tmp";
    static final String JOURNAL_FILE_BACKUP = "journal.bkp";
    static final String CACHE_FILE = "cache.c";
    static final String MAGIC = "libcore.io.DiskLruCache";
    static final String VERSION_1 = "1";
    static final String STRING_KEY_PATTERN = "[a-z0-9_-]{1,120}";
    static final Pattern LEGAL_KEY_PATTERN = Pattern.compile(STRING_KEY_PATTERN);
    private static final String CLEAN = "CLEAN";
    private static final String DIRTY = "DIRTY";
    private static final String REMOVE = "REMOVE";
    private static final Object WRITE_SYNC = new Object(); // 写文件锁

    /*
     * This cache uses a journal file named "journal". A typical journal file
     * looks like this:
     *     libcore.io.DiskLruCache
     *     1
     *     2
     *
     *     CLEAN 3400330d1dfc7f3f7f4b8d4d803dfcf6 832 0
     *     DIRTY 335c4c6028171cfddfbaae1a9c313c52
     *     CLEAN 335c4c6028171cfddfbaae1a9c313c52 3934 832
     *     REMOVE 335c4c6028171cfddfbaae1a9c313c52
     *     DIRTY 1ab96a171faeeee38496d8b330771a7a
     *     CLEAN 1ab96a171faeeee38496d8b330771a7a 1600 234
     *
     * The first five lines of the journal form its header. They are the
     * constant string "libcore.io.DiskLruCache", the disk cache's version,
     * the application's version, the value count, and a blank line.
     *
     * Each of the subsequent lines in the file is a record of the state of a
     * cache entry. Each line contains space-separated values: a state, a key,
     * and optional state-specific values.
     *   o DIRTY lines track that an entry is actively being created or updated.
     *     Every successful DIRTY action should be followed by a CLEAN or REMOVE
     *     action. DIRTY lines without a matching CLEAN or REMOVE indicate that
     *     temporary files may need to be deleted.
     *   o CLEAN lines track a cache entry that has been successfully published
     *     and may be read. A publish line is followed by the length of each of
     *     its values.
     *   o REMOVE lines track entries that have been deleted.
     *
     * The journal file is appended to as cache operations occur. The journal may
     * occasionally be compacted by dropping redundant lines. A temporary file named
     * "journal.tmp" will be used during compaction; that file should be deleted if
     * it exists when the cache is opened.
     */

    private final File directory;
    private final File journalFile;
    private final File journalFileTmp;
    private final File journalFileBackup;
    private final File cacheFile;
    private final RandomAccessFile cacheRandomFile;
    private final int appVersion;
    private long maxSize;
    private Writer journalWriter;
    private final LinkedHashMap<String, Entry> lruEntries =
            new LinkedHashMap<String, Entry>(0, 0.75f, true);
    private int redundantOpCount;

    /**
     * This cache uses a single background thread to evict entries.
     */
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Callable<Void> cleanupCallable = new Callable<Void>() {
        public Void call() throws Exception {
            synchronized (WRITE_SYNC) {
                if (journalWriter == null) {
                    return null; // Closed.
                }
                trimToSize();
                if (journalRebuildRequired()) {
                    rebuildJournal();
                    redundantOpCount = 0;
                }
            }
            return null;
        }
    };

    private DiskLruCache(File directory, int appVersion, long maxSize) throws IOException {
        this.directory = directory;
        this.appVersion = appVersion;
        this.journalFile = new File(directory, JOURNAL_FILE);
        this.journalFileTmp = new File(directory, JOURNAL_FILE_TEMP);
        this.journalFileBackup = new File(directory, JOURNAL_FILE_BACKUP);
        this.cacheFile = new File(directory, CACHE_FILE);
        if (!cacheFile.exists()) {
            cacheFile.getParentFile().mkdirs();
            cacheFile.createNewFile();
        }
        this.cacheRandomFile = new RandomAccessFile(cacheFile, "rw");
        this.maxSize = maxSize;
    }

    /**
     * Opens the cache in {@code directory}, creating a cache if none exists
     * there.
     *
     * @param directory a writable directory
     * @param maxSize   the maximum number of bytes this cache should use to store
     * @throws IOException if reading or writing the cache directory fails
     */
    public static DiskLruCache open(File directory, int appVersion, long maxSize)
            throws IOException {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize <= 0");
        }

        // If a bkp file exists, use it instead.
        File backupFile = new File(directory, JOURNAL_FILE_BACKUP);
        if (backupFile.exists()) {
            File journalFile = new File(directory, JOURNAL_FILE);
            // If journal file also exists just delete backup file.
            if (journalFile.exists()) {
                backupFile.delete();
            } else {
                renameTo(backupFile, journalFile, false);
            }
        }

        // Prefer to pick up where we left off.
        DiskLruCache cache = new DiskLruCache(directory, appVersion, maxSize);
        if (cache.journalFile.exists()) {
            try {
                cache.readJournal();
                cache.processJournal();
                return cache;
            } catch (IOException journalIsCorrupt) {
                System.out
                        .println("DiskLruCache "
                                + directory
                                + " is corrupt: "
                                + journalIsCorrupt.getMessage()
                                + ", removing");
                cache.delete();
            }
        }

        // Create a new empty cache.
        directory.mkdirs();
        cache = new DiskLruCache(directory, appVersion, maxSize);
        cache.rebuildJournal();
        return cache;
    }

    private void readJournal() throws IOException {
        StrictLineReader reader = new StrictLineReader(new FileInputStream(journalFile), Util.US_ASCII);
        try {
            String magic = reader.readLine();
            String version = reader.readLine();
            String appVersionString = reader.readLine();
            String blank = reader.readLine();
            if (!MAGIC.equals(magic)
                    || !VERSION_1.equals(version)
                    || !Integer.toString(appVersion).equals(appVersionString)
                    || !"".equals(blank)) {
                throw new IOException("unexpected journal header: [" + magic + ", " + version + ", "
                        + blank + "]");
            }

            int lineCount = 0;
            while (true) {
                try {
                    readJournalLine(reader.readLine());
                    lineCount++;
                } catch (EOFException endOfJournal) {
                    break;
                }
            }
            redundantOpCount = lineCount - lruEntries.size();

            // If we ended on a truncated line, rebuild the journal before appending to it.
            if (reader.hasUnterminatedLine()) {
                rebuildJournal();
            } else {
                journalWriter = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(journalFile, true), Util.US_ASCII));
            }
        } finally {
            Util.closeQuietly(reader);
        }
    }

    private void readJournalLine(String line) throws IOException {
        int firstSpace = line.indexOf(' ');
        if (firstSpace == -1) {
            throw new IOException("unexpected journal line: " + line);
        }

        int keyBegin = firstSpace + 1;
        int secondSpace = line.indexOf(' ', keyBegin);
        final String key;
        if (secondSpace == -1) {
            key = line.substring(keyBegin);
            if (firstSpace == REMOVE.length() && line.startsWith(REMOVE)) {
                lruEntries.remove(key);
                return;
            }
        } else {
            key = line.substring(keyBegin, secondSpace);
        }

        Entry entry = lruEntries.get(key);
        if (entry == null) {
            entry = new Entry(key);
            lruEntries.put(key, entry);
        }

        if (secondSpace != -1 && firstSpace == CLEAN.length() && line.startsWith(CLEAN)) {
            String[] lines = line.substring(secondSpace + 1).split(" ");
            entry.readable = true;
            entry.currentEditor = null;
            entry.setLength(lines[0]);
            entry.setStart(lines[1]);
        } else if (secondSpace == -1 && firstSpace == DIRTY.length() && line.startsWith(DIRTY)) {
            entry.currentEditor = new Editor(entry);
        } else {
            throw new IOException("unexpected journal line: " + line);
        }
    }

    /**
     * Computes the initial size and collects garbage as a part of opening the
     * cache. Dirty entries are assumed to be inconsistent and will be deleted.
     */
    private void processJournal() throws IOException {
        deleteIfExists(journalFileTmp);
        for (Iterator<Entry> i = lruEntries.values().iterator(); i.hasNext(); ) {
            Entry entry = i.next();
            if (entry.currentEditor != null) {
                entry.currentEditor = null;
                i.remove();
            }
        }
    }

    /**
     * Creates a new journal that omits redundant information. This replaces the
     * current journal if it exists.
     */
    private synchronized void rebuildJournal() throws IOException {
        if (journalWriter != null) {
            journalWriter.close();
        }

        Writer writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(journalFileTmp), Util.US_ASCII));
        try {
            writer.write(MAGIC);
            writer.write("\n");
            writer.write(VERSION_1);
            writer.write("\n");
            writer.write(Integer.toString(appVersion));
            writer.write("\n");
            writer.write("\n");

            for (Entry entry : lruEntries.values()) {
                if (entry.currentEditor != null) {
                    writer.write(DIRTY + ' ' + entry.key + '\n');
                } else {
                    writer.write(CLEAN + ' ' + entry.key + ' ' + entry.getLength() + ' ' + entry.getStart() + '\n');
                }
            }
        } finally {
            writer.close();
        }

        if (journalFile.exists()) {
            renameTo(journalFile, journalFileBackup, true);
        }
        renameTo(journalFileTmp, journalFile, false);
        journalFileBackup.delete();

        journalWriter = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(journalFile, true), Util.US_ASCII));
    }

    private static void deleteIfExists(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException();
        }
    }

    private static void renameTo(File from, File to, boolean deleteDestination) throws IOException {
        if (deleteDestination) {
            deleteIfExists(to);
        }
        if (!from.renameTo(to)) {
            throw new IOException();
        }
    }

    /**
     * Returns a snapshot of the entry named {@code key}, or null if it doesn't
     * exist is not currently readable. If a value is returned, it is moved to
     * the head of the LRU queue.
     */
    private synchronized Reader get(String key) throws IOException {
        checkNotClosed();
        validateKey(key);
        Entry entry = lruEntries.get(key);
        if (entry == null) {
            return null;
        }

        if (!entry.readable) {
            return null;
        }

        redundantOpCount++;
        if (journalRebuildRequired()) {
            executorService.submit(cleanupCallable);
        }

        return new Reader(entry.start, entry.length);
    }

    private synchronized Editor edit(String key) throws IOException {
        checkNotClosed();
        validateKey(key);
        Entry entry = lruEntries.get(key);
        if (entry == null) {
            entry = new Entry(key);
            lruEntries.put(key, entry);
        } else if (entry.currentEditor != null) {
            return null; // Another edit is in progress.
        }

        Editor editor = new Editor(entry);
        entry.currentEditor = editor;

        // Flush the journal before creating files to prevent file leaks.
        journalWriter.write(DIRTY + ' ' + key + '\n');
        journalWriter.flush();
        return editor;
    }

    /**
     * Returns the directory where this cache stores its data.
     */
    public File getDirectory() {
        return directory;
    }

    /**
     * Returns the maximum number of bytes that this cache should use to store
     * its data.
     */
    public synchronized long getMaxSize() {
        return maxSize;
    }

    /**
     * Changes the maximum number of bytes the cache can store and queues a job
     * to trim the existing store, if necessary.
     */
    public synchronized void setMaxSize(long maxSize) {
        this.maxSize = maxSize;
        executorService.submit(cleanupCallable);
    }

    /**
     * Returns the number of bytes currently being used to store the values in
     * this cache. This may be greater than the max size if a background
     * deletion is pending.
     */
    public synchronized long size() {
        return cacheFile != null && cacheFile.exists() ? cacheFile.length() : 0;
    }

    private synchronized void completeEdit(Editor editor, boolean success) throws IOException {
        Entry entry = editor.entry;
        if (entry.currentEditor != editor) {
            throw new IllegalStateException();
        }

        // If this edit is creating the entry for the first time, every index must have a value.
        if (success && !entry.readable && !editor.isInWritten) {
            editor.abort();
            throw new IllegalStateException("Newly created entry didn't create value for index ");
        }

        entry.currentEditor = null;
        entry.readable = true;
        journalWriter.write(CLEAN + ' ' + entry.key + ' ' + entry.getLength() + ' ' + entry.getStart() + '\n');
        journalWriter.flush();

        redundantOpCount++;
        if (size() > maxSize || journalRebuildRequired()) {
            executorService.submit(cleanupCallable);
        }
    }

    /**
     * We only rebuild the journal when it will halve the size of the journal
     * and eliminate at least 2000 ops.
     */
    private boolean journalRebuildRequired() {
        return redundantOpCount >= 2000 && redundantOpCount >= lruEntries.size();
    }

    /**
     * Drops the entry for {@code key} if it exists and can be removed. Entries
     * actively being edited cannot be removed.
     *
     * @return true if an entry was removed.
     */
    public synchronized boolean remove(String key) throws IOException {
        checkNotClosed();
        validateKey(key);
        Entry entry = lruEntries.get(key);
        if (entry == null || entry.currentEditor != null) {
            return false;
        }
        entry.length = 0;

        redundantOpCount++;
        journalWriter.append(REMOVE + ' ' + key + '\n');
        lruEntries.remove(key);

        if (journalRebuildRequired()) {
            executorService.submit(cleanupCallable);
        }

        return true;
    }

    /**
     * Returns true if this cache has been closed.
     */
    public synchronized boolean isClosed() {
        return journalWriter == null;
    }

    private void checkNotClosed() {
        if (journalWriter == null) {
            throw new IllegalStateException("cache is closed");
        }
    }

    /**
     * Force buffered operations to the filesystem.
     */
    public synchronized void flush() throws IOException {
        checkNotClosed();
        trimToSize();
        journalWriter.flush();
    }

    /**
     * Closes this cache. Stored values will remain on the filesystem.
     */
    public synchronized void close() throws IOException {
        if (journalWriter == null) {
            return; // Already closed.
        }
        for (Entry entry : new ArrayList<Entry>(lruEntries.values())) {
            if (entry.currentEditor != null) {
                entry.currentEditor.abort();
            }
        }
        trimToSize();
        Util.closeQuietly(cacheRandomFile);
        journalWriter.close();
        journalWriter = null;
    }

    private void trimToSize() throws IOException {
        while (size() > maxSize) {
            Map.Entry<String, Entry> toEvict = lruEntries.entrySet().iterator().next();
            remove(toEvict.getKey());
        }
    }

    /**
     * Closes the cache and deletes all of its stored values. This will delete
     * all files in the cache directory including files that weren't created by
     * the cache.
     */
    public void delete() throws IOException {
        close();
        Util.deleteContents(directory);
    }

    private void validateKey(String key) {
        Matcher matcher = LEGAL_KEY_PATTERN.matcher(key);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("keys must match regex "
                    + STRING_KEY_PATTERN + ": \"" + key + "\"");
        }
    }

    public void setString(String key, String value) {
        try {
            synchronized (WRITE_SYNC) {
                Editor editor = edit(key);
                editor.set(value);
                editor.commit();
            }
        } catch (Exception ignored) {
        }
    }

    public String getString(String key) {
        try {
            return get(key).getString();
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * A snapshot of the values for an entry.
     */
    private final class Reader implements Closeable {
        private final long startPosition;
        private final int length;

        private Reader(long startPosition, int lengths) {
            this.startPosition = startPosition;
            this.length = lengths;
        }

        /**
         * Returns the string value for {@code index}.
         */
        String getString() throws IOException {
            RandomAccessFile file = null;
            byte[] bytes = null;
            try {
                file = new RandomAccessFile(cacheFile, "r");
                file.seek(startPosition);
                bytes = new byte[length];
                if (file.read(bytes, 0, length) != length) {
                    throw new IOException("unexpected journal line: " + length);
                }
            } finally {
                Util.closeQuietly(file);
            }

            return new String(bytes);
        }

        public void close() {
        }
    }

    /**
     * Edits the values for an entry.
     */
    private final class Editor {
        private final Entry entry;
        private boolean isInWritten;
        private boolean hasErrors;

        private Editor(Entry entry) {
            this.entry = entry;
            this.isInWritten = false;
            this.hasErrors = false;
        }

        /**
         * Sets the value at {@code index} to {@code value}.
         */
        void set(String value) {
            if (entry.currentEditor != this) {
                return;
            }

            entry.readable = false;
            isInWritten = true;
            hasErrors = false;
            try {
                entry.computeStart(value.length());

                cacheRandomFile.seek(entry.getStart());
                cacheRandomFile.write(value.getBytes());
                entry.setLength(value.length());
            } catch (Exception e) {
                e.printStackTrace();
                hasErrors = true;
            }
        }

        /**
         * Commits this edit so it is visible to readers.  This releases the
         * edit lock so another edit may be started on the same key.
         */
        public void commit() throws IOException {
            if (hasErrors) {
                completeEdit(this, false);
                remove(entry.key); // The previous entry is stale.
            } else {
                completeEdit(this, true);
                entry.readable = true;
            }
            isInWritten = false;
        }

        /**
         * Aborts this edit. This releases the edit lock so another edit may be
         * started on the same key.
         */
        public void abort() throws IOException {
            completeEdit(this, false);
            isInWritten = false;
        }
    }

    private final class Entry {
        private final String key;

        /**
         * Lengths of this entry's files.
         */
        private int length;

        /**
         * 文件的开始位置
         */
        private long start;

        /**
         * True if this entry has ever been published.
         */
        private boolean readable;

        /**
         * The ongoing edit or null if this entry is not being edited.
         */
        private Editor currentEditor;

        private Entry(String key) {
            this.key = key;
            this.length = 0;
        }

        public int getLength() throws IOException {
            return length;
        }

        /**
         * Set length using decimal numbers like "10123".
         */
        private void setLength(String length) throws IOException {
            try {
                this.length = Integer.parseInt(length);
            } catch (Exception e) {
                throw new IOException("unexpected journal line: " + length);
            }
        }

        public void setStart(String start) throws IOException {
            try {
                this.start = Long.parseLong(start);
            } catch (Exception e) {
                throw new IOException("unexpected journal line: " + length);
            }
        }

        public void computeStart(long l) {
            if (length <= 0 || l > length) {
                start = cacheFile.length();
            }
        }

        public long getStart() {
            return start;
        }

        public void setLength(int length) {
            this.length = length;
        }
    }
}
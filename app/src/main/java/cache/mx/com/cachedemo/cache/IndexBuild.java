package cache.mx.com.cachedemo.cache;

import java.nio.MappedByteBuffer;

/**
 * 缓存的index文件写入读取帮助类
 * 写入方式：
 * 关键字-状态-内容长度-插入时间-内容开始位置-插入的标记
 * key-status-length-time-start-index
 * 对应类型：
 * long-char-int-long-long-int
 * <p>
 * 创建人： zhangmengxiong
 * 创建时间： 2017/8/10.
 * 联系方式: zmx_final@163.com
 */
class IndexBuild {
    static final int LENGTH = 33;

    /**
     * 从文件的某个位置开始读取数据到entry中
     *
     * @param journalBuffer 内存文件
     * @param entry         填充的对象
     * @param p             开始位置
     */
    static void fillEntry(MappedByteBuffer journalBuffer, MXCache.Entry entry, Integer p) {
        entry.keyStartPosition = p;
        entry.key = journalBuffer.getLong(p);
        entry.status = journalBuffer.get(p + 8);
        entry.length = journalBuffer.getInt(p + 8 + 1);
        entry.insertTime = journalBuffer.getLong(p + 8 + 1 + 4);
        entry.valueStartPosition = journalBuffer.getLong(p + 8 + 1 + 4 + 8);
        entry.sortIndex = journalBuffer.getInt(p + 8 + 1 + 4 + 8 + 8);
    }

    /**
     * 写入数据开始
     *
     * @param journalBuffer 内存文件
     * @param startIndex    开始位置
     * @param key           键
     * @param type          写入标记
     * @throws Exception
     */
    static void editStart(MappedByteBuffer journalBuffer, int startIndex, long key, byte type) throws Exception {
        journalBuffer.putLong(startIndex, key);
        journalBuffer.put(startIndex + 8, type);
    }

    /**
     * 写入结束
     *
     * @param journalBuffer     内存文件
     * @param startIndex        开始位置
     * @param key               键
     * @param type              写入标记
     * @param length            数据长度
     * @param currentTimeMillis 时间点
     * @param start             数据源的开始位置
     * @param sortIndex         插入的顺序
     */
    static void editEnd(MappedByteBuffer journalBuffer, int startIndex, long key, byte type, int length, long currentTimeMillis, long start, int sortIndex) {
        journalBuffer.putLong(startIndex, key);
        journalBuffer.put(startIndex + 8, type);
        journalBuffer.putInt(startIndex + 8 + 1, length);
        journalBuffer.putLong(startIndex + 8 + 1 + 4, currentTimeMillis);
        journalBuffer.putLong(startIndex + 8 + 1 + 4 + 8, start);
        journalBuffer.putInt(startIndex + 8 + 1 + 4 + 8 + 8, sortIndex);
    }

    /**
     * 读取status
     *
     * @param journalBuffer 内存文件
     * @param position      开始位置
     * @return
     */
    static byte getStatus(MappedByteBuffer journalBuffer, int position) {
        return journalBuffer.get(position + 8);
    }

    /**
     * 读取key
     *
     * @param journalBuffer 内存文件
     * @param position      开始位置
     * @return
     */
    static long getKey(MappedByteBuffer journalBuffer, int position) {
        return journalBuffer.getLong(position);
    }

    /**
     * 读取排序
     *
     * @param journalBuffer 内存文件
     * @param position      开始位置
     * @return
     */
    static int getOrderIndex(MappedByteBuffer journalBuffer, int position) {
        return journalBuffer.getInt(position + 8 + 1 + 4 + 8 + 8);
    }

    /**
     * 删除一条数
     *
     * @param journalBuffer 内存文件
     * @param position      开始位置
     * @param b             标记状态
     */
    static void deleteKey(MappedByteBuffer journalBuffer, Integer position, byte b) {
        journalBuffer.putLong(position, 0L);
        journalBuffer.put(position + 8, b);
    }
}

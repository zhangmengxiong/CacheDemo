package cache.mx.com.cachedemo.cache;

import java.io.Closeable;
import java.nio.charset.Charset;

/**
 * 创建人： zhangmengxiong
 * 创建时间： 2017/8/10.
 * 联系方式: zmx_final@163.com
 */

class Utils {
    private static final long POLY64REV = 0x95AC9329AC4BC9B5L;
    private static final long INITIALCRC = 0xFFFFFFFFFFFFFFFFL;
    static final Charset UTF_8 = Charset.forName("UTF-8");

    private static long[] sCrcTable = new long[256];

    static {
        // 参考 http://bioinf.cs.ucl.ac.uk/downloads/crc64/crc64.c
        long part;
        for (int i = 0; i < 256; i++) {
            part = i;
            for (int j = 0; j < 8; j++) {
                long x = ((int) part & 1) != 0 ? POLY64REV : 0;
                part = (part >> 1) ^ x;
            }
            sCrcTable[i] = part;
        }
    }

    static long getKey(String key) {
        byte[] bytes = getBytes(key);
        long crc = INITIALCRC;
        for (byte b : bytes) {
            crc = sCrcTable[(((int) crc) ^ b) & 0xff] ^ (crc >> 8);
        }
        return crc;
    }

    private static byte[] getBytes(String in) {
        byte[] result = new byte[in.length() * 2];
        int output = 0;
        for (char ch : in.toCharArray()) {
            result[output++] = (byte) (ch & 0xFF);
            result[output++] = (byte) (ch >> 8);
        }
        return result;
    }

    static int readInt(byte[] buf, int offset) {
        return (buf[offset] & 0xff) | ((buf[offset + 1] & 0xff) << 8) | ((buf[offset + 2] & 0xff) << 16)
                | ((buf[offset + 3] & 0xff) << 24);
    }

    static long readLong(byte[] buf, int offset) {
        long result = buf[offset + 7] & 0xff;
        for (int i = 6; i >= 0; i--) {
            result = (result << 8) | (buf[offset + i] & 0xff);
        }
        return result;
    }

    static byte[] readBytes(byte[] bytes, int offset, int length) {
        byte[] str = new byte[length];
        System.arraycopy(bytes, offset, str, 0, length);
        return str;
    }

    static void writeInt(byte[] buf, int offset, int value) {
        for (int i = 0; i < 4; i++) {
            buf[offset + i] = (byte) (value & 0xff);
            value >>= 8;
        }
    }

    static void writeLong(byte[] buf, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            buf[offset + i] = (byte) (value & 0xff);
            value >>= 8;
        }
    }

    static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (RuntimeException rethrown) {
                throw rethrown;
            } catch (Exception ignored) {
            }
        }
    }
}

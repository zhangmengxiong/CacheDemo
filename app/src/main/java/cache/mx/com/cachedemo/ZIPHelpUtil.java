package cache.mx.com.cachedemo;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import cache.mx.com.cachedemo.cache.CacheUtils;

/**
 * 压缩文件处理类
 * Created by zmx_f on 2016/3/23.
 */
public class ZIPHelpUtil {
    private static final String TAG = ZIPHelpUtil.class.getSimpleName();

    /**
     * 直接从zip文件中解压到缓存数据中
     *
     * @param zipFile
     * @param keyHead
     * @throws IOException
     */
    public static synchronized void unZipAndCache(File zipFile, String keyHead) throws IOException, InterruptedException {
        ZipFile zip = new ZipFile(zipFile);
        StringBuilder content = new StringBuilder();
        CacheUtils cacheUtils = CacheUtils.getInstance(null);
        byte[] b = new byte[1024 * 16];
        for (Enumeration entries = zip.entries(); entries.hasMoreElements(); ) {
            ZipEntry entry = (ZipEntry) entries.nextElement();
            if (entry.isDirectory()) continue;//如果解压的文件是目录 则跳过

            String key = entry.getName();
            InputStream in = zip.getInputStream(entry);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int len;
            while ((len = in.read(b)) > 0) {
                out.write(b, 0, len);
            }
            in.close();

            content.setLength(0);
            content.append(out.toString("UTF-8"));
            out.close();
            cacheUtils.setString(keyHead + key, content.toString());

            String json = cacheUtils.getString(keyHead + key);
            if (!json.equals(content.toString())) {
                Log.v(TAG, "存储 != 读取：" + key);
            }
        }
        zip.close();
    }
}

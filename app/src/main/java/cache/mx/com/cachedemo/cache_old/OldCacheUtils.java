package cache.mx.com.cachedemo.cache_old;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

import cache.mx.com.cachedemo.cache_old.core.StringCache;

/**
 * 缓存工具类
 * Created by zmx on 2015/11/30.
 */
public class OldCacheUtils {
    private static final String TAG = OldCacheUtils.class.getSimpleName();
    private static OldCacheUtils INSTANCE;
    private StringCache.ImageCacheParams cacheParams;
    /**
     * 互斥操作锁
     */
    private static final Object SYNC_WRITE = new Object();
    private StringCache stringCache;

    private OldCacheUtils(Context context) {
        File cache = context.getExternalCacheDir();
        if (cache != null && !cache.exists()) {
            cache.mkdirs();
        }
        cacheParams = new StringCache.ImageCacheParams(new File("/sdcard/cache/old"));
        cacheParams.setMemCacheSizePercent(context, 0.06f);//使用内存缓存
        cacheParams.setDiskCacheSize(100 * 1024 * 1024);//100M的数据缓存
        cacheParams.setRecycleImmediately(true);
        cacheParams.memoryCacheEnabled = false;
        stringCache = new StringCache(cacheParams);
    }

    /**
     * 单例
     *
     * @return
     */
    public synchronized static OldCacheUtils getInstance(Context context) {
        if (INSTANCE == null)
            INSTANCE = new OldCacheUtils(context);
        return INSTANCE;
    }

    private AtomicInteger READ_SIZE = new AtomicInteger(0);

    /**
     * 获取缓存字符串
     *
     * @param key
     * @return
     */
    public String getCache(String key) {
        long timeDelay = 60 * 24 * 15;//默认为15天
        return getCache(key, timeDelay);
    }

    /**
     * js缓存
     *
     * @param key
     * @param timeDelay 缓存有效期 分钟
     * @return
     */
    public String getCache(String key, long timeDelay) {
        if (TextUtils.isEmpty(key)) return null;
        READ_SIZE.incrementAndGet();
//        Log.v(TAG, "READ_SIZE = " + READ_SIZE.get());
        String value = stringCache.getCache(key, timeDelay);
        READ_SIZE.decrementAndGet();
//        Log.v(TAG, "READ_SIZE = " + READ_SIZE.get());
        return value;
    }

    /**
     * 重置缓存，会清空所有数据
     */
    public void reset() {
        synchronized (SYNC_WRITE) {
            stringCache.clearCache();
            stringCache.close();
            stringCache = null;
            stringCache = new StringCache(cacheParams);
        }
    }

    /**
     * 插入js缓存文件
     *
     * @param key
     * @param value
     */
    public void addCache(String key, String value) {
        if (TextUtils.isEmpty(key)) return;
        stringCache.addCache(key, value);
    }

    public void addCacheSync(String key, String value) throws InterruptedException {
        while (READ_SIZE.get() >= 1) {
            Log.v(TAG, "优先读取  写入延迟1秒！");
            Thread.sleep(1000);
        }
        addCache(key, value);
    }
}

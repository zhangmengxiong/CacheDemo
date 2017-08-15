package cache.mx.com.cachedemo.cache;

import android.content.Context;

import java.io.File;

/**
 * 创建人： zhangmengxiong
 * 创建时间： 2017/8/11.
 * 联系方式: zmx_final@163.com
 */

public class CacheUtils {
    private static CacheUtils INSTANCE = null;
    private MXCache mxCache;

    private CacheUtils(Context context) {
        try {
            mxCache = new MXCache(new File("/sdcard/cache"), 1, 10000, 1024 * 1024 * 50);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static synchronized CacheUtils getInstance(Context context) {
        if (INSTANCE == null) {
            INSTANCE = new CacheUtils(context);
        }
        return INSTANCE;
    }

    public void setString(String key, String value) {
        mxCache.setString(key, value);
    }

    public String getString(String key) {
        return mxCache.getString(key, 0);
    }
}

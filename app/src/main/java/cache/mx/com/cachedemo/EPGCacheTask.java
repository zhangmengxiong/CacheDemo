package cache.mx.com.cachedemo;

import android.content.Context;
import android.os.AsyncTask;

import java.io.File;

/**
 * 电视节目列表缓存执行器
 * Created by zmx on 2015/11/19.
 * <p/>
 * 2016-7-14 张孟雄 修改：
 * 1：修改服务器的文件名字，规避运营商缓存导致的数据同步问题！
 */
public class EPGCacheTask extends AsyncTask<String, String, Boolean> {
    private static final String TAG = EPGCacheTask.class.getSimpleName();
    private final Context context;

    public EPGCacheTask(Context context) {
        this.context = context;
    }

    @Override
    protected Boolean doInBackground(String... params) {
        while (!isCancelled()) {
            try {
                ZIPHelpUtil.unZipAndCache(new File("/sdcard/aaa.c"), "asdasdasd");
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try {
                    Thread.sleep(1000 * 30);
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }
}

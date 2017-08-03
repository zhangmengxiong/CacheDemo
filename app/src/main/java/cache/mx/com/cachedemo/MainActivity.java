package cache.mx.com.cachedemo;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import java.io.File;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    DiskLruCache diskLruCache = DiskLruCache.open(new File("/sdcard/cache/"), 1, 1024 * 1024);
                    for (int i = 0; i < 10000; i++) {
                        diskLruCache.setString("" + i, i + "_653213532132658435432135735432132354132435436323125");
                    }
                    for (int i = 0; i < 10000; i++) {
                        Log.v("aaa", i + " = " + diskLruCache.getString("" + i));
                        Thread.sleep(10);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }
}

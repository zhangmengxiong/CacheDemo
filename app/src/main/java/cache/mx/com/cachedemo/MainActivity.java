package cache.mx.com.cachedemo;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Random;

public class MainActivity extends Activity {
    DiskLruCache diskLruCache;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        try {
            diskLruCache = DiskLruCache.open(new File("/sdcard/cache/"), 1, 1024 * 1024 * 10);
        } catch (IOException e) {
            e.printStackTrace();
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long start = System.currentTimeMillis();
                    for (int i = 0; i < 100000; i++) {
                        diskLruCache.setString("" + i, i + "__" + System.currentTimeMillis() + "_" + new Random().nextDouble());
                    }
                    Log.v("time", "存储100000条数据用时" + (System.currentTimeMillis() - start) / 1000f + " s");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                try {
//                    for (int i = 0; i < 10000; i++) {
//                        diskLruCache.setString("" + i, i + "__" + System.currentTimeMillis() + "_" + new Random().nextDouble());
//                    }
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }
//            }
//        }).start();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < 100000; i++) {
                        Log.v("aaa", i + " = " + diskLruCache.getString("" + i));
                        Thread.sleep(30);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }
}

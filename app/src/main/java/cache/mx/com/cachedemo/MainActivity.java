package cache.mx.com.cachedemo;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Random;

import cache.mx.com.cachedemo.cache.MXCache;

public class MainActivity extends Activity {
    MXCache diskLruCache;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        try {
            diskLruCache = new MXCache(new File("/sdcard/cache/"), 1, 100, 1024 * 1024 * 10);
//            diskLruCache.setString("" + 11, 11 + "__" + System.currentTimeMillis() + "_" + 22);
//            Log.v("aaa", 11 + " = " + diskLruCache.getString("" + 11));
//
//            diskLruCache.setString("" + 22, 22 + "__" + System.currentTimeMillis() + "_" + 33);
//            Log.v("aaa", 22 + " = " + diskLruCache.getString("" + 22));
        } catch (IOException e) {
            e.printStackTrace();
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 5000; i++) {
                    String key = getRandomString(10);
                    String value = getRandomString(new Random().nextInt(5000));
                    diskLruCache.setString(key, value);
                    Log.v("aaa", key + " = " + value);

                    String newValue = diskLruCache.getString(key);
                    if (value.equals(newValue)) {
                        Log.v("aaa", key + " = " + newValue);
                    } else {
                        Log.e("aaa", key + " != " + newValue);
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();

//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                try {
//                    long start = System.currentTimeMillis();
//                    for (int i = 0; i < 10000; i++) {
//                        diskLruCache.setString("" + i, i + "__" + System.currentTimeMillis() + "_" + new Random().nextDouble());
//                    }
//                    Log.v("time", "存储10000条数据用时" + (System.currentTimeMillis() - start) / 1000f + " s");
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }
//            }
//        }).start();
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
//        new EPGCacheTask(this).execute();

//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                try {
//                    for (int i = 0; i < 10000; i++) {
//                        Log.v("aaa", i + " = " + diskLruCache.getString("" + new Random().nextInt(10000)));
//                        Thread.sleep(30);
//                    }
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }
//            }
//        }).start();
    }

    private String getBuildString(int i) {
        StringBuilder builder = new StringBuilder();

        String s = "abcdsdhflkjpq [qqp[ouiweportieryuwyjkgh;askdjfzxmbczx,cmn23847-234=098=0129483589767";
        for (int i1 = 0; i1 < i; i1++) {
            builder.append(s.indexOf(new Random(s.length()).nextInt()));
        }
        return builder.toString();
    }

    public static String getRandomString(int length) { //length表示生成字符串的长度
        String base = "abcdefghijklmnopqrstuvwxyz0123456789";
        Random random = new Random();
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < length; i++) {
            int number = random.nextInt(base.length());
            sb.append(base.charAt(number));
        }
        return sb.toString();
    }
}

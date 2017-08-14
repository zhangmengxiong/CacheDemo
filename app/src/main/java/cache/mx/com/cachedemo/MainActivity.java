package cache.mx.com.cachedemo;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import java.util.Random;

import cache.mx.com.cachedemo.cache.CacheUtils;
import cache.mx.com.cachedemo.cache_old.OldCacheUtils;

public class MainActivity extends Activity {
    CacheUtils diskLruCache;
    OldCacheUtils oldCacheUtils;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        diskLruCache = CacheUtils.getInstance(this);
        diskLruCache.setString("" + 11, 11 + "__" + System.currentTimeMillis() + "_" + 22);
        Log.v("aaa", 11 + " = " + diskLruCache.getString("" + 11));

        diskLruCache.setString("" + 22, 22 + "__" + System.currentTimeMillis() + "_" + 33);
        Log.v("aaa", 22 + " = " + diskLruCache.getString("" + 22));

        oldCacheUtils = OldCacheUtils.getInstance(this);


//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                for (int i = 0; i < 5000; i++) {
//                    String key = getRandomString(5);
//                    String value = getRandomString(new Random().nextInt(500));
//                    diskLruCache.setString(key, value);
////                    Log.v("aaa", key + " = " + value);
//
//                    String newValue = diskLruCache.getString(key);
//                    if (value.equals(newValue)) {
////                        Log.v("aaa", key + " = " + newValue);
//                    } else {
//                        Log.e("aaa", key + " != " + newValue);
//                    }
////                    try {
////                        Thread.sleep(50);
////                    } catch (InterruptedException e) {
////                        e.printStackTrace();
////                    }
//                }
//            }
//        }).start();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long start = System.currentTimeMillis();
                    for (int i = 0; i < 10000; i++) {
                        if (i % 100 == 0) {
                            diskLruCache.setString("" + i, "__" + System.currentTimeMillis() + "_" + new Random().nextDouble() + " " + i);
                        } else {
                            diskLruCache.setString("" + i, i + "__" + System.currentTimeMillis() + "_" + new Random().nextDouble() + " " + i);
                        }
                    }
                    Log.v("new", "存储10000条数据用时" + (System.currentTimeMillis() - start) / 1000f + " s");
                    start = System.currentTimeMillis();
                    for (int i = 0; i < 10000; i++) {
                        String s = diskLruCache.getString("" + i);
                        if (s == null || !s.startsWith("" + i) || !s.endsWith("" + i)) {
                            Log.v("get", " 读取失败：" + i + " " + s);
                        }
                    }
                    Log.v("new", "读取10000条数据用时" + (System.currentTimeMillis() - start) / 1000f + " s");

                    for (int i = 0; i < 10000; i++) {
                        if (i % 100 == 0) {
                            oldCacheUtils.addCache("" + i, "__" + System.currentTimeMillis() + "_" + new Random().nextDouble() + " " + i);
                        } else {
                            oldCacheUtils.addCache("" + i, i + "__" + System.currentTimeMillis() + "_" + new Random().nextDouble() + " " + i);
                        }
                    }
                    Log.v("old", "存储10000条数据用时" + (System.currentTimeMillis() - start) / 1000f + " s");
                    start = System.currentTimeMillis();
                    for (int i = 0; i < 10000; i++) {
                        String s = oldCacheUtils.getCache("" + i);
                        if (s == null || !s.startsWith("" + i) || !s.endsWith("" + i)) {
                            Log.v("get", " 读取失败：" + i + " " + s);
                        }
                    }
                    Log.v("old", "读取10000条数据用时" + (System.currentTimeMillis() - start) / 1000f + " s");
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
        String base = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random random = new Random();
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < length; i++) {
            int number = random.nextInt(base.length());
            sb.append(base.charAt(number));
        }
        return sb.toString();
    }
}

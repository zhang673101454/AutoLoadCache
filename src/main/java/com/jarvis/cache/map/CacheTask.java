package com.jarvis.cache.map;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;

import com.jarvis.cache.to.CacheWrapper;
import com.jarvis.lib.util.OsUtil;

public class CacheTask implements Runnable {

    private static final Logger logger=Logger.getLogger(CacheTask.class);

    private CachePointCut cacheManager;

    private int period=60 * 1000; // 1Minutes

    private volatile boolean running=false;

    private File saveFile;

    public CacheTask(CachePointCut cacheManager) {
        this.cacheManager=cacheManager;
    }

    public void start() {
        if(!this.running) {
            loadCache();
            this.running=true;
        }

    }

    public void destroy() {
        persistCache();
        this.running=false;
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        this.running=false;
    }

    private String getSavePath() {
        String persistFile=cacheManager.getPersistFile();
        if(null != persistFile && persistFile.trim().length() > 0) {
            return persistFile;
        }
        String path="/tmp/autoload-cache/";
        String nsp=cacheManager.getNamespace();
        if(null != nsp && nsp.trim().length() > 0) {
            path+=nsp.trim() + "/";
        }
        if(OsUtil.getInstance().isLinux()) {
            return path;
        }
        return "C:" + path;
    }

    private File getSaveFile() {
        if(null != saveFile) {
            return saveFile;
        }
        String path=getSavePath();
        File savePath=new File(path);
        if(!savePath.exists()) {
            savePath.mkdirs();
        }
        saveFile=new File(path + "map.cache");
        return saveFile;
    }

    /**
     * 从磁盘中加载之前保存的缓存数据，避免刚启动时，因为没有缓存，而且造成压力过大
     */
    @SuppressWarnings("unchecked")
    public void loadCache() {
        File file=getSaveFile();
        if(null == file) {
            return;
        }
        if(!file.exists()) {
            return;
        }
        BufferedInputStream bis=null;
        try {
            FileInputStream fis=new FileInputStream(file);
            bis=new BufferedInputStream(fis);
            ByteArrayOutputStream baos=new ByteArrayOutputStream();
            byte buf[]=new byte[1024];
            int len=-1;
            while((len=bis.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            byte retArr[]=baos.toByteArray();
            Object obj=cacheManager.getSerializer().deserialize(retArr);
            if(null != obj && obj instanceof ConcurrentHashMap) {
                cacheManager.getCache().putAll((ConcurrentHashMap<String, Object>)obj);
            }
        } catch(Exception ex) {
            logger.error(ex.getMessage(), ex);
        } finally {
            if(null != bis) {
                try {
                    bis.close();
                } catch(IOException e) {
                    e.printStackTrace();
                }
            }
        }

    }

    private void persistCache() {
        if(!cacheManager.isNeedPersist()) {
            return;
        }
        if(!cacheManager.isCacheChaned()) {
            return;
        }
        cacheManager.setCacheChaned(false);
        FileOutputStream fos=null;
        try {
            byte[] data=cacheManager.getSerializer().serialize(cacheManager.getCache());
            File file=getSaveFile();
            fos=new FileOutputStream(file);
            fos.write(data);
        } catch(Exception ex) {
            cacheManager.setCacheChaned(true);
            logger.error(ex.getMessage(), ex);
        } finally {
            if(null != fos) {
                try {
                    fos.close();
                } catch(IOException e) {
                    e.printStackTrace();
                }
            }
        }

    }

    @Override
    public void run() {
        while(running) {
            try {
                cleanCache();
                persistCache();
            } catch(Exception e) {
                logger.error(e.getMessage(), e);
            }
            try {
                Thread.sleep(period);
            } catch(InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    /**
     * 清除过期缓存
     */
    @SuppressWarnings("unchecked")
    private void cleanCache() {
        Iterator<Entry<String, Object>> iterator=cacheManager.getCache().entrySet().iterator();
        boolean cacheChaned=false;
        while(iterator.hasNext()) {
            Object value=iterator.next().getValue();
            if(value instanceof CacheWrapper) {
                CacheWrapper tmp=(CacheWrapper)value;
                if(tmp.isExpired()) {
                    iterator.remove();
                    cacheChaned=true;
                }
            } else {
                ConcurrentHashMap<String, CacheWrapper> hash=(ConcurrentHashMap<String, CacheWrapper>)value;
                Iterator<Entry<String, CacheWrapper>> iterator2=hash.entrySet().iterator();
                while(iterator2.hasNext()) {
                    CacheWrapper tmp=iterator2.next().getValue();
                    if(tmp.isExpired()) {
                        iterator2.remove();
                        cacheChaned=true;
                    }
                }
                if(hash.isEmpty()) {
                    iterator.remove();
                    cacheChaned=true;
                }
            }
        }
        if(cacheChaned) {
            cacheManager.setCacheChaned(true);
        }
    }

}
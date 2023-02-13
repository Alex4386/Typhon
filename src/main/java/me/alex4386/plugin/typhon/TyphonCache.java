package me.alex4386.plugin.typhon;

public class TyphonCache<T>  {
    T target;
    long lastUpdate = System.currentTimeMillis();

    public static long cacheValidity = 500;

    public TyphonCache(T target) {
        this.target = target;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() - this.lastUpdate >= cacheValidity;
    }

    public T getTarget() {
        return this.target;
    }
}

package me.alex4386.plugin.typhon;

public class TyphonCache<T>  {
    T target;
    long lastUpdate = System.currentTimeMillis();

    public long cacheValidity = 20;

    public TyphonCache(T target) {
        this.target = target;
    }

    public TyphonCache(T target, long cacheValidity) {
        this(target);
        this.cacheValidity = cacheValidity;
    }

    public boolean isExpired() {
        if (this.cacheValidity < 0) return true;
        if (this.cacheValidity == 0) return false;
        return System.currentTimeMillis() - this.lastUpdate >= this.cacheValidity;
    }

    public T getTarget() {
        return this.target;
    }
}

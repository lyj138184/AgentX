package org.xhy.domain.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xhy.domain.rag.model.DocumentUnitEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RAG缓存服务
 * 用于缓存热门查询的结果
 *
 * @author shilong.zang
 */
@Service
public class RAGCacheService {

    private static final Logger log = LoggerFactory.getLogger(RAGCacheService.class);
    
    /**
     * 缓存容量上限
     */
    @Value("${rag.cache.max-size:100}")
    private int maxCacheSize;
    
    /**
     * 缓存过期时间（秒）
     */
    @Value("${rag.cache.expiration-seconds:1800}")
    private long cacheExpirationSeconds;
    
    /**
     * 缓存命中计数器
     */
    private int cacheHits = 0;
    
    /**
     * 缓存未命中计数器
     */
    private int cacheMisses = 0;
    
    /**
     * 缓存数据结构
     * key: 缓存键（查询+数据集组合的哈希）
     * value: 缓存条目（包含结果和元数据）
     */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    
    /**
     * 从缓存获取查询结果
     * 
     * @param query 查询文本
     * @param dataSetIds 数据集ID列表
     * @return 缓存的文档结果，如果缓存未命中则返回null
     */
    public List<DocumentUnitEntity> getFromCache(String query, List<String> dataSetIds) {
        // 生成缓存键
        String cacheKey = generateCacheKey(query, dataSetIds);
        
        // 尝试从缓存获取
        CacheEntry entry = cache.get(cacheKey);
        
        // 检查缓存是否存在且未过期
        if (entry != null && !isExpired(entry)) {
            // 缓存命中统计
            cacheHits++;
            log.debug("缓存命中: 查询='{}', 数据集={}, 命中率={}%", 
                    query, dataSetIds, calculateHitRate());
            return entry.getDocuments();
        }
        
        // 缓存未命中统计
        cacheMisses++;
        log.debug("缓存未命中: 查询='{}', 数据集={}", query, dataSetIds);
        
        // 如果条目过期，从缓存中移除
        if (entry != null && isExpired(entry)) {
            cache.remove(cacheKey);
        }
        
        return null;
    }
    
    /**
     * 将查询结果存入缓存
     * 
     * @param query 查询文本
     * @param dataSetIds 数据集ID列表
     * @param documents 文档结果
     */
    public void putToCache(String query, List<String> dataSetIds, List<DocumentUnitEntity> documents) {
        // 生成缓存键
        String cacheKey = generateCacheKey(query, dataSetIds);
        
        // 如果缓存已满，移除最旧的条目
        if (cache.size() >= maxCacheSize) {
            pruneCache();
        }
        
        // 创建新的缓存条目
        CacheEntry entry = new CacheEntry(documents, Instant.now());
        
        // 存入缓存
        cache.put(cacheKey, entry);
        log.debug("缓存添加: 查询='{}', 数据集={}, 文档数={}", 
                query, dataSetIds, documents.size());
    }
    
    /**
     * 清除过期缓存条目
     */
    public void clearExpiredEntries() {
        int beforeSize = cache.size();
        
        // 移除所有过期的条目
        cache.entrySet().removeIf(entry -> isExpired(entry.getValue()));
        
        int afterSize = cache.size();
        if (beforeSize > afterSize) {
            log.info("缓存清理: 移除了{}个过期条目", beforeSize - afterSize);
        }
    }
    
    /**
     * 重置缓存
     */
    public void resetCache() {
        cache.clear();
        cacheHits = 0;
        cacheMisses = 0;
        log.info("缓存已重置");
    }
    
    /**
     * 生成缓存键
     */
    private String generateCacheKey(String query, List<String> dataSetIds) {
        // 按字母序排序数据集ID以确保一致性
        List<String> sortedIds = dataSetIds.stream().sorted().toList();
        // 组合查询和数据集ID生成唯一键
        return query.trim().toLowerCase() + ":" + String.join(",", sortedIds);
    }
    
    /**
     * 检查缓存条目是否过期
     */
    private boolean isExpired(CacheEntry entry) {
        return Duration.between(entry.getCreationTime(), Instant.now())
                .getSeconds() > cacheExpirationSeconds;
    }
    
    /**
     * 当缓存满时，移除最老的条目
     */
    private void pruneCache() {
        // 找到最旧的缓存条目
        Map.Entry<String, CacheEntry> oldest = cache.entrySet().stream()
                .min(Map.Entry.comparingByValue((e1, e2) -> 
                        e1.getCreationTime().compareTo(e2.getCreationTime())))
                .orElse(null);
        
        // 移除最旧的条目
        if (oldest != null) {
            cache.remove(oldest.getKey());
            log.debug("缓存淘汰: 移除最旧条目, 创建时间={}", oldest.getValue().getCreationTime());
        }
    }
    
    /**
     * 计算缓存命中率
     */
    private double calculateHitRate() {
        int total = cacheHits + cacheMisses;
        return total > 0 ? (double) cacheHits / total * 100 : 0;
    }
    
    /**
     * 获取缓存统计信息
     */
    public CacheStats getCacheStats() {
        return new CacheStats(
                cache.size(),
                maxCacheSize, 
                cacheHits, 
                cacheMisses, 
                calculateHitRate(),
                cacheExpirationSeconds
        );
    }
    
    /**
     * 缓存条目类
     */
    private static class CacheEntry {
        private final List<DocumentUnitEntity> documents;
        private final Instant creationTime;
        
        public CacheEntry(List<DocumentUnitEntity> documents, Instant creationTime) {
            this.documents = documents;
            this.creationTime = creationTime;
        }
        
        public List<DocumentUnitEntity> getDocuments() {
            return documents;
        }
        
        public Instant getCreationTime() {
            return creationTime;
        }
    }
    
    /**
     * 缓存统计信息
     */
    public static class CacheStats {
        private final int currentSize;
        private final int maxSize;
        private final int hits;
        private final int misses;
        private final double hitRate;
        private final long expirationSeconds;
        
        public CacheStats(int currentSize, int maxSize, int hits, int misses, 
                         double hitRate, long expirationSeconds) {
            this.currentSize = currentSize;
            this.maxSize = maxSize;
            this.hits = hits;
            this.misses = misses;
            this.hitRate = hitRate;
            this.expirationSeconds = expirationSeconds;
        }
        
        public int getCurrentSize() {
            return currentSize;
        }
        
        public int getMaxSize() {
            return maxSize;
        }
        
        public int getHits() {
            return hits;
        }
        
        public int getMisses() {
            return misses;
        }
        
        public double getHitRate() {
            return hitRate;
        }
        
        public long getExpirationSeconds() {
            return expirationSeconds;
        }
    }
} 
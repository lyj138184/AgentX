package org.xhy.domain.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 搜索参数服务
 * 根据查询特征动态调整RAG检索参数
 * 
 * @author shilong.zang
 */
@Service
public class SearchParameterService {

    private static final Logger log = LoggerFactory.getLogger(SearchParameterService.class);
    
    /**
     * 默认的最大结果数
     */
    @Value("${rag.search.default-max-results:5}")
    private int defaultMaxResults;
    
    /**
     * 默认的最小相关性分数
     */
    @Value("${rag.search.default-min-score:0.6}")
    private double defaultMinScore;
    
    /**
     * 问答查询模式的最大结果数
     */
    @Value("${rag.search.qa-max-results:3}")
    private int qaMaxResults;
    
    /**
     * 问答查询模式的最小相关性分数
     */
    @Value("${rag.search.qa-min-score:0.7}")
    private double qaMinScore;
    
    /**
     * 探索查询模式的最大结果数
     */
    @Value("${rag.search.exploratory-max-results:8}")
    private int exploratoryMaxResults;
    
    /**
     * 探索查询模式的最小相关性分数
     */
    @Value("${rag.search.exploratory-min-score:0.5}")
    private double exploratoryMinScore;
    
    /**
     * 问句模式的正则表达式
     */
    private static final Pattern QUESTION_PATTERN = Pattern.compile(
            ".*[?？]$|^(什么|如何|怎么|怎样|为什么|是否|能否|可否|哪些|谁|何时|何地|多少).*");
    
    /**
     * 探索模式的关键词
     */
    private static final Pattern EXPLORATORY_PATTERN = Pattern.compile(
            ".*(列出|罗列|总结|比较|区别|综述|概述|整理|归纳|汇总|分类|介绍).*");
    
    /**
     * 根据查询内容动态调整搜索参数
     * 
     * @param query 查询文本
     * @param dataSetIds 数据集ID列表
     * @return 优化后的搜索参数
     */
    public SearchParameters getOptimizedParameters(String query, List<String> dataSetIds) {
        // 参数校验
        if (query == null || query.trim().isEmpty()) {
            return getDefaultParameters();
        }
        
        // 查询类型识别
        QueryType queryType = detectQueryType(query);
        
        // 根据查询类型和数据集特性调整参数
        SearchParameters parameters;
        switch (queryType) {
            case QUESTION_ANSWERING:
                parameters = new SearchParameters(qaMaxResults, qaMinScore);
                break;
            case EXPLORATORY:
                parameters = new SearchParameters(exploratoryMaxResults, exploratoryMinScore);
                break;
            default:
                parameters = getDefaultParameters();
        }
        
        // 根据数据集大小进行额外调整
        if (dataSetIds != null && dataSetIds.size() > 3) {
            // 多数据集查询略微调整参数
            parameters.setMaxResults(parameters.getMaxResults() + 1);
            parameters.setMinScore(Math.max(0.45, parameters.getMinScore() - 0.05));
        }
        
        log.debug("查询参数优化: 查询='{}', 类型={}, 最大结果={}, 最小分数={}",
                query, queryType, parameters.getMaxResults(), parameters.getMinScore());
        
        return parameters;
    }
    
    /**
     * 检测查询类型
     */
    private QueryType detectQueryType(String query) {
        if (QUESTION_PATTERN.matcher(query).matches()) {
            return QueryType.QUESTION_ANSWERING;
        } else if (EXPLORATORY_PATTERN.matcher(query).matches()) {
            return QueryType.EXPLORATORY;
        } else {
            return QueryType.GENERAL;
        }
    }
    
    /**
     * 获取默认参数
     */
    private SearchParameters getDefaultParameters() {
        return new SearchParameters(defaultMaxResults, defaultMinScore);
    }
    
    /**
     * 查询类型枚举
     */
    public enum QueryType {
        QUESTION_ANSWERING,  // 问答型查询（如"什么是..."）
        EXPLORATORY,         // 探索型查询（如"列出所有..."）
        GENERAL              // 一般查询
    }
    
    /**
     * 搜索参数类
     */
    public static class SearchParameters {
        private int maxResults;
        private double minScore;
        
        public SearchParameters(int maxResults, double minScore) {
            this.maxResults = maxResults;
            this.minScore = minScore;
        }
        
        public int getMaxResults() {
            return maxResults;
        }
        
        public void setMaxResults(int maxResults) {
            this.maxResults = maxResults;
        }
        
        public double getMinScore() {
            return minScore;
        }
        
        public void setMinScore(double minScore) {
            this.minScore = minScore;
        }
    }
} 
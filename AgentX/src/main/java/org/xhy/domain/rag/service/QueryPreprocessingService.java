package org.xhy.domain.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查询预处理服务
 * 负责分析和优化查询，提高RAG检索质量
 *
 * @author shilong.zang
 */
@Component
public class QueryPreprocessingService {

    private static final Logger log = LoggerFactory.getLogger(QueryPreprocessingService.class);
    
    // 停用词列表
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "的", "了", "是", "在", "我", "有", "和", "就", "不", "人", "都", 
            "一", "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着",
            "没有", "看", "好", "自己", "这", "这个", "那", "那个", "可以", "什么", "给"
    ));
    
    private final OpenAiEmbeddingModel embeddingModel;
    
    public QueryPreprocessingService(OpenAiEmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 处理查询文本，进行分析和优化
     *
     * @param query 原始查询文本
     * @return 处理后的查询文本和关键词
     */
    public QueryResult processQuery(String query) {
        if (StrUtil.isBlank(query)) {
            log.warn("输入查询为空");
            return new QueryResult(query, new ArrayList<>());
        }
        
        // 提取关键词
        List<String> keywords = extractKeywords(query);
        
        // 扩展或重写查询文本
        String processedQuery = query;
        
        // 根据查询特征进行优化
        if (isQuestionQuery(query)) {
            processedQuery = optimizeQuestionQuery(query);
        }
        
        log.debug("查询预处理: 原始查询='{}', 处理后查询='{}', 关键词={}", query, processedQuery, keywords);
        return new QueryResult(processedQuery, keywords);
    }
    
    /**
     * 提取查询中的关键词
     */
    private List<String> extractKeywords(String query) {
        List<String> keywords = new ArrayList<>();
        
        // 简单分词，实际项目中可使用更复杂的NLP分词工具
        String[] words = query.split("\\s+|，|。|？|！|、|；|:|：|,|\\.|\\?|!|;");
        
        for (String word : words) {
            word = word.trim();
            // 过滤停用词和过短词汇
            if (!word.isEmpty() && word.length() > 1 && !STOP_WORDS.contains(word)) {
                keywords.add(word);
            }
        }
        
        return keywords;
    }
    
    /**
     * 判断是否为问句查询
     */
    private boolean isQuestionQuery(String query) {
        // 检查是否包含问号或常见问句词语
        Pattern pattern = Pattern.compile(".*[?？]$|^(什么|如何|怎么|怎样|为什么|是否|能否|可否|哪些|谁|何时|何地|多少).*");
        Matcher matcher = pattern.matcher(query);
        return matcher.matches();
    }
    
    /**
     * 优化问句查询
     */
    private String optimizeQuestionQuery(String query) {
        // 移除一些常见的问句引导词，让查询更聚焦于主题
        return query.replaceAll("^(请问|请告诉我|我想知道|能否告诉我)", "")
                .replaceAll("(吗|呢|啊|呀|哈|哇)$", "");
    }
    
    /**
     * 查询处理结果
     */
    public static class QueryResult {
        private final String processedQuery;
        private final List<String> keywords;
        
        public QueryResult(String processedQuery, List<String> keywords) {
            this.processedQuery = processedQuery;
            this.keywords = keywords;
        }
        
        public String getProcessedQuery() {
            return processedQuery;
        }
        
        public List<String> getKeywords() {
            return keywords;
        }
    }
} 
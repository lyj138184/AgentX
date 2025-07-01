package org.xhy.domain.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import cn.hutool.core.util.StrUtil;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义分块服务
 * 基于内容语义和结构进行智能文档分块
 *
 * @author shilong.zang
 */
@Component
public class SemanticChunkingService {

    private static final Logger log = LoggerFactory.getLogger(SemanticChunkingService.class);
    
    /**
     * 块大小（字符数）默认限制
     */
    @Value("${rag.chunk.max-size:1024}")
    private int maxChunkSize;
    
    /**
     * 块重叠范围（字符数）
     */
    @Value("${rag.chunk.overlap:100}")
    private int chunkOverlap;
    
    /**
     * 标题和大纲标记模式
     */
    private static final Pattern HEADER_PATTERN = Pattern.compile(
            "^(#+\\s+.*$)|^(第[一二三四五六七八九十百千万零\\d]+[章节篇部分].*$)",
            Pattern.MULTILINE);
    
    /**
     * 分隔符检测模式（用于检测可能的语义边界）
     */
    private static final Pattern SEPARATOR_PATTERN = Pattern.compile(
            "[。！？.!?;；\\n\\r]{1,2}\\s*");
    
    /**
     * 进行语义化分块
     *
     * @param content 待分块内容
     * @return 分块后的内容列表，保留语义完整性
     */
    public List<TextChunk> semanticChunking(String content) {
        if (StrUtil.isBlank(content)) {
            return Collections.emptyList();
        }
        
        // 获取文档结构（标题和章节分隔）
        List<DocumentMarker> markers = identifyStructure(content);
        
        // 基于结构和语义边界进行分块
        List<TextChunk> chunks = splitByStructure(content, markers);
        
        log.info("语义分块完成: 原始内容长度={}, 分块数量={}", content.length(), chunks.size());
        return chunks;
    }
    
    /**
     * 识别文档结构，找出标题和章节标记
     */
    private List<DocumentMarker> identifyStructure(String content) {
        List<DocumentMarker> markers = new ArrayList<>();
        
        // 查找标题和章节标记
        Matcher headerMatcher = HEADER_PATTERN.matcher(content);
        while (headerMatcher.find()) {
            markers.add(new DocumentMarker(
                    headerMatcher.start(),
                    headerMatcher.end(),
                    MarkerType.HEADER,
                    headerMatcher.group()
            ));
        }
        
        // 查找自然段落（通过空行分隔）
        Pattern paragraphPattern = Pattern.compile("\\n\\s*\\n");
        Matcher paragraphMatcher = paragraphPattern.matcher(content);
        while (paragraphMatcher.find()) {
            markers.add(new DocumentMarker(
                    paragraphMatcher.start(),
                    paragraphMatcher.end(),
                    MarkerType.PARAGRAPH_BREAK,
                    null
            ));
        }
        
        // 排序所有标记（按位置）
        markers.sort(Comparator.comparingInt(DocumentMarker::getStart));
        
        return markers;
    }
    
    /**
     * 基于文档结构和语义边界进行分块
     */
    private List<TextChunk> splitByStructure(String content, List<DocumentMarker> markers) {
        List<TextChunk> chunks = new ArrayList<>();
        
        // 如果没有明显结构，使用语义边界分块
        if (markers.isEmpty()) {
            return splitBySentence(content);
        }
        
        int currentPos = 0;
        int currentHeaderLevel = 0;
        String currentHeader = null;
        StringBuilder currentChunk = new StringBuilder();
        Map<String, String> metadata = new HashMap<>();
        
        // 遍历所有标记点
        for (int i = 0; i < markers.size(); i++) {
            DocumentMarker marker = markers.get(i);
            
            // 添加当前标记之前的内容
            if (marker.getStart() > currentPos) {
                currentChunk.append(content, currentPos, marker.getStart());
            }
            
            // 处理标记
            if (marker.getType() == MarkerType.HEADER) {
                // 如果当前块不为空，保存它
                if (currentChunk.length() > 0) {
                    chunks.add(createChunk(currentChunk.toString(), currentHeader, metadata));
                    currentChunk = new StringBuilder();
                }
                
                // 更新当前标题信息
                currentHeader = marker.getContent();
                currentHeaderLevel = getHeaderLevel(currentHeader);
                
                // 添加标题到元数据
                metadata = new HashMap<>();
                metadata.put("header", currentHeader);
                metadata.put("level", String.valueOf(currentHeaderLevel));
                
                // 添加标题到当前块
                currentChunk.append(marker.getContent());
            } else if (marker.getType() == MarkerType.PARAGRAPH_BREAK) {
                // 处理段落分隔
                
                // 如果当前块超过最大尺寸，保存并启动新块
                if (currentChunk.length() >= maxChunkSize) {
                    chunks.add(createChunk(currentChunk.toString(), currentHeader, metadata));
                    
                    // 新块保留上下文（如标题信息）
                    currentChunk = new StringBuilder();
                    if (currentHeader != null) {
                        metadata = new HashMap<>();
                        metadata.put("header", currentHeader);
                        metadata.put("level", String.valueOf(currentHeaderLevel));
                    }
                } 
                
                // 添加段落分隔
                currentChunk.append(content, marker.getStart(), marker.getEnd());
            }
            
            // 更新当前位置
            currentPos = marker.getEnd();
            
            // 处理最后一个块或中间大块
            if (i == markers.size() - 1 || currentChunk.length() >= maxChunkSize) {
                // 如果是最后一个标记，添加剩余内容
                if (i == markers.size() - 1 && currentPos < content.length()) {
                    currentChunk.append(content, currentPos, content.length());
                }
                
                // 如果块太大，进一步按句子分割
                if (currentChunk.length() > maxChunkSize * 1.5) {
                    List<TextChunk> sentenceChunks = splitChunkBySentence(
                            currentChunk.toString(), currentHeader, metadata);
                    chunks.addAll(sentenceChunks);
                    currentChunk = new StringBuilder();
                }
                // 处理最后的内容
                else if (i == markers.size() - 1 && currentChunk.length() > 0) {
                    chunks.add(createChunk(currentChunk.toString(), currentHeader, metadata));
                }
            }
        }
        
        // 处理末尾剩余内容
        if (currentPos < content.length()) {
            currentChunk.append(content, currentPos, content.length());
            if (currentChunk.length() > 0) {
                if (currentChunk.length() > maxChunkSize * 1.5) {
                    chunks.addAll(splitChunkBySentence(
                            currentChunk.toString(), currentHeader, metadata));
                } else {
                    chunks.add(createChunk(currentChunk.toString(), currentHeader, metadata));
                }
            }
        }
        
        return chunks;
    }
    
    /**
     * 按句子分割内容
     */
    private List<TextChunk> splitBySentence(String content) {
        List<TextChunk> chunks = new ArrayList<>();
        Matcher sentenceMatcher = SEPARATOR_PATTERN.matcher(content);
        
        int start = 0;
        StringBuilder currentChunk = new StringBuilder();
        
        while (sentenceMatcher.find()) {
            int end = sentenceMatcher.end();
            String sentence = content.substring(start, end);
            
            // 如果添加这个句子会超过最大块大小，先保存当前块
            if (currentChunk.length() + sentence.length() > maxChunkSize && currentChunk.length() > 0) {
                chunks.add(createChunk(currentChunk.toString(), null, null));
                
                // 重叠处理：新块从上一块末尾的部分句子开始
                if (currentChunk.length() > chunkOverlap) {
                    String overlap = getLastSentences(currentChunk.toString(), chunkOverlap);
                    currentChunk = new StringBuilder(overlap);
                } else {
                    currentChunk = new StringBuilder();
                }
            }
            
            // 添加句子到当前块
            currentChunk.append(sentence);
            start = end;
        }
        
        // 处理最后一个块
        if (start < content.length()) {
            currentChunk.append(content.substring(start));
        }
        
        if (currentChunk.length() > 0) {
            chunks.add(createChunk(currentChunk.toString(), null, null));
        }
        
        return chunks;
    }
    
    /**
     * 将大块按句子分割
     */
    private List<TextChunk> splitChunkBySentence(String content, String header, Map<String, String> metadata) {
        List<TextChunk> chunks = splitBySentence(content);
        
        // 添加块元数据（如标题信息）
        if (header != null) {
            for (TextChunk chunk : chunks) {
                chunk.getMetadata().put("header", header);
                if (metadata != null) {
                    chunk.getMetadata().putAll(metadata);
                }
            }
        }
        
        return chunks;
    }
    
    /**
     * 提取文本中最后几个句子
     */
    private String getLastSentences(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        
        // 从后向前查找句子分隔符
        int pos = text.length() - maxLength;
        while (pos < text.length()) {
            if (pos > 0 && SEPARATOR_PATTERN.matcher(text.substring(pos - 1, pos + 1)).matches()) {
                break;
            }
            pos++;
        }
        
        return text.substring(pos);
    }
    
    /**
     * 创建块
     */
    private TextChunk createChunk(String content, String header, Map<String, ?> metadata) {
        TextChunk chunk = new TextChunk(content.trim());
        
        // 添加标题信息
        if (StringUtils.hasText(header)) {
            chunk.getMetadata().put("header", header);
        }
        
        // 添加其他元数据
        if (metadata != null) {
            metadata.forEach((key, value) -> {
                if (value != null) {
                    chunk.getMetadata().put(key, value);
                }
            });
        }
        
        return chunk;
    }
    
    /**
     * 获取标题级别
     */
    private int getHeaderLevel(String header) {
        if (header.startsWith("#")) {
            // Markdown风格标题
            int level = 0;
            for (char c : header.toCharArray()) {
                if (c == '#') {
                    level++;
                } else {
                    break;
                }
            }
            return level;
        } else {
            // 中文标题（第X章、节等）
            if (header.contains("章") || header.contains("篇")) {
                return 1;
            } else if (header.contains("节")) {
                return 2;
            } else {
                return 3; // 其他级别
            }
        }
    }
    
    /**
     * 文档标记类型
     */
    private enum MarkerType {
        HEADER,         // 标题
        PARAGRAPH_BREAK // 段落分隔
    }
    
    /**
     * 文档结构标记
     */
    private static class DocumentMarker {
        private final int start;
        private final int end;
        private final MarkerType type;
        private final String content;
        
        public DocumentMarker(int start, int end, MarkerType type, String content) {
            this.start = start;
            this.end = end;
            this.type = type;
            this.content = content;
        }
        
        public int getStart() {
            return start;
        }
        
        public int getEnd() {
            return end;
        }
        
        public MarkerType getType() {
            return type;
        }
        
        public String getContent() {
            return content;
        }
    }
    
    /**
     * 文本块类
     * 包含分块内容和相关元数据
     */
    public static class TextChunk {
        private final String content;
        private final Map<String, Object> metadata;
        
        public TextChunk(String content) {
            this.content = content;
            this.metadata = new HashMap<>();
        }
        
        public String getContent() {
            return content;
        }
        
        public Map<String, Object> getMetadata() {
            return metadata;
        }
        
        @Override
        public String toString() {
            return "TextChunk{" +
                    "length=" + (content != null ? content.length() : 0) +
                    ", metadata=" + metadata +
                    '}';
        }
    }
} 
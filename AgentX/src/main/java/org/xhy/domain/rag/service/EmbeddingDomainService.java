package org.xhy.domain.rag.service;

import dev.langchain4j.store.embedding.EmbeddingMatch;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.dromara.streamquery.stream.core.stream.Steam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.xhy.domain.rag.message.RagDocSyncStorageMessage;
import org.xhy.domain.rag.constant.EmbeddingStatus;
import org.xhy.domain.rag.constant.FileInitializeStatus;
import org.xhy.domain.rag.constant.MetadataConstant;
import org.xhy.domain.rag.model.DocumentUnitEntity;
import org.xhy.domain.rag.model.FileDetailEntity;
import org.xhy.domain.rag.repository.DocumentUnitRepository;
import org.xhy.domain.rag.repository.FileDetailRepository;
import org.xhy.domain.rag.service.QueryPreprocessingService.QueryResult;
import org.xhy.domain.rag.service.SearchParameterService.SearchParameters;
import org.xhy.infrastructure.mq.enums.EventType;
import org.xhy.infrastructure.mq.events.RagDocSyncStorageEvent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;

/**
 * 向量话存储
 *
 * @author shilong.zang
 * @date 18:28 <br/>
 */
@Component
public class EmbeddingDomainService implements MetadataConstant {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingDomainService.class);

    private final OpenAiEmbeddingModel openAiEmbeddingModel;

    private final ApplicationContext applicationContext;

    private final EmbeddingStore<TextSegment> embeddingStore;

    private final FileDetailRepository fileDetailRepository;

    private final DocumentUnitRepository documentUnitRepository;

    private final RerankDomainService rerankService;
    
    private final QueryPreprocessingService queryPreprocessingService;
    
    private final RAGCacheService ragCacheService;
    
    private final SearchParameterService searchParameterService;
    
    private final SemanticChunkingService semanticChunkingService;

    public EmbeddingDomainService(OpenAiEmbeddingModel openAiEmbeddingModel, 
                                  EmbeddingStore<TextSegment> embeddingStore,
                                  FileDetailRepository fileDetailRepository, 
                                  ApplicationContext applicationContext,
                                  DocumentUnitRepository documentUnitRepository, 
                                  RerankDomainService rerankService,
                                  QueryPreprocessingService queryPreprocessingService,
                                  RAGCacheService ragCacheService,
                                  SearchParameterService searchParameterService,
                                  SemanticChunkingService semanticChunkingService) {
        this.openAiEmbeddingModel = openAiEmbeddingModel;
        this.embeddingStore = embeddingStore;
        this.fileDetailRepository = fileDetailRepository;
        this.applicationContext = applicationContext;
        this.documentUnitRepository = documentUnitRepository;
        this.rerankService = rerankService;
        this.queryPreprocessingService = queryPreprocessingService;
        this.ragCacheService = ragCacheService;
        this.searchParameterService = searchParameterService;
        this.semanticChunkingService = semanticChunkingService;
    }

    /**
     * 基于混合检索的RAG文档获取
     * 
     * @param dataSetId 知识库ids
     * @param question 查询内容
     * @param maxResults 最大返回结果数（可选，为null时会自动调整）
     * @param minScore 最小相关性分数（可选，为null时会自动调整）
     * @return List<Document> 文档列表
     */
    public List<DocumentUnitEntity> ragDoc(List<String> dataSetId, String question, Integer maxResults, Double minScore) {
        // 参数校验
        if (StrUtil.hasBlank(question)) {
            log.warn("Question is empty");
            throw new IllegalArgumentException("Question is empty");
        }

        if (ObjectUtil.isEmpty(dataSetId)) {
            log.warn("DataSetId is empty");
            throw new IllegalArgumentException("dataSetId is empty");
        }
        
        // 尝试从缓存获取结果
        List<DocumentUnitEntity> cachedResult = ragCacheService.getFromCache(question, dataSetId);
        if (cachedResult != null) {
            log.info("使用缓存结果: 查询='{}', 数据集={}, 结果数量={}", 
                    question, dataSetId, cachedResult.size());
            return cachedResult;
        }
        
        // 查询预处理
        QueryResult queryResult = queryPreprocessingService.processQuery(question);
        String processedQuery = queryResult.getProcessedQuery();
        List<String> keywords = queryResult.getKeywords();
        
        log.info("RAG查询处理：原始查询='{}', 处理后查询='{}', 提取关键词={}", 
                question, processedQuery, keywords);
        
        // 动态调整搜索参数
        SearchParameters parameters = searchParameterService.getOptimizedParameters(question, dataSetId);
        
        // 如果外部提供了参数，则优先使用外部参数
        int finalMaxResults = maxResults != null ? maxResults : parameters.getMaxResults();
        double finalMinScore = minScore != null ? minScore : parameters.getMinScore();
        
        log.debug("RAG检索参数: maxResults={}, minScore={} ({})", 
                finalMaxResults, finalMinScore, 
                (maxResults == null || minScore == null) ? "使用自适应参数" : "使用指定参数");
        
        // 设置向量检索数量
        int vectorSearchResults = Math.min(25, finalMaxResults * 2); // 向量检索数量扩大
        
        // 向量检索 - 始终执行
        List<String> vectorDocIds = performVectorSearch(dataSetId, processedQuery, 
                vectorSearchResults, finalMinScore);
        
        Set<String> resultDocIds = new HashSet<>(vectorDocIds);
        
        // 关键词检索 - 当有足够关键词时执行
        if (keywords.size() >= 2) {
            List<String> keywordDocIds = performKeywordSearch(dataSetId, keywords, finalMaxResults);
            // 合并结果
            resultDocIds.addAll(keywordDocIds);
        }
        
        // 获取最终结果
        if (resultDocIds.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<DocumentUnitEntity> results = documentUnitRepository.selectList(
                Wrappers.lambdaQuery(DocumentUnitEntity.class)
                        .in(DocumentUnitEntity::getId, resultDocIds));
        
        // 将结果存入缓存
        ragCacheService.putToCache(question, dataSetId, results);
        
        return results;
    }
    
    /**
     * 执行向量检索
     */
    private List<String> performVectorSearch(List<String> dataSetId, String query, 
                                           int maxResults, Double minScore) {
        // 向量检索
        final EmbeddingSearchResult<TextSegment> textSegmentList = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .filter(new IsIn(DATA_SET_ID, dataSetId))
                        .maxResults(maxResults)
                        .queryEmbedding(Embedding.from(openAiEmbeddingModel.embed(query).content().vector()))
                        .build());

        // 重排序
        final List<EmbeddingMatch<TextSegment>> embeddingMatches = rerankService.rerankDocument(
                textSegmentList, query, maxResults, minScore);

        // 提取文档ID
        return Steam.of(embeddingMatches)
                .map(match -> {
                    if (match.embedded().metadata().containsKey(DOCUMENT_ID)) {
                        return match.embedded().metadata().getString(DOCUMENT_ID);
                    }
                    return "";
                })
                .filter(StrUtil::isNotBlank)
                .toList();
    }
    
    /**
     * 执行关键词检索
     */
    private List<String> performKeywordSearch(List<String> dataSetId, List<String> keywords, int maxResults) {
        // 构建关键词查询条件
        LambdaQueryWrapper<DocumentUnitEntity> queryWrapper = Wrappers.lambdaQuery(DocumentUnitEntity.class)
                .in(DocumentUnitEntity::getFileId, 
                    // 获取数据集对应的文件ID
                    fileDetailRepository.selectList(
                            Wrappers.lambdaQuery(FileDetailEntity.class)
                                    .in(FileDetailEntity::getDataSetId, dataSetId)
                    ).stream().map(FileDetailEntity::getId).collect(Collectors.toList())
                );
        
        // 添加关键词条件
        for (String keyword : keywords) {
            queryWrapper.or().like(DocumentUnitEntity::getContent, keyword);
        }
        
        // 限制结果数量
        queryWrapper.last("LIMIT " + maxResults);
        
        // 查询并返回ID列表
        return documentUnitRepository.selectList(queryWrapper)
                .stream()
                .map(DocumentUnitEntity::getId)
                .collect(Collectors.toList());
    }
    
    /**
     * 对文档内容进行语义分块
     * 用于优化文档的存储和检索效果
     * 
     * @param content 文档内容
     * @return 分块后的内容列表
     */
    public List<SemanticChunkingService.TextChunk> chunkDocument(String content) {
        return semanticChunkingService.semanticChunking(content);
    }
    
    /**
     * 清除RAG缓存
     * 
     * @param dataSetId 如果指定，则只清除该数据集相关的缓存；否则清除所有缓存
     */
    public void clearCache(String dataSetId) {
        if (dataSetId == null) {
            ragCacheService.resetCache();
            log.info("已清除所有RAG查询缓存");
        } else {
            // 这里只能重置所有缓存，因为当前实现不支持按数据集选择性清除
            // 未来可扩展RAGCacheService支持更精细的缓存管理
            ragCacheService.resetCache();
            log.info("已清除数据集 {} 的RAG查询缓存", dataSetId);
        }
    }

    /**
     * 批量删除向量数据
     *
     * @param embeddingIds 向量数据id
     */
    public void deleteEmbedding(List<String> embeddingIds) {
        embeddingStore.removeAll(embeddingIds);
    }

    /**
     * 重新向量入库
     *
     * @param fileId 文件ID
     * @return 处理结果，true表示成功，false表示失败
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean reindexEmbedding(String fileId) {

        if (!StringUtils.hasText(fileId)) {
            log.warn("File ID for re-vectorization is empty");
            return false;
        }

        try {
            // 获取文件详情
            FileDetailEntity fileDetail = fileDetailRepository.selectById(fileId);
            if (fileDetail == null) {
                log.warn("File with ID {}not found", fileId);
                return false;
            }

            if (ObjectUtil.notEqual(fileDetail.getIsInitialize(), FileInitializeStatus.INITIALIZED)) {
                log.warn("The file with ID {} has no data", fileId);
            }

            // 更新文件状态为入库中
            fileDetail.setIsEmbedding(EmbeddingStatus.INITIALIZING);
            fileDetailRepository.updateById(fileDetail);

            // 删除旧向量数据
            removeEmbeddingByFileId(fileId);
            
            // 清除缓存，因为数据已更新
            clearCache(fileDetail.getDataSetId());

            // 获取当前文件的所有文档
            List<DocumentUnitEntity> documentUnitEntities = documentUnitRepository.selectList(
                    Wrappers.lambdaQuery(DocumentUnitEntity.class)
                            .eq(DocumentUnitEntity::getFileId, fileId)
            );
            
            // 将文档状态设置为未向量化
            if (!documentUnitEntities.isEmpty()) {
                documentUnitRepository.update(
                        Wrappers.lambdaUpdate(DocumentUnitEntity.class)
                                .eq(DocumentUnitEntity::getFileId, fileId)
                                .set(DocumentUnitEntity::getVector, false)
                );
            }

            // 重新入库
            indexEmbedding(documentUnitEntities);

            return true;
        } catch (Exception e) {
            log.error("File {} re-vectorization and storage failed", fileId, e);
            // 更新文件状态为入库失败
            try {
                FileDetailEntity fileDetail = fileDetailRepository.selectById(fileId);
                if (fileDetail != null) {
                    fileDetail.setIsEmbedding(EmbeddingStatus.INITIALIZATION_FAILED);
                    fileDetailRepository.updateById(fileDetail);
                }
            } catch (Exception ex) {
                log.error("Failed to update file status", ex);
            }
            return false;
        }
    }

    /**
     * 获取与文件关联的向量ID列表
     *
     * @param fileId 文件ID
     */
    private void removeEmbeddingByFileId(String fileId) {
        embeddingStore.removeAll(new IsEqualTo(FILE_ID, fileId));
    }

    /**
     * 批量向量化入库
     */
    private void indexEmbedding(List<DocumentUnitEntity> documentUnitEntityList) {
        Steam.of(documentUnitEntityList).forEach(documentUnit -> applicationContext.publishEvent(
                new RagDocSyncStorageEvent<>(documentUnit, EventType.DOC_SYNC_RAG)));
    }

    /**
     * 文本向量化
     */
    public void syncStorage(RagDocSyncStorageMessage ragDocSyncStorageMessage) {

        final String docId = ragDocSyncStorageMessage.getId();

        final DocumentUnitEntity documentUnitEntity = documentUnitRepository.selectById(docId);

        final FileDetailEntity fileDetailEntity = fileDetailRepository.selectById(ragDocSyncStorageMessage.getFileId());
        if (documentUnitEntity == null) {
            return;
        }

        final String content = documentUnitEntity.getContent();

        final Metadata documentMetadata = buildMetadata(ragDocSyncStorageMessage);

        final TextSegment textSegment = new TextSegment(content, documentMetadata);

        // 生成嵌入向量
        Embedding embeddings = openAiEmbeddingModel.embed(textSegment).content();

        embeddingStore.add(embeddings);

        documentUnitRepository.update(
                Wrappers.lambdaUpdate(DocumentUnitEntity.class).eq(DocumentUnitEntity::getId, docId)
                        .set(DocumentUnitEntity::getVector, true));

        // 修改文件状态
        final Integer pageSize = fileDetailEntity.getFilePageSize();

        final Long isVector = documentUnitRepository.selectCount(Wrappers.lambdaQuery(DocumentUnitEntity.class)
                .eq(DocumentUnitEntity::getFileId, documentUnitEntity.getFileId())
                .eq(DocumentUnitEntity::getVector, true));

        final Integer anInt = Convert.toInt(isVector);

        if (anInt >= pageSize) {
            fileDetailRepository.update(
                    Wrappers.lambdaUpdate(FileDetailEntity.class).eq(FileDetailEntity::getId, fileDetailEntity.getId())
                            .set(FileDetailEntity::getIsEmbedding, EmbeddingStatus.INITIALIZED));
        }

    }

    private Metadata buildMetadata(RagDocSyncStorageMessage ragDocSyncStorageMessage) {

        final Metadata metadata = new Metadata();
        metadata.put(FILE_ID, ragDocSyncStorageMessage.getFileId());
        metadata.put(FILE_NAME, ragDocSyncStorageMessage.getFileName());
        metadata.put(DOCUMENT_ID, ragDocSyncStorageMessage.getId());
        return metadata;
    }
} 
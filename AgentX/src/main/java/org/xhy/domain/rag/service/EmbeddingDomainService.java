package org.xhy.domain.rag.service;

import dev.langchain4j.store.embedding.EmbeddingMatch;
import java.util.ArrayList;
import java.util.List;

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
import org.xhy.infrastructure.mq.enums.EventType;
import org.xhy.infrastructure.mq.events.RagDocSyncStorageEvent;

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

    public EmbeddingDomainService(OpenAiEmbeddingModel openAiEmbeddingModel, EmbeddingStore<TextSegment> embeddingStore,
                                  FileDetailRepository fileDetailRepository, ApplicationContext applicationContext,
                                  DocumentUnitRepository documentUnitRepository, RerankDomainService rerankService) {
        this.openAiEmbeddingModel = openAiEmbeddingModel;
        this.embeddingStore = embeddingStore;
        this.fileDetailRepository = fileDetailRepository;
        this.applicationContext = applicationContext;
        this.documentUnitRepository = documentUnitRepository;
        this.rerankService = rerankService;
    }

    /**
     * @param dataSetId 知识库ids
     * @param question 内容
     * @return List<Document> 文档列表
     */
    public List<DocumentUnitEntity> ragDoc(List<String> dataSetId, String question, Integer maxResults, Double minScore) {

        if (StrUtil.hasBlank(question)) {
            log.warn("Question is empty");
            throw new IllegalArgumentException("Question is empty");
        }

        if (ObjectUtil.isEmpty(dataSetId)) {
            log.warn("DataSetId is empty");
            throw new IllegalArgumentException("dataSetId is empty");
        }

        final EmbeddingSearchResult<TextSegment> textSegmentList = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .filter(new IsIn(DATA_SET_ID, dataSetId))
                        .maxResults(25)
                        .queryEmbedding(Embedding.from(openAiEmbeddingModel.embed(question).content().vector()))
                        .build());

        final List<EmbeddingMatch<TextSegment>> embeddingMatches = rerankService.rerankDocument(textSegmentList,
                question,maxResults, minScore);

        final List<String> documentId = Steam.of(embeddingMatches).map(textSegmentEmbeddingSearchResult -> {

            if (textSegmentEmbeddingSearchResult.embedded().metadata().containsKey(DOCUMENT_ID)) {
                return textSegmentEmbeddingSearchResult.embedded().metadata().getString(DOCUMENT_ID);
            }
            return "";
        }).filter(StrUtil::isNotBlank).toList();

        return documentUnitRepository.selectList(
                Wrappers.lambdaQuery(DocumentUnitEntity.class).in(DocumentUnitEntity::getId, documentId));

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

            // Todo 获取当前文件的所有文档id
            //Todo 设置文档表为未向量化
            final List<DocumentUnitEntity> documentUnitEntities = new ArrayList<>();

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

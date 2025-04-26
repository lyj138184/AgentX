package org.xhy.domain.rag.service;

import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.xhy.domain.rag.dto.req.RerankRequest;
import org.xhy.domain.rag.dto.resp.RerankResponse;
import org.xhy.infrastructure.rag.client.RerankClient;
import org.xhy.infrastructure.rag.config.RerankProperties;

/**
 * @author shilong.zang
 * @date 16:11 <br/>
 */
@Service
public class RerankDomainService {

    private final RerankProperties rerankProperties;

    private final RerankClient rerankClient;

    public RerankDomainService(RerankProperties rerankProperties, RerankClient rerankClient) {
        this.rerankProperties = rerankProperties;
        this.rerankClient = rerankClient;
    }


    public List<EmbeddingMatch<TextSegment>> rerankDocument(EmbeddingSearchResult<TextSegment> textSegmentEmbeddingSearchResult,String question) {

        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();

        final List<String> list = textSegmentEmbeddingSearchResult.matches().stream().map(text -> text.embedded().text()).toList();

        final RerankRequest rerankRequest = new RerankRequest();
        rerankRequest.setModel(rerankProperties.getModel());
        rerankRequest.setQuery(question);
        rerankRequest.setDocuments(list);

        final RerankResponse rerankResponse = rerankClient.rerank(rerankRequest);

        final List<RerankResponse.SearchResult> results = rerankResponse.getResults();

        results.forEach(result -> {
            final Integer index = result.getIndex();
            matches.add(textSegmentEmbeddingSearchResult.matches().get(index));
        });

        return matches;

    }

}

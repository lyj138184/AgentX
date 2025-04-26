package org.xhy.infrastructure.rag.client;

import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.xhy.domain.rag.dto.req.RerankRequest;
import org.xhy.domain.rag.dto.resp.RerankResponse;
import org.xhy.infrastructure.rag.config.RerankProperties;

/**
 * @author shilong.zang
 * @date 17:11 <br/>
 */
@Service
public class RerankClient {


    private final RerankProperties rerankProperties;

    public RerankClient(RerankProperties rerankProperties) {
        this.rerankProperties = rerankProperties;
    }


    public RerankResponse rerank(RerankRequest rerankRequest) {

        final HttpRequest build = HttpRequest.builder()
                .addHeader("accept", "application/json")
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .addHeader("Authorization", rerankProperties.getApiKey())
                .method(HttpMethod.POST)
                .url(rerankProperties.getApiUrl())
                .body(JSONObject.toJSONString(rerankRequest)).build();

        HttpClient httpClient = new JdkHttpClient(new JdkHttpClientBuilder());
        String response = httpClient.execute(build).body();

        return JSONObject.toJavaObject(JSONObject.parseObject(response),
                RerankResponse.class);
    }
}

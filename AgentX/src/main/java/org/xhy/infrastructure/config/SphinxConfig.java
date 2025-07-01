package org.xhy.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sphx.api.SphinxClient;
import org.sphx.api.SphinxException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sphinx搜索引擎配置
 *
 * @author shilong.zang
 */
@Configuration
public class SphinxConfig {

    private static final Logger log = LoggerFactory.getLogger(SphinxConfig.class);

    @Value("${sphinx.host:localhost}")
    private String sphinxHost;

    @Value("${sphinx.port:9312}")
    private int sphinxPort;

    @Value("${sphinx.timeout:5000}")
    private int sphinxTimeout;

    /**
     * 配置SphinxClient Bean
     *
     * @return SphinxClient实例
     */
    @Bean
    public SphinxClient sphinxClient() {
        SphinxClient client = new SphinxClient();
        try {
            client.SetServer(sphinxHost, sphinxPort);
            client.SetConnectTimeout(sphinxTimeout);
            client.SetArrayResult(true);
            log.info("Sphinx client configured: {}:{}", sphinxHost, sphinxPort);
            return client;
        } catch (SphinxException e) {
            log.error("Failed to initialize Sphinx client", e);
            throw new RuntimeException("Failed to initialize Sphinx client", e);
        }
    }
} 
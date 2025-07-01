package org.xhy.domain.rag.consumer;

import static org.xhy.infrastructure.mq.model.MQSendEventModel.HEADER_NAME_TRACE_ID;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.xhy.domain.rag.message.RagDocSyncStorageMessage;
import org.xhy.domain.rag.service.EmbeddingDomainService;
import org.xhy.infrastructure.mq.events.RagDocSyncStorageEvent;
import org.xhy.infrastructure.mq.model.MqMessage;

/**
 * @author shilong.zang
 * @date 10:34 <br/>
 */
@RabbitListener(bindings = @QueueBinding(value = @Queue(RagDocSyncStorageEvent.QUEUE_NAME),
        exchange = @Exchange(value = RagDocSyncStorageEvent.EXCHANGE_NAME, type = ExchangeTypes.TOPIC),
        key = RagDocSyncStorageEvent.ROUTE_KEY))
@Component
public class RagVectorStoreConsumer {

    private static final Logger log = LoggerFactory.getLogger(RagVectorStoreConsumer.class);

    private final EmbeddingDomainService embeddingService;


    public RagVectorStoreConsumer(EmbeddingDomainService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @RabbitHandler
    public void receiveMessage(Message message, String msg, Channel channel) throws IOException {
        MqMessage mqMessageBody = JSONObject.parseObject(msg, MqMessage.class);

        MDC.put(HEADER_NAME_TRACE_ID, Objects.nonNull(mqMessageBody.getTraceId())
                ? mqMessageBody.getTraceId() : IdWorker.getTimeId());
        MessageProperties messageProperties = message.getMessageProperties();
        long deliveryTag = messageProperties.getDeliveryTag();
        RagDocSyncStorageMessage mqRecordReqDTO = JSON.parseObject(JSON.toJSONString(mqMessageBody.getData()),
                RagDocSyncStorageMessage.class);
        try {
            log.info("Current file {} Page {} ———— Starting vectorization", mqRecordReqDTO.getFileName(),
                    mqRecordReqDTO.getPage());
            embeddingService.syncStorage(mqRecordReqDTO);
            log.info("Current file {} Page {} ———— Vectorization finished", mqRecordReqDTO.getFileName(),
                    mqRecordReqDTO.getPage());
        } catch (Exception e) {
            log.error("Exception occurred during vectorization", e);
        } finally {
            channel.basicAck(deliveryTag, false);
        }
    }

}

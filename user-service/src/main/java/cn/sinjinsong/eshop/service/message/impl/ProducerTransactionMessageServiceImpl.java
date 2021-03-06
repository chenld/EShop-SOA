package cn.sinjinsong.eshop.service.message.impl;

import cn.sinjinsong.eshop.common.domain.dto.message.MessageQueryConditionDTO;
import cn.sinjinsong.eshop.common.domain.entity.message.ProducerTransactionMessageDO;
import cn.sinjinsong.eshop.common.enumeration.message.MessageStatus;
import cn.sinjinsong.eshop.config.MQProducerConfig;
import cn.sinjinsong.eshop.dao.message.ProductTransactionMessageDOMapper;
import cn.sinjinsong.eshop.service.message.ConsumerTransactionMessageService;
import cn.sinjinsong.eshop.service.message.ProducerTransactionMessageService;
import com.alibaba.rocketmq.client.producer.MQProducer;
import com.alibaba.rocketmq.client.producer.SendResult;
import com.alibaba.rocketmq.common.message.Message;
import com.github.pagehelper.PageInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * @author sinjinsong
 * @date 2017/12/26
 */
@Slf4j
public class ProducerTransactionMessageServiceImpl implements ProducerTransactionMessageService {
    @Autowired
    private MQProducer producer;
    @Autowired
    private MQProducerConfig config;
    @Autowired
    private ProductTransactionMessageDOMapper mapper;
    @Autowired
    private ConsumerTransactionMessageService consumerTransactionMessageService;

    @Transactional
    @Override
    public void save(ProducerTransactionMessageDO message) {
        mapper.insert(message);
    }

    @Transactional
    @Override
    public void check() {
        List<Long> all = mapper.findMessageIdsByStatusCreatedAfter(Arrays.asList(MessageStatus.UNCONSUMED, MessageStatus.CONSUME_FAILED), MQProducerConfig.CHECK_GAP);
        Map<Long, MessageStatus> statusMap = consumerTransactionMessageService.findConsumerMessageStatuses(all);
        for (Map.Entry<Long, MessageStatus> entry : statusMap.entrySet()) {
            mapper.updateByPrimaryKeySelective(ProducerTransactionMessageDO.builder().id(entry.getKey()).messageStatus(entry.getValue()).updateTime(LocalDateTime.now()).build());
        }
        all.removeAll(statusMap.keySet());
        // 此时all为确认消息发送失败的
        this.reSend(mapper.selectBatchByPrimaryKeys(all));
    }

    @Transactional
    @Override
    public void reSend(List<ProducerTransactionMessageDO> messages) {
        for (ProducerTransactionMessageDO messageDO : messages) {
            if (messageDO.getSendTimes() == config.getRetryTimes()) {
                messageDO.setUpdateTime(LocalDateTime.now());
                messageDO.setMessageStatus(MessageStatus.OVER_CONFIRM_RETRY_TIME);
                mapper.updateByPrimaryKeySelective(messageDO);
                continue;
            }
            Message message = new Message();
            message.setTopic(config.getTopic());
            message.setBody(messageDO.getBody());
            try {
                SendResult result = producer.send(message);
                messageDO.setSendTimes(messageDO.getSendTimes() + 1);
                messageDO.setUpdateTime(LocalDateTime.now());
                mapper.updateByPrimaryKeySelective(messageDO);
                log.info("发送重试消息完毕,Message:{},result:{}", message, result);
            } catch (Exception e) {
                e.printStackTrace();
                log.info("发送重试消息时失败! Message:{}", message);
            }
        }
    }

    @Transactional
    @Override
    public void delete(Long id) {
        mapper.deleteByPrimaryKey(id);
    }

    @Transactional(readOnly = true)
    @Override
    public List<ProducerTransactionMessageDO> findByIds(List<Long> ids) {
        return mapper.selectBatchByPrimaryKeys(ids);
    }

    @Transactional(readOnly = true)
    @Override
    public PageInfo<ProducerTransactionMessageDO> findByQueryDTO(MessageQueryConditionDTO dto) {
        return mapper.findByCondition(dto, dto.getPageNum(), dto.getPageSize()).toPageInfo();
    }

    @Override
    public void update(ProducerTransactionMessageDO message) {
        mapper.updateByPrimaryKeySelective(message);
    }

}

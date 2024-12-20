package com.transferwise.tasks.demoapp.ninjas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transferwise.common.baseutils.ExceptionUtils;
import com.transferwise.common.context.UnitOfWorkManager;
import com.transferwise.common.gracefulshutdown.GracefulShutdownStrategy;
import com.transferwise.tasks.ITaskDataSerializer;
import com.transferwise.tasks.ITasksService;
import com.transferwise.tasks.demoapp.payout.PayoutInstruction;
import com.transferwise.tasks.helpers.IErrorLoggingThrottler;
import com.transferwise.tasks.helpers.kafka.ConsistentKafkaConsumer;
import com.transferwise.tasks.helpers.kafka.meters.IKafkaListenerMetricsTemplate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class CoreKafkaListener implements GracefulShutdownStrategy, InitializingBean {

  @Autowired
  private ITasksService tasksService;
  @Autowired
  private ObjectMapper objectMapper;
  @Autowired
  private KafkaProperties kafkaProperties;
  @Autowired
  private IErrorLoggingThrottler errorLoggingThrottler;
  @Autowired
  private UnitOfWorkManager unitOfWorkManager;
  @Autowired
  private ITaskDataSerializer taskDataSerializer;
  @Autowired
  private IKafkaListenerMetricsTemplate kafkaListenerMetricsTemplate;

  private ExecutorService executorService;

  private boolean shuttingDown;

  private final List<String> topics = new ArrayList<>();

  @Override
  public void afterPropertiesSet() {
    topics.add("payout.succeeded");
  }

  public void poll() {
    Map<String, Object> kafkaConsumerProps = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
    kafkaConsumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    kafkaConsumerProps.put(ConsumerConfig.CLIENT_ID_CONFIG, kafkaConsumerProps.getOrDefault(ConsumerConfig.CLIENT_ID_CONFIG, "") + ".core");

    new ConsistentKafkaConsumer<String>().setTopics(topics)
        .setDelayTimeout(Duration.ofSeconds(5))
        .setShouldFinishPredicate(() -> shuttingDown)
        .setKafkaPropertiesSupplier(() -> kafkaConsumerProps)
        .setRecordConsumer((record) -> {
          try {
            ExceptionUtils.doUnchecked(() -> {
              PayoutInstruction poi = objectMapper.readValue(record.value(), PayoutInstruction.class);
              log.debug("Received payout.succeeded message from Kafka for payout #" + poi.getId());
              tasksService.addTask(new ITasksService.AddTaskRequest()
                  .setPriority(poi.getPriority())
                  .setData(taskDataSerializer.serialize(record.value()))
                  .setType(SucceededPayoutsTaskHandlerConfiguration.TASK_TYPE_PAYOUT_SUCCEEDED)
              );
            });
          } catch (Throwable t) {
            log.error(t.getMessage(), t);
          }
        })
        .setKafkaListenerMetricsTemplate(kafkaListenerMetricsTemplate)
        .setErrorLoggingThrottler(errorLoggingThrottler)
        .setUnitOfWorkManager(unitOfWorkManager)
        .consume();
  }

  @Override
  public void applicationStarted() {
    executorService = Executors.newCachedThreadPool();
    executorService.submit(() -> {
      try {
        poll();
      } catch (Throwable t) {
        log.error(t.getMessage(), t);
      }
    });
  }

  @Override
  public void prepareForShutdown() {
    shuttingDown = true;
    if (executorService != null) {
      executorService.shutdown();
    }
  }

  @Override
  public boolean canShutdown() {
    return executorService == null || executorService.isTerminated();
  }
}

package com.transferwise.tasks.testapp.config;

import com.transferwise.common.context.TwContextClockHolder;
import com.transferwise.tasks.buckets.BucketProperties;
import com.transferwise.tasks.buckets.IBucketsManager;
import com.transferwise.tasks.domain.ITask;
import com.transferwise.tasks.ext.kafkalistener.KafkaListenerExtTestConfiguration;
import com.transferwise.tasks.helpers.kafka.IKafkaListenerConsumerPropertiesProvider;
import com.transferwise.tasks.helpers.kafka.messagetotask.IKafkaMessageHandler;
import com.transferwise.tasks.impl.jobs.interfaces.IJob;
import com.transferwise.tasks.processing.ITaskProcessingInterceptor;
import com.transferwise.tasks.processing.ITaskRegistrationDecorator;
import com.transferwise.tasks.testapp.testbeans.JambiTaskInterceptor;
import com.transferwise.tasks.testapp.testbeans.JambiTaskRegistrationDecorator;
import com.transferwise.tasks.utils.LogUtils;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Slf4j
@Import({KafkaListenerExtTestConfiguration.class})
public class TestConfiguration implements InitializingBean {

  public static final String KAFKA_TEST_TOPIC_A = "myTopicA";
  public static final String TEST_JOB_UNIQUE_NAME = "MyFancyJobA";

  @Autowired
  private IBucketsManager bucketsManager;

  @Autowired
  private KafkaProperties kafkaProperties;

  @Override
  public void afterPropertiesSet() {
    bucketsManager.registerBucketProperties("manualStart", new BucketProperties()
        .setAutoStartProcessing(false));

    AdminClient adminClient = AdminClient.create(kafkaProperties.buildAdminProperties());

    short one = 1;

    // Creating all used topics beforehand, can improve test suite latency quite considerably.
    List<NewTopic> newTopics = Arrays.asList(
        new NewTopic("twTasks.test-mysql.executeTask.manualStart", one, one),
        new NewTopic("twTasks.test-mysql.executeTask.default", one, one),
        new NewTopic("ToKafkaTest", 100, one),
        new NewTopic("toKafkaBatchTestTopic", one, one),
        new NewTopic("twTasks.test-postgres.executeTask.default", one, one),
        new NewTopic("toKafkaBatchTestTopic5Partitions", 5, one),
        new NewTopic("KafkaListenerTopicA", 5, one),
        new NewTopic("KafkaListenerTopicB", one, one),
        new NewTopic("toKafkaBatchTestTopic2", one, one),
        new NewTopic("allMessagesWillBeReceivedOnceOnRebalancing", one, one),
        new NewTopic("topicWithCorruptedMessages", one, one),
        new NewTopic("myTopicA", one, one)
    );

    try {
      adminClient.createTopics(newTopics);
    } finally {
      adminClient.close();
    }
  }

  @Bean
  public ITaskProcessingInterceptor taskProcessingInterceptor() {
    return (task, processor) -> {
      if (log.isDebugEnabled()) {
        log.debug("Task {} got intercepted.", LogUtils.asParameter(task.getVersionId()));
      }
      processor.run();
    };
  }

  @Bean
  IKafkaMessageHandler<String> newHandlerForTopicA() {
    return new IKafkaMessageHandler<>() {
      @Override
      public List<Topic> getTopics() {
        return Collections.singletonList(new Topic().setAddress(KAFKA_TEST_TOPIC_A));
      }

      @Override
      public boolean handles(String topic) {
        return topic.equals(KAFKA_TEST_TOPIC_A);
      }

      @Override
      public void handle(ConsumerRecord<String, String> record) {
      }
    };
  }

  @Bean
  public IJob myFancyJobA() {

    return new IJob() {
      @Override
      public ZonedDateTime getNextRunTime() {
        return ZonedDateTime.now(TwContextClockHolder.getClock()).plusYears(20);
      }

      @Override
      public ProcessResult process(ITask task) {
        return null;
      }

      @Override
      public String getUniqueName() {
        return TEST_JOB_UNIQUE_NAME;
      }
    };
  }

  @Bean
  public IKafkaListenerConsumerPropertiesProvider twTasksKafkaListenerSpringKafkaConsumerPropertiesProvider() {
    return shard -> {
      var props = kafkaProperties.buildConsumerProperties();

      // Having a separate group id can greatly reduce Kafka re-balancing times for tests.
      props.put(CommonClientConfigs.GROUP_ID_CONFIG, props.get(CommonClientConfigs.GROUP_ID_CONFIG) + "-tw-tasks-kafka-listener-" + shard);
      props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, CooperativeStickyAssignor.class.getName());

      return props;
    };
  }

  @Bean
  ITaskRegistrationDecorator jambiRegistrationInterceptor() {
    return new JambiTaskRegistrationDecorator();
  }

  @Bean
  ITaskProcessingInterceptor jambiProcessingInterceptor(MeterRegistry meterRegistry) {
    return new JambiTaskInterceptor(meterRegistry);
  }
}

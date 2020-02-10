package com.transferwise.tasks.helpers.kafka.messagetotask

import com.transferwise.tasks.TasksProperties
import com.transferwise.tasks.test.BaseSpec
import spock.lang.Unroll

class CoreKafkaListenerSpec extends BaseSpec {
    @Unroll
    def "topic prefixes are correctly removed"() {
        given:
        CoreKafkaListener listener = new CoreKafkaListener()
        listener.tasksProperties = new TasksProperties()
        listener.tasksProperties.kafkaTopicsNamespace = namespace
        listener.kafkaDataCenterPrefixes = ["aws.", "fra."]
        when:
        String result = listener.removeTopicPrefixes(topic)
        then:
        result == nakedTopic
        where:
        namespace | topic             | nakedTopic
        ""        | "MyTopic"         | "MyTopic"
        ""        | "fra.MyTopic"     | "MyTopic"
        "dev"     | "dev.MyTopic"     | "MyTopic"
        "dev"     | "dev.fra.MyTopic" | "MyTopic"
    }
}

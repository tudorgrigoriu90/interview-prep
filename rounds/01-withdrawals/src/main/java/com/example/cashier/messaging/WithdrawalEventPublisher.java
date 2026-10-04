package com.example.cashier.messaging;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.example.cashier.config.CashierProperties;

@Component
public class WithdrawalEventPublisher {

    private final KafkaTemplate<String, WithdrawalRequestedEvent> kafka;
    private final String topic;

    public WithdrawalEventPublisher(KafkaTemplate<String, WithdrawalRequestedEvent> kafka,
                                    CashierProperties properties) {
        this.kafka = kafka;
        this.topic = properties.topics().withdrawals();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(WithdrawalRequestedEvent event) {
        kafka.send(topic, String.valueOf(event.withdrawalId()), event);
    }
}

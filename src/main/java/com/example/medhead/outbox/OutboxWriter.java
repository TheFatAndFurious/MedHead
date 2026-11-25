package com.example.medhead.outbox;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Writes outbox events in the caller's transaction and wakes the local dispatcher once it commits. */
@Component
public class OutboxWriter {

	private final OutboxEventRepository repository;
	private final OutboxWakeUp wakeUp;

	public OutboxWriter(OutboxEventRepository repository, OutboxWakeUp wakeUp) {
		this.repository = repository;
		this.wakeUp = wakeUp;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void append(OutboxEvent event) {
		repository.save(event);
		// Never wake the dispatcher before commit: it would not see the row yet, or the row may roll back.
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				wakeUp.signal();
			}
		});
	}
}

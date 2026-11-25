package com.example.medhead.outbox;

import org.springframework.context.SmartLifecycle;
import org.springframework.util.Assert;

/**
 * Publishes the outbox in a background thread. It is woken in memory after each local commit that
 * wrote an event, and polls at {@code pollInterval} as a safety net: events committed by another
 * instance that stopped before publishing them, by an instance without dispatcher, or rows locked by
 * another publisher that rolls back. No NOTIFY in the business transaction: PostgreSQL would hold a
 * global lock from its commit until the WAL flush, and reservation commits would run one at a time.
 */
public class OutboxDispatcher implements SmartLifecycle, AutoCloseable {

	private final OutboxPublisher publisher;
	private final OutboxWakeUp wakeUp;
	private final long pollInterval;
	private final long retryDelay;
	private volatile boolean running;
	private Thread worker;

	public OutboxDispatcher(OutboxPublisher publisher, OutboxWakeUp wakeUp, long pollInterval, long retryDelay) {
		Assert.isTrue(pollInterval > 0, "Outbox poll interval must be positive");
		Assert.isTrue(retryDelay > 0, "Outbox retry delay must be positive");
		this.publisher = publisher;
		this.wakeUp = wakeUp;
		this.pollInterval = pollInterval;
		this.retryDelay = retryDelay;
	}

	@Override
	public synchronized void start() {
		if (running) return;
		running = true;
		worker = Thread.ofPlatform().name("medhead-outbox-dispatcher").daemon().start(this::dispatch);
	}

	private void dispatch() {
		try {
			// The first pass catches up on events left by a previous run.
			while (running) {
				OutboxPublisher.Result result = publisher.publishPending();
				if (result == OutboxPublisher.Result.FULL) continue;
				if (result == OutboxPublisher.Result.ERROR) {
					// Ignore wake-ups meanwhile: under load they would retry a failing Redis hundreds of times per second.
					Thread.sleep(retryDelay);
					continue;
				}
				// A commit during the batch has already signalled: this returns at once and nothing is delayed.
				wakeUp.await(pollInterval);
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		} finally {
			running = false;
		}
	}

	@Override
	public void stop() {
		running = false;
		if (worker == null) return;
		worker.interrupt();
		try {
			worker.join(5000);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public void close() {
		stop();
	}
}

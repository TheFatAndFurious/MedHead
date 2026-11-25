package com.example.medhead.outbox;

import org.springframework.stereotype.Component;

/**
 * In-memory signal between the transactions that write outbox events and the dispatcher of the
 * same process. A signal sent while the dispatcher is busy is kept, so the next wait returns at once.
 */
@Component
public class OutboxWakeUp {

	private boolean signalled;

	public synchronized void signal() {
		signalled = true;
		notifyAll();
	}

	/**
	 * Waits for a signal, at most {@code timeoutMillis}, and consumes it.
	 *
	 * @return true if woken by a signal, false on timeout
	 */
	public synchronized boolean await(long timeoutMillis) throws InterruptedException {
		long deadline = System.nanoTime() + timeoutMillis * 1_000_000;
		while (!signalled) {
			long remaining = (deadline - System.nanoTime()) / 1_000_000;
			if (remaining <= 0) {
				return false;
			}
			wait(remaining);
		}
		signalled = false;
		return true;
	}
}

package com.example.medhead.outbox;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.Assert;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public class OutboxNotificationListener implements SmartLifecycle, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(OutboxNotificationListener.class);
	private final DataSource notifications;
	private final OutboxPublisher publisher;
	private final OutboxPublicationService service;
	private final long retryDelay;
	private volatile boolean running;
	private volatile Connection connection;
	private Thread worker;

	public OutboxNotificationListener(DataSource notifications, OutboxPublisher publisher,
			OutboxPublicationService service, long retryDelay) {
		Assert.isTrue(retryDelay > 0, "Outbox retry delay must be positive");
		this.notifications = notifications;
		this.publisher = publisher;
		this.service = service;
		this.retryDelay = retryDelay;
	}

	@Override
	public synchronized void start() {
		if (running) return;
		running = true;
		worker = Thread.ofPlatform().name("medhead-outbox-listener").daemon().start(this::listen);
	}

	private void listen() {
		while (running) {
			try (Connection listening = notifications.getConnection()) {
				connection = listening;
				if (!running) break;
				listening.setAutoCommit(true);
				try (var statement = listening.createStatement()) {
					statement.execute("LISTEN medhead_outbox");
				}
				PGConnection postgres = listening.unwrap(PGConnection.class);
				// Subscribe before the catch-up scan so concurrent commits cannot be missed.
				while (running) {
					OutboxPublisher.Result result = publisher.publishPending();
					if (result == OutboxPublisher.Result.FULL) continue;
					if (result == OutboxPublisher.Result.ERROR || service.hasPending()) {
						// Pending rows may be locked by a different publisher that later rolls back.
						awaitRetry();
						continue;
					}
					PGNotification[] received;
					do {
						// Block on the socket: no SQL query or periodic wake-up while idle.
						received = postgres.getNotifications(0);
					} while (running && (received == null || received.length == 0));
				}
			} catch (SQLException | RuntimeException exception) {
				if (running) {
					log.warn("Outbox listener disconnected; reconnecting: {}", exception.getMessage());
					awaitRetry();
				}
			} finally {
				connection = null;
			}
		}
	}

	private void awaitRetry() {
		try {
			Thread.sleep(retryDelay);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			running = false;
		}
	}

	@Override
	public void stop() {
		running = false;
		if (worker == null) return;
		worker.interrupt();
		Connection listening = connection;
		if (listening != null) {
			try {
				// Abort releases a JDBC read blocked in getNotifications(0).
				listening.abort(Runnable::run);
			} catch (SQLException exception) {
				log.warn("Could not close outbox listener connection: {}", exception.getMessage());
			}
		}
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

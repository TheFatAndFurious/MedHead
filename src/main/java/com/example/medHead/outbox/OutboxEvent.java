package com.example.medhead.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_outbox")
public class OutboxEvent {

	@Id
	private UUID id;

	@Column(name = "event_type", nullable = false, length = 100)
	private String eventType;

	@Column(name = "aggregate_id", nullable = false)
	private UUID aggregateId;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private String payload;

	@Column(name = "published_at")
	private Instant publishedAt;

	protected OutboxEvent() {
		// Required by JPA.
	}

	public OutboxEvent(UUID id, String eventType, UUID aggregateId, Instant occurredAt, String payload) {
		this.id = id;
		this.eventType = eventType;
		this.aggregateId = aggregateId;
		this.occurredAt = occurredAt;
		this.payload = payload;
	}

	public UUID getId() {
		return id;
	}

	public String getEventType() {
		return eventType;
	}

	public UUID getAggregateId() {
		return aggregateId;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public String getPayload() {
		return payload;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}

	public void markPublished(Instant publishedAt) {
		this.publishedAt = publishedAt;
	}
}

package com.example.medhead.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

	@Query(value = """
			select * from event_outbox
			where published_at is null
			order by occurred_at, id
			limit :limit
			for update skip locked
			""", nativeQuery = true)
	List<OutboxEvent> lockNextPending(@Param("limit") int limit);

	// One statement for the whole batch instead of one UPDATE per event.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update OutboxEvent e set e.publishedAt = :publishedAt where e.id in :ids")
	int markPublished(@Param("ids") Collection<UUID> ids, @Param("publishedAt") Instant publishedAt);

	@Query(value = "select exists (select 1 from event_outbox where published_at is null)", nativeQuery = true)
	boolean hasPending();
}

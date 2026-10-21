package com.example.medhead.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

	@Query(value = """
			select * from event_outbox
			where published_at is null
			order by occurred_at, id
			limit 1
			for update skip locked
			""", nativeQuery = true)
	Optional<OutboxEvent> findNextPending();
}

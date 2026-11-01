package com.gerson.cowork.entity;

import com.gerson.cowork.enums.SpaceType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "spaces")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Space {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, length = 100)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private SpaceType type;

	@Column(nullable = false)
	private int capacity;

	@Column(nullable = false, length = 200)
	private String location;

	@Column(name = "hourly_rate", nullable = false, precision = 10, scale = 2)
	private BigDecimal hourlyRate;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
}

package com.tokengateway.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "gateway_users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayUser {

    @Id
    @Column(name = "user_id", length = 128)
    private String userId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "tier", nullable = false, length = 32)
    @Builder.Default
    private String tier = "standard";

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;
}

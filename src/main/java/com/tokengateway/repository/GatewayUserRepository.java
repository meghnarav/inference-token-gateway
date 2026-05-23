package com.tokengateway.repository;

import com.tokengateway.model.GatewayUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface GatewayUserRepository extends JpaRepository<GatewayUser, String> {

    /**
     * Auto-register unknown users on first request.
     * Uses INSERT ... ON CONFLICT DO NOTHING for idempotent upsert.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO gateway_users (user_id, tier, is_active, created_at)
        VALUES (:userId, 'standard', TRUE, NOW())
        ON CONFLICT (user_id) DO NOTHING
        """, nativeQuery = true)
    void registerIfAbsent(@Param("userId") String userId);
}

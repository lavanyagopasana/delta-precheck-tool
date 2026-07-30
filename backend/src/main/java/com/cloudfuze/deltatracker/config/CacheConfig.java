package com.cloudfuze.deltatracker.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine-backed cache manager for master/lookup data only.
 *
 * <p>The only thing cached today is the per-role roster email list ({@link
 * com.cloudfuze.deltatracker.service.AppUserService#emailsForRole}) -- read constantly to populate
 * dropdowns and notification recipient lists, written rarely (an admin editing Manage Access, or a
 * first-sign-in auto-provision). Every write path evicts the cache, so nothing a user just changed
 * is served stale beyond that. Nothing user-scoped, and nothing reflecting sign-off/escalation
 * state, is ever cached here.
 *
 * <p>A 10-minute TTL bounds staleness even in the (impossible-by-design) case an eviction is ever
 * missed.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Cache of role -> list of member emails. Evicted on every app_users write. */
    public static final String ROSTER_EMAILS_CACHE = "rosterEmails";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(ROSTER_EMAILS_CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(100));
        return manager;
    }
}

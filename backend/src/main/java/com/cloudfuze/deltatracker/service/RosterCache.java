package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.config.CacheConfig;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * Single owner of "throw away the cached roster".
 *
 * <p>Extracted because two services now invalidate the same cache: AppUserService (a user's role or
 * team changed) and TeamService (a team was renamed or deleted). Both feed the same dropdowns, so
 * both must evict, and a second hand-rolled copy of the getCache/clear dance is exactly the kind of
 * duplicated-constant drift that already bit APPROVAL_SEQUENCE in this codebase.
 *
 * <p>Deliberately clears the WHOLE cache rather than one key. The per-role lists and the
 * manager -> engineers map are derived from overlapping rows, so a team reassignment invalidates
 * entries that a key-scoped eviction would leave stale.
 */
@Component
public class RosterCache {

    private final CacheManager cacheManager;

    public RosterCache(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public void evict() {
        Cache cache = cacheManager.getCache(CacheConfig.ROSTER_EMAILS_CACHE);
        if (cache != null) {
            cache.clear();
        }
    }
}

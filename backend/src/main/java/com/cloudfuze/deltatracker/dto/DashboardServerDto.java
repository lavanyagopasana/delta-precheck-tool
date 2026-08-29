package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.ProductType;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * One server behind the Dashboard's "Servers" and "Delta Ready" tiles, carrying enough to identify it
 * without a second request: which project it belongs to and what it migrates.
 *
 * <p>Deliberately served from the dashboard summary rather than reusing {@code GET /api/servers}.
 * That endpoint ({@code ServerService.listReadiness}) takes no caller identity and returns every
 * server in the database to anybody allowlisted, so building a scoped tile on top of it would have
 * shown a Migration Manager the count for their own projects and then listed everyone else's servers
 * when they clicked it.
 *
 * <p>One DTO backs both tiles: the Servers popup lists all of them, the Delta Ready popup lists those
 * with {@code deltaReady} set. Splitting them into two shapes would mean two ways to describe the same
 * row, and the two tiles would drift apart the first time either changed.
 */
@Getter
@Setter
public class DashboardServerDto {

    private Long serverId;
    private String serverName;
    private ProductType productType;
    private Long projectId;
    private String projectName;

    /**
     * The server's own status is DELTA_READY. Matches how the tile has always been counted
     * ({@code ProjectService.buildSummary} counts servers with {@code PairStatus.DELTA_READY}), so the
     * popup can never disagree with the number above it.
     */
    private boolean deltaReady;

    /**
     * Names of this server's combinations that are individually DELTA_READY. A server holds several
     * combinations, each running its own pre-check and sign-off chain, so "which combination" is a
     * real question the server name alone cannot answer.
     */
    private List<String> deltaReadyCombinations = new ArrayList<>();
}

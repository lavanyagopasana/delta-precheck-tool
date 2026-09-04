package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.PreCheckItemDto;
import com.cloudfuze.deltatracker.dto.PreCheckItemEditDto;
import com.cloudfuze.deltatracker.dto.PreCheckItemUpdateRequest;
import com.cloudfuze.deltatracker.entity.ItemStatus;
import com.cloudfuze.deltatracker.service.PreCheckItemService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/combinations/{combinationId}/precheck-items")
public class PreCheckItemController {

    private final PreCheckItemService preCheckItemService;

    public PreCheckItemController(PreCheckItemService preCheckItemService) {
        this.preCheckItemService = preCheckItemService;
    }

    @GetMapping
    public List<PreCheckItemDto> list(@PathVariable Long combinationId) {
        return preCheckItemService.listByCombination(combinationId);
    }

    /**
     * Every recorded edit to one item, newest first.
     *
     * <p>A GET, so it lands on the allowlist-only matcher that already covers reading a pre-check --
     * the trail is meant to be visible to everyone who can see the item, including the Dev and QA
     * Leads who approve it and cannot edit it. Loaded per item rather than folded into the checklist
     * payload: only an expanded row needs it, and an item edited fifty times should not weigh down
     * the list for the other twenty.
     */
    /** The whole form's trail. Distinct path from the per-item one, which is kept for drill-down. */
    @GetMapping("/history")
    public List<PreCheckItemEditDto> history(@PathVariable Long combinationId) {
        return preCheckItemService.editHistoryForCombination(combinationId);
    }

    @GetMapping("/{itemId}/history")
    public List<PreCheckItemEditDto> itemHistory(@PathVariable Long combinationId, @PathVariable Long itemId) {
        return preCheckItemService.editHistory(combinationId, itemId);
    }

    @PostMapping("/{itemId}")
    public PreCheckItemDto update(@PathVariable Long combinationId,
                                   @PathVariable Long itemId,
                                   @Valid @RequestBody PreCheckItemUpdateRequest request) {
        return preCheckItemService.update(combinationId, itemId, request);
    }

    @PostMapping("/check-all")
    public void checkAll(@PathVariable Long combinationId,
                          @RequestParam ItemStatus status,
                          @RequestParam(required = false) String updatedBy) {
        preCheckItemService.setAllStatus(combinationId, status, updatedBy);
    }
}

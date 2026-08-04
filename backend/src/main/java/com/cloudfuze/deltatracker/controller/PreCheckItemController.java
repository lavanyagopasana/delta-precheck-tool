package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.PreCheckItemDto;
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

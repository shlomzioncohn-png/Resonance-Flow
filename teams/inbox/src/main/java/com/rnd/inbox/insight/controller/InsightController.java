package com.rnd.inbox.insight.controller;

import com.rnd.inbox.insight.dto.InsightCreateRequestDto;
import com.rnd.inbox.insight.dto.InsightResponseDto;
import com.rnd.inbox.insight.service.InsightService;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.security.CurrentActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Insights", description = "Personal AI insights of the calling user - created and read, never updated")
@RestController
@RequestMapping("/api/v1/insights")
public class InsightController {

    private final InsightService insightService;

    public InsightController(InsightService insightService) {
        this.insightService = insightService;
    }

    @Operation(summary = "Save a new insight for the calling user",
            description = "The insight always belongs to the user in X-Actor-Id and to that user's tenant; "
                    + "the body carries no user or tenant id.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Insight created",
                    content = @Content(schema = @Schema(implementation = InsightResponseDto.class))),
            @ApiResponse(responseCode = "400",
                    description = "Invalid request body, or periodTo before periodFrom"),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity")
    })
    @PostMapping
    public ResponseEntity<InsightResponseDto> createInsight(@Valid @RequestBody InsightCreateRequestDto request,
                                                            @CurrentActor UUID actorId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(insightService.createInsight(request, actorId));
    }

    @Operation(summary = "List the calling user's own insights, newest first, paginated",
            description = "Only the caller's own insights are returned - nobody, managers included, can list "
                    + "another user's insights. The order is fixed (newest first); a sort parameter is ignored.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of insights",
                    content = @Content(schema = @Schema(implementation = PageResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity")
    })
    @GetMapping
    public PageResponseDto<InsightResponseDto> listMyInsights(Pageable pageable, @CurrentActor UUID actorId) {
        return insightService.listMyInsights(pageable, actorId);
    }
}

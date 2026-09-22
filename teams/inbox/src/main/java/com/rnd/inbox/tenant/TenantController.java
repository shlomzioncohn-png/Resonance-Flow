package com.rnd.inbox.tenant;

import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.security.CurrentActor;
import com.rnd.inbox.shared.security.RequireRole;
import com.rnd.inbox.user.UserRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

@Tag(name = "Tenants", description = "Create, update, and look up tenants")
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @Operation(summary = "Create a new tenant")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Tenant created",
                    content = @Content(schema = @Schema(implementation = TenantResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403", description = "Actor's role cannot create tenants (SYSTEM_ADMIN only)"),
            @ApiResponse(responseCode = "409", description = "Slug already exists")
    })
    @PostMapping
    @RequireRole(UserRole.SYSTEM_ADMIN)
    public ResponseEntity<TenantResponseDto> createTenant(@Valid @RequestBody TenantCreateRequestDto request,
                                                            @CurrentActor UUID actorId,
                                                            UriComponentsBuilder uriBuilder) {
        TenantResponseDto created = tenantService.createTenant(request, actorId);
        return ResponseEntity
                .created(uriBuilder.path("/api/v1/tenants/{id}").build(created.getId()))
                .body(created);
    }

    @Operation(summary = "Get a tenant by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenant found",
                    content = @Content(schema = @Schema(implementation = TenantResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403",
                    description = "Tenant is outside the actor's scope "
                            + "(SYSTEM_ADMIN: any tenant; everyone else: their own tenant only)"),
            @ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    @GetMapping("/{id}")
    public TenantResponseDto getTenantById(@PathVariable UUID id, @CurrentActor UUID actorId) {
        return tenantService.getTenantById(id, actorId);
    }

    @Operation(summary = "List all tenants, paginated (SYSTEM_ADMIN only)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of tenants",
                    content = @Content(schema = @Schema(implementation = PageResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403", description = "Actor's role cannot list tenants (SYSTEM_ADMIN only)")
    })
    @GetMapping
    @RequireRole(UserRole.SYSTEM_ADMIN)
    public PageResponseDto<TenantResponseDto> listTenants(Pageable pageable) {
        return tenantService.listTenants(pageable);
    }

    @Operation(summary = "Update a tenant's details")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenant updated",
                    content = @Content(schema = @Schema(implementation = TenantResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403",
                    description = "Actor's role cannot update tenants (SYSTEM_ADMIN/TENANT_ADMIN only), "
                            + "or the tenant is outside the actor's scope (TENANT_ADMIN: their own tenant only)"),
            @ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    @PatchMapping("/{id}")
    @RequireRole({UserRole.SYSTEM_ADMIN, UserRole.TENANT_ADMIN})
    public TenantResponseDto updateTenant(@PathVariable UUID id, @Valid @RequestBody TenantUpdateRequestDto request,
                                           @CurrentActor UUID actorId) {
        return tenantService.updateTenant(id, request, actorId);
    }

    @Operation(summary = "Change a tenant's status (SYSTEM_ADMIN only)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status updated",
                    content = @Content(schema = @Schema(implementation = TenantResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403", description = "Actor's role cannot change tenant status (SYSTEM_ADMIN only)"),
            @ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    @PatchMapping("/{id}/status")
    @RequireRole(UserRole.SYSTEM_ADMIN)
    public TenantResponseDto updateStatus(@PathVariable UUID id,
                                           @Valid @RequestBody TenantStatusUpdateRequestDto request,
                                           @CurrentActor UUID actorId) {
        return tenantService.updateStatus(id, request, actorId);
    }
}

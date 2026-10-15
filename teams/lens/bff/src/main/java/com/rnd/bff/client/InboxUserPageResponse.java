package com.rnd.bff.client;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Wire shape of Inbox's {@code GET /api/v1/users} response envelope
 * (Inbox's generic {@code PageResponseDto<T>} —
 * teams/inbox/src/main/java/com/rnd/inbox/shared/dto/PageResponseDto.java).
 * Field names (content/totalElements/totalPages) mirror Inbox's JSON, not
 * the Lens-wide items/total/page/size convention used by UserListResponseDTO
 * and TaskListResponseDTO — that mapping happens in UserService.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InboxUserPageResponse {

    private List<InboxUserRecord> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
}

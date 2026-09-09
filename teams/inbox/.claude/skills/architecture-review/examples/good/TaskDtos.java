// Example only — illustrates a compliant slice for the architecture-review skill's test
// scenarios. Not wired into the Maven build. (Two DTOs kept in one file here purely to keep
// the example set short — in real code each class gets its own file per naming conventions.)
package com.rnd.inbox.dto;

import lombok.Value;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

class TaskCreateRequestDto {
    @NotBlank
    @Size(max = 200)
    String title;

    @Size(max = 2000)
    String description;
}

@Value
class TaskResponseDto {
    Long id;
    String title;
    String description;
    String status;
}

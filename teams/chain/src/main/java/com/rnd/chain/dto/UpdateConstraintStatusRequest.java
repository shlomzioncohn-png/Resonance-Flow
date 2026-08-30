package com.rnd.chain.dto;

import javax.validation.constraints.NotNull;

public class UpdateConstraintStatusRequest {

    @NotNull(message = "constraint status is required")
    private Constraint.ConstraintStatus status;


    public UpdateConstraintStatusRequest() {
    }


    public Constraint.ConstraintStatus getStatus() {
        return status;
    }

    public void setStatus(Constraint.ConstraintStatus status) {
        this.status = status;
    }
}
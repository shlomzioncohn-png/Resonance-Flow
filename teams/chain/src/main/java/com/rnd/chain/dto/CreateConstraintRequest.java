package com.rnd.chain.dto;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public class CreateConstraintRequest {

    @NotNull(message = "constraint type is required")
    private Constraint.ConstraintType type;

    private Map<String, Object> reason;

    private String sourceRef;


    public CreateConstraintRequest() {
    }


    public Constraint.ConstraintType getType() {
        return type;
    }

    public void setType(Constraint.ConstraintType type) {
        this.type = type;
    }

    public Map<String, Object> getReason() {
        return reason;
    }

    public void setReason(Map<String, Object> reason) {
        this.reason = reason;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }
}
package com.rnd.inbox.taskselection;

import org.springframework.stereotype.Component;

@Component
public class TaskSelectionProperties {

    private double deadlineHalfLifeHours = 24;
    private double overdueExtraCap = 0.15;
    private double agingHalfLifeHours = 72;
    private double agingCap = 0.15;
    private double scale = 1;
    private int defaultEffortEstimateMinutes = 30;
    private double defaultEffortEstimateConfidence = 0.1;
    private double defaultPriorityScore = 5;

    public double getDeadlineHalfLifeHours() {
        return deadlineHalfLifeHours;
    }

    public void setDeadlineHalfLifeHours(double deadlineHalfLifeHours) {
        this.deadlineHalfLifeHours = deadlineHalfLifeHours;
    }

    public double getOverdueExtraCap() {
        return overdueExtraCap;
    }

    public void setOverdueExtraCap(double overdueExtraCap) {
        this.overdueExtraCap = overdueExtraCap;
    }

    public double getAgingHalfLifeHours() {
        return agingHalfLifeHours;
    }

    public void setAgingHalfLifeHours(double agingHalfLifeHours) {
        this.agingHalfLifeHours = agingHalfLifeHours;
    }

    public double getAgingCap() {
        return agingCap;
    }

    public void setAgingCap(double agingCap) {
        this.agingCap = agingCap;
    }

    public double getScale() {
        return scale;
    }

    public void setScale(double scale) {
        this.scale = scale;
    }

    public int getDefaultEffortEstimateMinutes() {
        return defaultEffortEstimateMinutes;
    }

    public void setDefaultEffortEstimateMinutes(int defaultEffortEstimateMinutes) {
        this.defaultEffortEstimateMinutes = defaultEffortEstimateMinutes;
    }

    public double getDefaultEffortEstimateConfidence() {
        return defaultEffortEstimateConfidence;
    }

    public void setDefaultEffortEstimateConfidence(double defaultEffortEstimateConfidence) {
        this.defaultEffortEstimateConfidence = defaultEffortEstimateConfidence;
    }

    public double getDefaultPriorityScore() {
        return defaultPriorityScore;
    }

    public void setDefaultPriorityScore(double defaultPriorityScore) {
        this.defaultPriorityScore = defaultPriorityScore;
    }
}

package com.rnd.bridge.intake.controller;

/** Response body for Slack's Events API url_verification handshake. */
public record SlackChallengeResponse(String challenge) {
}

package co.kuznetsov.mailkick.model;

/**
 * Identifies a monitored system component for health reporting.
 */
public enum HealthComponent {
    S3,
    FASTMAIL,
    DYNAMODB,
    ANTHROPIC,
    TRIAGE,
    MEDIA_FEED
}

package com.sloopworks.dayfold.client

/** Host-owned availability. No reducer action can promote HIDDEN to PREVIEW. */
enum class RoutineCapability { HIDDEN, PREVIEW }

enum class RoutineAuthority { OWNER, ADULT_READ_ONLY }

enum class RoutineDestination {
  ENTRY,
  PROVIDER,
  SOURCES,
  HUB_ACCESS,
  SCHEDULE,
  PRIVACY,
  HANDOFF,
  STATUS,
  DRAFT,
  ACTIVE,
}

enum class RoutineLifecycle { OFF, PREPARING, AWAITING_PROVIDER, VERIFYING, ACTIVE, PAUSED, REVOKING, REVOKED }
enum class RoutineExecutionMode { SHADOW }
enum class RoutinePublicationMode { REVIEW }
enum class RoutineProvider { UNSELECTED, CLAUDE, CHATGPT }
enum class RoutineExternalSource { GMAIL, GOOGLE_CALENDAR, GOOGLE_DRIVE }
enum class RoutineSourceStatus { REQUESTED, PROBING, OBSERVED, SYNCING, REAUTH_REQUIRED, ADMIN_BLOCKED, UNAVAILABLE, REMOVED }
enum class RoutineProviderHealth { NEVER_SEEN, LAST_SEEN, REPORTED_FAILED, STALE }
enum class RoutineRunResult { SUCCESS, NO_CHANGES, PARTIAL, REJECTED, FAILED }
enum class RoutineDraftFixture { FIRST_DRAFT, STALE_OR_CONFLICTED }
enum class RoutineDraftDecision { ACCEPTED_PREVIEW, REJECTED_PREVIEW }
enum class RoutineOperation { PREPARATION, PROVIDER_RETURN, SOURCE_PROBE, RUN, DRAFT_REFRESH, REVOKE }
enum class RoutineRevokeOutcome { REVOKED, FAILED, REVOKED_PROVIDER_TASK_REMAINS }

/** Synthetic choices deliberately avoid action-loggable production IDs or family content. */
enum class RoutineSyntheticHub { FAMILY_BRIEFING, RESTRICTED_EXAMPLE }
enum class RoutineHubFixture { LOADING, AVAILABLE, EMPTY, SELECTED_REMOVED }
enum class RoutineSchedulePreset { WEEKDAY_MORNING, DAILY_EVENING, WEEKLY_SUNDAY }

enum class RoutineRecoveryPhase(val wire: String) {
  ENROLLMENT("enrollment"),
  PROVIDER_HANDOFF("provider_handoff"),
  AUTHORIZATION("authorization"),
  SOURCE_PROBE("source_probe"),
  PROVIDER_OBSERVATION("provider_observation"),
  CONTEXT_READ("context_read"),
  ANALYSIS("analysis"),
  VALIDATION("validation"),
  STAGE("stage"),
  APPLY("apply"),
  FINISH("finish"),
  REVOKE("revoke"),
  UNKNOWN("unknown");

  companion object {
    fun fromWire(raw: String): RoutineRecoveryPhase = entries.firstOrNull { it != UNKNOWN && it.wire == raw } ?: UNKNOWN
  }
}

enum class RoutineRecoveryReason(val wire: String) {
  ENROLLMENT_EXPIRED("enrollment_expired"),
  ROUTINE_NOT_CONFIRMED("routine_not_confirmed"),
  APP_RETURN_LOST("app_return_lost"),
  PREPARATION_FAILED("preparation_failed"),
  PROVIDER_UNAVAILABLE("provider_unavailable"),
  AUTHORIZATION_DENIED("authorization_denied"),
  AUTHORIZATION_CONTEXT_MISMATCH("authorization_context_mismatch"),
  LATE_CALLBACK("late_callback"),
  PROVIDER_QUOTA("provider_quota"),
  SOURCE_REAUTH("source_reauth"),
  SOURCE_SYNCING("source_syncing"),
  SOURCE_NOT_OBSERVED("source_not_observed"),
  ADMIN_BLOCKED("admin_blocked"),
  UNEXPECTED_SOURCE_SET("unexpected_source_set"),
  PARTIAL_SOURCE_SET("partial_source_set"),
  RUN_REPORTED_FAILED("run_reported_failed"),
  GATEWAY_UNREACHABLE("gateway_unreachable"),
  INVALID_OUTPUT("invalid_output"),
  POLICY_REJECTED("policy_rejected"),
  WRITE_CAP("write_cap"),
  CONFLICT("conflict"),
  RATE_LIMITED("rate_limited"),
  CONFIRM_PENDING("confirm_pending"),
  REVOKE_FAILED("revoke_failed"),
  PROVIDER_TASK_REMAINS("provider_task_remains"),
  RETRIES_EXHAUSTED("retries_exhausted"),
  REPEATED_CONFLICTS("repeated_conflicts"),
  VOLUME_ANOMALY("volume_anomaly"),
  TIMEOUT("timeout"),
  OFFLINE("offline"),
  INTERNAL("internal"),
  UNKNOWN("unknown");

  companion object {
    fun fromWire(raw: String): RoutineRecoveryReason = entries.firstOrNull { it != UNKNOWN && it.wire == raw } ?: UNKNOWN
  }
}

enum class RoutineRetryability(val wire: String) {
  AUTOMATIC("automatic"),
  USER_ACTION("user_action"),
  FINAL("final"),
  UNKNOWN("unknown");

  companion object {
    fun fromWire(raw: String): RoutineRetryability = entries.firstOrNull { it != UNKNOWN && it.wire == raw } ?: UNKNOWN
  }
}

enum class RoutineRecommendedAction(val wire: String) {
  RETRY("retry"),
  RESUME_PROVIDER("resume_provider"),
  MANAGE_SOURCE("manage_source"),
  RESTART_ENROLLMENT("restart_enrollment"),
  CONTINUE_REVIEW_ONLY("continue_review_only"),
  REFRESH_DRAFT("refresh_draft"),
  REVIEW_POLICY("review_policy"),
  CONTACT_SUPPORT("contact_support"),
  UNKNOWN("unknown");

  companion object {
    fun fromWire(raw: String): RoutineRecommendedAction = entries.firstOrNull { it != UNKNOWN && it.wire == raw } ?: UNKNOWN
  }
}

const val ROUTINE_RECOVERY_SCHEMA_VERSION = 1

data class RoutineRecoveryEnvelope(
  val schemaVersion: Int = ROUTINE_RECOVERY_SCHEMA_VERSION,
  val phase: RoutineRecoveryPhase,
  val reason: RoutineRecoveryReason,
  val retryability: RoutineRetryability,
  val recommendedAction: RoutineRecommendedAction,
  val supportCodeAvailable: Boolean = false,
)

data class RoutineSafeCounts(
  val candidates: Int = 0,
  val sourcesObserved: Int = 0,
) {
  init {
    require(candidates >= 0)
    require(sourcesObserved >= 0)
  }
}

data class RoutineRunSummary(
  val result: RoutineRunResult,
  val counts: RoutineSafeCounts = RoutineSafeCounts(),
  val sourceStatuses: Map<RoutineExternalSource, RoutineSourceStatus> = emptyMap(),
)

/**
 * Pure, in-session preview state. It contains closed enums and safe counts only: no provider
 * errors, source content, URLs, codes, support identifiers, or production resource IDs.
 */
data class RoutineState(
  val capability: RoutineCapability = RoutineCapability.HIDDEN,
  val destination: RoutineDestination = RoutineDestination.ENTRY,
  val lifecycle: RoutineLifecycle = RoutineLifecycle.OFF,
  val executionMode: RoutineExecutionMode = RoutineExecutionMode.SHADOW,
  val publicationMode: RoutinePublicationMode = RoutinePublicationMode.REVIEW,
  val provider: RoutineProvider = RoutineProvider.UNSELECTED,
  val externalSources: Set<RoutineExternalSource> = emptySet(),
  val sourceStatuses: Map<RoutineExternalSource, RoutineSourceStatus> = emptyMap(),
  val hubFixture: RoutineHubFixture = RoutineHubFixture.AVAILABLE,
  val selectedHub: RoutineSyntheticHub? = null,
  val schedule: RoutineSchedulePreset? = null,
  val privacyAcknowledged: Boolean = false,
  val providerHealth: RoutineProviderHealth? = null,
  val lastRun: RoutineRunSummary? = null,
  val lastGoodRun: RoutineRunSummary? = null,
  val draft: RoutineDraftFixture? = null,
  val draftDecision: RoutineDraftDecision? = null,
  val recovery: RoutineRecoveryEnvelope? = null,
  val offline: Boolean = false,
  val detailsOpen: Boolean = false,
  val revokeSheetOpen: Boolean = false,
  val operationGeneration: Long = 0L,
  val pendingOperation: RoutineOperation? = null,
  val syntheticGrantMarker: Boolean = false,
  val syntheticFinishMarker: Boolean = false,
) {
  companion object {
    fun preview(): RoutineState = RoutineState(capability = RoutineCapability.PREVIEW)
  }

  /** Family/session reset preserves only the immutable host capability. */
  fun resetFamilyScoped(): RoutineState = RoutineState(
    capability = capability,
    // Keep the monotonic fence across family replacement. Resetting it to zero would allow an
    // old family-A terminal with generation 1 to match a new family-B operation generation 1.
    operationGeneration = operationGeneration,
  )
}

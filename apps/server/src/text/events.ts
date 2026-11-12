export const LOG_EVENTS = Object.freeze({
  serverStarted: 'server.started',
  serverStopping: 'server.stopping',
  dbMigrationFailed: 'db.migration_failed',
  dbPoolError: 'db.pool_error',
  httpUnhandledError: 'http.unhandled_error',
  healthNotReady: 'health.not_ready',
  jobStarted: 'job.started',
  jobFinished: 'job.finished',
  jobFailed: 'job.failed',
  securityRateLimitsDisabled: 'security.rate_limits_disabled',
});

export const API_PREFIX = '/api/v1';

export const ROUTE_PATTERNS = Object.freeze({
  config: `${API_PREFIX}/config`,
  healthLive: '/health/live',
  healthReady: '/health/ready',
});

export const ROUTES = Object.freeze({
  config: () => `${API_PREFIX}/config`,
  healthLive: () => '/health/live',
  healthReady: () => '/health/ready',
});

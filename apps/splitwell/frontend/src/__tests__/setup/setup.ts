// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

// happy-dom@20 doesn't lowercase header names during iteration as the Fetch
// spec requires; the openapi clients read `headers["content-type"]`, so without
// this patch they fail to parse JSON responses from MSW.
const _origForEach = Headers.prototype.forEach;
Headers.prototype.forEach = function (cb, thisArg) {
  return _origForEach.call(this, function (value: string, name: string, parent: Headers) {
    cb.call(thisArg, value, name.toLowerCase(), parent);
  });
};

import { cleanup } from '@testing-library/react';
import crypto from 'crypto';
import { SetupServer } from 'msw/node';
import { vi, afterAll, afterEach, beforeAll } from 'vitest';

import { buildServer } from '../mocks/server';
import { config } from './config';
import { DetachedWindowAPI } from 'happy-dom';

// Provide an implementation for webcrypto when generating insecure jwts in the app
vi.stubGlobal('crypto', crypto.webcrypto);

type Config = typeof config;
export type Services = Config['services'];

// Provide a global variable for the app config in the test environment
window.splice_config = config;
declare global {
  interface Window {
    splice_config: Config; // (make typescript happy)
    happyDOM: DetachedWindowAPI;
  }
}

export const server: SetupServer = buildServer(window.splice_config.services);

// Start server before all tests
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));

//  Close server after all tests
afterAll(() => server.close());

// Reset handlers & react renderers after each test `important for test isolation`
afterEach(() => {
  server.resetHandlers();
  cleanup();
});

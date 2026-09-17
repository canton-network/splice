// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@jest/globals';

import { rateLimitedRequestsExpression } from './rateLimit';

test('the access log filter matches the rejections of the configured protocol', () => {
  // a rate limited HTTP request is answered with 429
  expect(rateLimitedRequestsExpression('http')).toEqual('response.code == 429');
  // whereas a rate limited gRPC call (e.g. on the sequencer's public API) is answered with
  // HTTP 200 and the RESOURCE_EXHAUSTED (8) gRPC status, so filtering on the HTTP status code
  // would never log it
  expect(rateLimitedRequestsExpression('grpc')).toEqual('response.grpc_status == 8');
});

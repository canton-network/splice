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
import { beforeEach, afterEach } from 'vitest';

beforeEach(() => {
  // @ts-expect-error Fixes "cannot serialize bigint" from react-query
  BigInt.prototype['toJSON'] = function () {
    return this.toString();
  };
});

afterEach(() => {
  cleanup();
});

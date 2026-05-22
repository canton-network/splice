// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  BaseApiMiddleware,
  onAuthExpired,
} from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { afterEach, describe, expect, test, vi } from 'vitest';

interface FakeRequestContext {
  headers: Record<string, string>;
  setHeaderParam(key: string, value: string): void;
}

type FakeResponseContext = { httpStatusCode: number };

const makeRequest = (): FakeRequestContext => {
  const headers: Record<string, string> = {};
  return {
    headers,
    setHeaderParam(key, value) {
      headers[key] = value;
    },
  };
};

const makeMiddleware = (token: string = 'tok') =>
  new BaseApiMiddleware<FakeRequestContext, FakeResponseContext>(token);

const cleanups: Array<() => void> = [];
const subscribe = (handler: () => void) => {
  cleanups.push(onAuthExpired(handler));
};

afterEach(() => {
  for (const cleanup of cleanups) cleanup();
  cleanups.length = 0;
});

describe('BaseApiMiddleware.pre', () => {
  test('attaches Bearer token to outgoing request', async () => {
    const ctx = makeRequest();

    await makeMiddleware('tok-123').pre(ctx);

    expect(ctx.headers.Authorization).toBe('Bearer tok-123');
  });

  test('throws when token is undefined', async () => {
    const middleware = new BaseApiMiddleware<FakeRequestContext, FakeResponseContext>(undefined);

    await expect(middleware.pre(makeRequest())).rejects.toThrow(
      /Request issued before access token was set/
    );
  });
});

describe('BaseApiMiddleware.post', () => {
  test('fires auth-expired and returns the response unchanged on 401', async () => {
    const handler = vi.fn();
    subscribe(handler);
    const response = { httpStatusCode: 401 };

    const result = await makeMiddleware().post(response);

    expect(handler).toHaveBeenCalledOnce();
    expect(result).toBe(response);
  });

  test.each([200, 204, 400, 403, 404, 500])(
    'does NOT fire the event for status %i',
    async status => {
      const handler = vi.fn();
      subscribe(handler);

      await makeMiddleware().post({ httpStatusCode: status });

      expect(handler).not.toHaveBeenCalled();
    }
  );

  test('is a safe no-op on 401 when nothing is subscribed', async () => {
    await expect(makeMiddleware().post({ httpStatusCode: 401 })).resolves.toEqual({
      httpStatusCode: 401,
    });
  });

  test('broadcasts to all subscribers on 401', async () => {
    const a = vi.fn();
    const b = vi.fn();
    subscribe(a);
    subscribe(b);

    await makeMiddleware().post({ httpStatusCode: 401 });

    expect(a).toHaveBeenCalledOnce();
    expect(b).toHaveBeenCalledOnce();
  });
});

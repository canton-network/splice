// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { AuthConfig, ClientCredentialsAuthConfig } from "./config.js";
import { logger } from "./logger.js";

const expiryMarginMs = 60 * 1000;

export interface TokenSource {
  getToken(): Promise<string>;
}

class StaticTokenSource implements TokenSource {
  constructor(private readonly token: string) {}
  async getToken(): Promise<string> {
    return this.token;
  }
}

type Token = { accessToken: string; expiresAtMs: number };

// Fetches a new token once the current one is about to expire. Concurrent callers share a single refresh.
export class ClientCredentialsTokenSource implements TokenSource {
  private token: Promise<Token> | undefined;

  constructor(
    private readonly fetchToken: () => Promise<Token>,
    private readonly now: () => number = Date.now,
  ) {}

  async getToken(): Promise<string> {
    if (this.token) {
      const current = await this.token.catch(() => undefined);
      if (current && this.now() < current.expiresAtMs - expiryMarginMs) {
        return current.accessToken;
      }
    }
    return (await this.refresh()).accessToken;
  }

  private refresh(): Promise<Token> {
    const pending = this.token;
    const refreshed = (async () => {
      const current = await pending?.catch(() => undefined);
      // Another caller may have refreshed the token while we were waiting.
      if (current && this.now() < current.expiresAtMs - expiryMarginMs) {
        return current;
      }
      return this.fetchToken();
    })();
    this.token = refreshed;
    return refreshed;
  }
}

async function getTokenEndpoint(wellKnownConfigUrl: string): Promise<string> {
  const response = await fetch(wellKnownConfigUrl);
  if (!response.ok) {
    throw new Error(
      `Failed to fetch OpenID configuration from ${wellKnownConfigUrl}: ${response.status} ${await response.text()}`,
    );
  }
  const body = (await response.json()) as { token_endpoint?: string };
  if (!body.token_endpoint) {
    throw new Error(
      `OpenID configuration at ${wellKnownConfigUrl} does not contain a token_endpoint`,
    );
  }
  return body.token_endpoint;
}

async function requestToken(
  auth: ClientCredentialsAuthConfig,
  tokenEndpoint: string,
): Promise<Token> {
  logger.info(
    `Requesting token for client id ${auth.clientId} from ${tokenEndpoint}`,
  );
  const params = new URLSearchParams({
    grant_type: "client_credentials",
    client_id: auth.clientId,
    client_secret: auth.clientSecret,
    audience: auth.audience,
  });
  if (auth.scope) {
    params.set("scope", auth.scope);
  }
  const response = await fetch(tokenEndpoint, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: params,
  });
  if (!response.ok) {
    throw new Error(
      `Failed to request token from ${tokenEndpoint}: ${response.status} ${await response.text()}`,
    );
  }
  const body = (await response.json()) as {
    access_token?: string;
    // in seconds
    expires_in?: number;
  };
  if (!body.access_token || !body.expires_in) {
    throw new Error(
      `Token response from ${tokenEndpoint} is missing access_token or expires_in`,
    );
  }
  const expiresAtMs = Date.now() + body.expires_in * 1000;
  logger.info(
    `Got token valid for ${body.expires_in}s, expiring at ${new Date(expiresAtMs).toISOString()}`,
  );
  return { accessToken: body.access_token, expiresAtMs };
}

export async function tokenSourceFromConfig(
  auth: AuthConfig,
): Promise<TokenSource> {
  switch (auth.type) {
    case "static":
      return new StaticTokenSource(auth.token);
    case "client-credentials": {
      const tokenEndpoint = await getTokenEndpoint(auth.wellKnownConfigUrl);
      const tokenSource = new ClientCredentialsTokenSource(() =>
        requestToken(auth, tokenEndpoint),
      );
      // Fail at startup if the credentials are wrong.
      await tokenSource.getToken();
      return tokenSource;
    }
  }
}

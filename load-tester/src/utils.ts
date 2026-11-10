// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
/* @ts-expect-error typings unavailable */
import { randomIntBetween, randomItem } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { sleep } from 'k6';
import { z } from 'zod';

import { FeaturedAppRightSpec } from './client/validator/models';
import { ValidatorClient } from './client/validator/validator';
import settings from './settings';
import { ValidatorConf } from './test/generate-load';

export function jsonStringDecoder<Z extends z.ZodTypeAny = z.ZodNever>(
  schema: Z,
  body: string,
): z.infer<Z> | undefined {
  const result = schema.safeParse(JSON.parse(body));
  if (result.success) {
    return result.data;
  } else {
    console.warn(`Failed to decode schema: ${result.error}`);
    return undefined;
  }
}

export function getTomorrowMs(): number {
  const now = Date.now();
  const dayToMs = 24 * 60 * 60 * 1000;

  return new Date(now + dayToMs).getTime() * 1000;
}

export function pickTwoRandom(nums: number): [number, number] {
  const arrayOfNums = Array.from({ length: nums }, (_, n) => n);

  const first = randomIntBetween(0, nums - 1);
  const second = randomItem(arrayOfNums.filter(n => n != first));

  return [first, second];
}

export function pickTwoRandomUsers(validators: ValidatorConf[]): {
  adminClient: ValidatorClient;
  senderClient: ValidatorClient;
  recipientClient: ValidatorClient;
} {
  if (validators.length > 1) {
    // Pick two random available validators
    const [validator1Index, validator2Index] = pickTwoRandom(validators.length);

    const validator1 = validators[validator1Index];
    const validator2 = validators[validator2Index];

    // Pick two random users from the validators
    const senderIndex = randomIntBetween(0, validator1.userTokens.length - 1);
    const recipientIndex = randomIntBetween(0, validator2.userTokens.length - 1);

    const senderToken = validator1.userTokens[senderIndex];
    const recipientToken = validator2.userTokens[recipientIndex];

    const senderFeatured = featuredAppRightSpec(validator1, senderIndex);
    const recipientFeatured = featuredAppRightSpec(validator2, recipientIndex);

    const adminClient = new ValidatorClient(
      validator1.walletBaseUrl,
      validator1.adminToken,
      undefined,
    );
    const senderClient = new ValidatorClient(validator1.walletBaseUrl, senderToken, senderFeatured);
    const recipientClient = new ValidatorClient(
      validator2.walletBaseUrl,
      recipientToken,
      recipientFeatured,
    );

    return { adminClient, senderClient, recipientClient };
  } else {
    const validatorConf: ValidatorConf = validators[0];
    const { adminToken, walletBaseUrl, userTokens } = validatorConf;

    // Pick two random users from that validator
    const [senderIndex, recipientIndex] = pickTwoRandom(userTokens.length);

    const senderToken = userTokens[senderIndex];
    const recipientToken = userTokens[recipientIndex];

    const senderFeatured = featuredAppRightSpec(validatorConf, senderIndex);
    const recipientFeatured = featuredAppRightSpec(validatorConf, recipientIndex);

    const adminClient = new ValidatorClient(walletBaseUrl, adminToken, undefined);
    const senderClient = new ValidatorClient(walletBaseUrl, senderToken, senderFeatured);
    const recipientClient = new ValidatorClient(walletBaseUrl, recipientToken, recipientFeatured);

    return { adminClient, senderClient, recipientClient };
  }
}

// Setting any field of a self-granted featured app right requires splice-wallet >= 0.1.26
const minWalletVersionForFeaturedAppConfig = [0, 1, 26];
const walletVersionCacheMs = 10 * 60 * 1000;
// Per walletBaseUrl, cached per VU
const featuredAppConfigSupportCache: Map<string, { supported: boolean; checkedAt: number }> =
  new Map();

function isVersionAtLeast(version: string, minVersion: number[]): boolean {
  const parts = version.split('.').map(Number);
  for (let i = 0; i < minVersion.length; i++) {
    const part = parts[i] ?? 0;
    if (Number.isNaN(part)) {
      console.warn(`Cannot parse package version ${version}`);
      return false;
    }
    if (part !== minVersion[i]) {
      return part > minVersion[i];
    }
  }
  return true;
}

// Whether the network's voted splice-wallet version supports configuring featured app rights.
// Returns undefined if that can't be determined right now.
function supportsFeaturedAppConfig(validator: ValidatorConf): boolean | undefined {
  const cached = featuredAppConfigSupportCache.get(validator.walletBaseUrl);
  if (cached && Date.now() - cached.checkedAt < walletVersionCacheMs) {
    return cached.supported;
  }
  const walletVersion = new ValidatorClient(
    validator.walletBaseUrl,
    validator.adminToken,
    undefined,
  ).v0.scanProxy.getAmuletRules()?.amulet_rules.contract.payload.configSchedule.initialValue
    .packageConfig.wallet;
  if (walletVersion === undefined) {
    return undefined;
  }
  const supported = isVersionAtLeast(walletVersion, minWalletVersionForFeaturedAppConfig);
  if (!supported) {
    console.log(
      `splice-wallet ${walletVersion} doesn't support configuring featured app rights, falling back to default rights`,
    );
  }
  featuredAppConfigSupportCache.set(validator.walletBaseUrl, { supported, checkedAt: Date.now() });
  return supported;
}

// The wanted featured app status of the given user, see ValidatorClient.featured.
// Configured rights fall back to a default (basic) right on networks that don't support
// configuring them. Returns undefined (don't touch the featured app status) if that can't be
// determined, or if an ops party can't be resolved yet because that user hasn't been onboarded.
function featuredAppRightSpec(
  validator: ValidatorConf,
  userIndex: number,
): FeaturedAppRightSpec | false | undefined {
  const config = settings.featuredAppRights[userIndex];
  if (config === undefined) {
    return false;
  }
  const { kind, weight, parties } = config;
  if (kind === undefined && weight === undefined && parties === undefined) {
    return {};
  }
  const supported = supportsFeaturedAppConfig(validator);
  if (supported === undefined) {
    return undefined;
  } else if (!supported) {
    return {};
  }
  if (parties === undefined) {
    return { kind, weight };
  }
  const opsParties = parties.map(i =>
    new ValidatorClient(validator.walletBaseUrl, validator.userTokens[i], undefined).partyId(),
  );
  if (opsParties.some(p => !p)) {
    console.log(`Not all ops parties of user ${userIndex} are onboarded yet`);
    return undefined;
  }
  return { kind, weight, opsParties: opsParties as string[] };
}

export function syncRetryUntil<A>(
  action: () => A | undefined,
  condition: (result: A | undefined) => boolean,
): A | undefined {
  let retries = 200; // The sleep is 200ms, so a larger retry value is fine
  let final = undefined;

  while (retries >= 0) {
    const result = action();
    if (condition(result)) {
      final = result;
      break;
    } else {
      sleep(0.2);
    }
    retries = retries - 1;
  }

  return final;
}

export function syncRetryUndefined<A>(action: () => A | undefined): A | undefined {
  return syncRetryUntil(action, result => typeof result !== 'undefined');
}

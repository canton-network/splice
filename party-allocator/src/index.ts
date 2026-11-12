// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { LedgerApiClient } from "./ledger-api-client.js";
import * as crypto from "node:crypto";
import {
  Command,
  DisclosedContract,
} from "@canton-network/canton-json-api-v2-openapi";
import { config, redactedConfig } from "./config.js";
import { logger } from "./logger.js";
import { tokenSourceFromConfig } from "./auth.js";
import { PrometheusExporter } from "@opentelemetry/exporter-prometheus";
import { AggregationType, MeterProvider } from "@opentelemetry/sdk-metrics";
import { Counter, Gauge, Histogram } from "@opentelemetry/api";
import { performance } from "perf_hooks"; // Use high-resolution monotonic clock
import { withNewTrace } from "./tracing.js";
import { fetchJson, retry, setRetryFailureListener } from "./retry.js";
import { AdaptiveConcurrency } from "./adaptive-concurrency.js";
import { CachedValue } from "./cache.js";
import { dispatch } from "./dispatch.js";
import { KeyPair, PartyStore } from "./party-store.js";

async function timed<T>(metric: Histogram, operation: () => Promise<T>) {
  const startTime = performance.now();
  try {
    return await operation();
  } finally {
    const endTime = performance.now();
    metric.record(endTime - startTime);
  }
}

const scanRetries = 20;

const scanCacheTtlMs = 10_000;

async function fetchAmuletRules() {
  return retry(
    "get amulet rules",
    async () => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const body = await fetchJson<any>(
        `${config.scanApiUrl}/api/scan/v0/amulet-rules`,
        {
          method: "POST",
          body: "{}",
          headers: { "content-type": "application/json" },
        },
      );
      if (!body?.amulet_rules_update) {
        throw new Error(
          `Unexpected amulet rules response: ${JSON.stringify(body)}`,
        );
      }
      return body.amulet_rules_update;
    },
    { maxRetries: scanRetries },
  );
}

const amuletRules = new CachedValue(fetchAmuletRules, scanCacheTtlMs);
const getAmuletRules = () => amuletRules.get();

async function getSynchronizerId() {
  const amuletRules = await getAmuletRules();
  return amuletRules.contract.payload.configSchedule.initialValue
    .decentralizedSynchronizer.activeSynchronizer;
}

async function fetchOpenRound() {
  return retry(
    "get open round",
    async () => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const body = await fetchJson<any>(
        `${config.scanApiUrl}/api/scan/v0/open-and-issuing-mining-rounds`,
        {
          method: "POST",
          body: '{"cached_open_mining_round_contract_ids": [], "cached_issuing_round_contract_ids": []}',
          headers: { "content-type": "application/json" },
        },
      );
      if (!body?.open_mining_rounds) {
        throw new Error(
          `Unexpected open mining rounds response: ${JSON.stringify(body)}`,
        );
      }
      const round = Object.values(body.open_mining_rounds)
        .filter((x: any) => new Date(x.contract.payload.opensAt) < new Date())
        .sort(
          (a: any, b: any) =>
            b.contract.payload.round.number - a.contract.payload.round.number,
        )[0] as any;
      if (!round) {
        throw new Error("No open mining round is currently open");
      }
      return round;
    },
    { maxRetries: scanRetries },
  );
}

const openRound = new CachedValue(fetchOpenRound, scanCacheTtlMs);
const getOpenRound = () => openRound.get();

const invalidateScanCache = {
  onFailure: () => {
    amuletRules.invalidate();
    openRound.invalidate();
  },
};

async function getValidatorPartyId() {
  return retry(
    "get validator party id",
    async () => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const body = await fetchJson<any>(
        `${config.validatorApiUrl}/api/validator/v0/validator-user`,
      );
      if (!body?.party_id) {
        throw new Error(
          `Unexpected validator user response: ${JSON.stringify(body)}`,
        );
      }
      return body.party_id as string;
    },
    { maxRetries: scanRetries },
  );
}

function toDisclosedContract(c: any): DisclosedContract {
  return {
    templateId: c.contract.template_id,
    contractId: c.contract.contract_id,
    createdEventBlob: c.contract.created_event_blob,
    synchronizerId: c.domain_id,
  };
}

const amuletTemplate = "#splice-amulet:Splice.Amulet:Amulet";
const preapprovalTemplate =
  "#splice-amulet:Splice.AmuletRules:TransferPreapproval";
const preapprovalProposalTemplate =
  "#splice-wallet:Splice.Wallet.TransferPreapproval:TransferPreapprovalProposal";

async function hasContract(
  client: LedgerApiClient,
  partyId: string,
  templateId: string,
) {
  const contracts = await client.queryContracts([partyId], [templateId]);
  return contracts.length > 0;
}

async function waitForPreapproval(client: LedgerApiClient, partyId: string) {
  await retry(
    "wait for preapproval",
    async () => {
      // Polled by the outer retry so no need to also retry each query.
      const response = await client.queryContracts(
        [partyId],
        [preapprovalTemplate],
        { maxRetries: 1 },
      );
      if (response.length === 0) {
        throw new Error(`No preapproval for ${partyId}`);
      }
    },
    {
      maxRetries: config.preapprovalRetries,
      initialDelayMs: config.preapprovalRetryDelayMs,
    },
  );
}

async function setupTopology(
  client: LedgerApiClient,
  synchronizerId: string,
  partyHint: string,
  keyPair: KeyPair,
) {
  const generateTopologyResponse = await client.generateExternalPartyTopology(
    synchronizerId,
    partyHint,
    {
      format: "CRYPTO_KEY_FORMAT_DER_X509_SUBJECT_PUBLIC_KEY_INFO",
      keyData: keyPair.publicKey.toString("base64"),
      keySpec: "SIGNING_KEY_SPEC_EC_CURVE25519",
    },
  );
  const signature = crypto.sign(
    null,
    Buffer.from(generateTopologyResponse.multiHash ?? "", "base64"),
    keyPair.privateKey,
  );
  await client.allocateExternalParty(
    generateTopologyResponse.partyId ?? "",
    synchronizerId,
    generateTopologyResponse.topologyTransactions!.map((t) => ({
      transaction: t,
      signatures: [],
    })),
    [
      {
        format: "SIGNATURE_FORMAT_RAW",
        signature: signature.toString("base64"),
        signedBy: generateTopologyResponse.partyId!.split("::")[1],
        signingAlgorithmSpec: "SIGNING_ALGORITHM_SPEC_ED25519",
      },
    ],
  );
  return generateTopologyResponse.partyId;
}

async function tap(
  client: LedgerApiClient,
  synchronizerId: string,
  partyId: string,
  keyPair: KeyPair,
) {
  return client.submitTransaction(
    "tap",
    synchronizerId,
    partyId,
    keyPair.privateKey,
    async () => {
      const [rules, round] = await Promise.all([
        getAmuletRules(),
        getOpenRound(),
      ]);
      const command = new Command();
      command.ExerciseCommand = {
        templateId: "#splice-amulet:Splice.AmuletRules:AmuletRules",
        choice: "AmuletRules_DevNet_Tap",
        contractId: rules.contract.contract_id,
        choiceArgument: {
          receiver: partyId,
          amount: "100.0",
          openRound: round.contract.contract_id,
        },
      };
      return {
        command,
        disclosedContracts: [rules, round].map(toDisclosedContract),
      };
    },
    invalidateScanCache,
  );
}

async function createPreapprovalProposal(
  client: LedgerApiClient,
  synchronizerId: string,
  partyId: string,
  validatorPartyId: string,
  keyPair: KeyPair,
) {
  await client.submitTransaction(
    "create preapproval proposal",
    synchronizerId,
    partyId,
    keyPair.privateKey,
    async () => {
      const rules = await getAmuletRules();
      const command = new Command();
      command.CreateCommand = {
        templateId: preapprovalProposalTemplate,
        createArguments: {
          receiver: partyId,
          provider: validatorPartyId,
          expectedDso: rules.contract.payload.dso,
        },
      };
      return { command, disclosedContracts: [] };
    },
    invalidateScanCache,
  );
}

// Creates the preapproval proposal unless a previous attempt already did so and waits for the
// validator automation to accept it.
async function setupPreapproval(
  metrics: Metrics,
  client: LedgerApiClient,
  synchronizerId: string,
  partyId: string,
  validatorPartyId: string,
  keyPair: KeyPair,
  resumed: boolean,
) {
  const wait = () =>
    timed(metrics.preapprovalWaitLatencyMs, () =>
      waitForPreapproval(client, partyId),
    );
  // Check the proposal first: accepting it archives it and creates the preapproval so in this order
  // we can't miss both.
  if (resumed) {
    if (await hasContract(client, partyId, preapprovalProposalTemplate)) {
      logger.info(`Preapproval proposal for ${partyId} already exists`);
      metrics.stepsSkipped.add(1, { step: "preapproval_proposal" });
      return wait();
    }
    if (await hasContract(client, partyId, preapprovalTemplate)) {
      logger.info(`Preapproval for ${partyId} already exists`);
      metrics.stepsSkipped.add(1, { step: "preapproval" });
      return;
    }
  }
  await createPreapprovalProposal(
    client,
    synchronizerId,
    partyId,
    validatorPartyId,
    keyPair,
  );
  await wait();
}

// Each step is skipped if a previous attempt (possibly before a restart) already completed it.
async function setupParty(
  metrics: Metrics,
  client: LedgerApiClient,
  store: PartyStore,
  synchronizerId: string,
  index: number,
  validatorPartyId: string,
) {
  logger.info(`Starting setup for party ${index}`);
  await store.markPending(index);
  const { keyPair, existed: resumed } = await store.loadOrCreateKeyPair(index);
  if (resumed) {
    logger.info(`Resuming setup for party ${index} with its existing key`);
    metrics.partiesResumedCounter.add(1);
  }

  const partyHint = `party-${index}`;
  const partyId = (await timed(metrics.partyAllocationLatencyMs, () =>
    setupTopology(client, synchronizerId, partyHint, keyPair),
  ))!;

  if (resumed && (await hasContract(client, partyId, amuletTemplate))) {
    logger.info(`Party ${partyId} already has amulets, skipping tap`);
    metrics.stepsSkipped.add(1, { step: "tap" });
  } else {
    await timed(metrics.tapLatencyMs, () =>
      tap(client, synchronizerId, partyId, keyPair),
    );
  }
  await timed(metrics.preapprovalLatencyMs, () =>
    setupPreapproval(
      metrics,
      client,
      synchronizerId,
      partyId,
      validatorPartyId,
      keyPair,
      resumed,
    ),
  );
  await store.markDone(index);
  logger.info(`Finished setup for party ${index}`);
}

export type Metrics = {
  partiesAllocatedCounter: Counter;
  partyAllocationFailuresCounter: Counter;
  partiesGivenUpCounter: Counter;
  partiesResumedCounter: Counter;
  stepsSkipped: Counter;
  taskFailures: Counter;
  parallelism: Gauge;
  totalPartiesAllocated: Gauge;
  partyAllocationLatencyMs: Histogram;
  tapLatencyMs: Histogram;
  preapprovalLatencyMs: Histogram;
  preapprovalWaitLatencyMs: Histogram;
  observeQueues: (inFlight: () => number, retryQueueSize: () => number) => void;
};

function setupMetrics(): Metrics {
  const exporter = new PrometheusExporter({
    port: 10013,
    prefix: "party_allocator",
  });
  const meterProvider = new MeterProvider({
    readers: [exporter],
    views: [
      // The opentelemetry library has support for exponential histograms but the prometheus exporter does not so
      // we go for explicit buckets here.
      {
        aggregation: {
          type: AggregationType.EXPLICIT_BUCKET_HISTOGRAM,
          options: {
            boundaries: [
              0, 500, 1000, 2000, 4000, 8000, 16000, 32000, 64000, 128000,
            ],
          },
        },
        instrumentName: "latency_*",
      },
    ],
  });
  const meter = meterProvider.getMeter("party_allocator");
  const partiesAllocatedCounter = meter.createCounter("parties_allocated", {
    description: "Counter for number of parties that have been allocated",
  });
  const partyAllocationFailuresCounter = meter.createCounter(
    "party_allocation_failures",
    { description: "Counter for number of failed party setup attempts" },
  );
  const parallelism = meter.createGauge("parallelism", {
    description: "Current number of parties being set up in parallel",
  });
  const totalPartiesAllocated = meter.createGauge("total_parties_allocated", {
    description: "Total number of parties that have been allocated",
  });
  const partyAllocationLatencyMs = meter.createHistogram(
    "latency_party_allocation",
    { description: "Latency of the topology setup of a party in ms" },
  );
  const tapLatencyMs = meter.createHistogram("latency_tap", {
    description: "Latency of executing a tap for a party in ms",
  });
  const preapprovalLatencyMs = meter.createHistogram("latency_preapproval", {
    description:
      "Latency of setting up the preapproval including waiting for the validator automation to accept the proposal in ms",
  });
  const preapprovalWaitLatencyMs = meter.createHistogram(
    "latency_preapproval_wait",
    {
      description:
        "Latency from the preapproval proposal being created until the preapproval created by the validator automation is visible in ms",
    },
  );
  const partiesGivenUpCounter = meter.createCounter("parties_given_up", {
    description: `Number of parties whose setup failed ${maxPartySetupAttempts} times and is not retried until the next restart`,
  });
  const partiesResumedCounter = meter.createCounter("parties_resumed", {
    description:
      "Number of party setups that reused the key of an earlier attempt, e.g. after a restart",
  });
  const stepsSkipped = meter.createCounter("steps_skipped", {
    description:
      "Number of setup steps skipped as an earlier attempt already completed them, by step",
  });
  const taskFailures = meter.createCounter("task_failures", {
    description:
      "Number of failed attempts of retried tasks, by task, whether the error was transient (5xx, 429, network) and whether it was retried",
  });
  const observeQueues = (
    inFlight: () => number,
    retryQueueSize: () => number,
  ) => {
    meter
      .createObservableGauge("in_flight", {
        description: "Number of party setups currently running",
      })
      .addCallback((r) => r.observe(inFlight()));
    meter
      .createObservableGauge("retry_queue_size", {
        description:
          "Number of failed or resumed party setups waiting to be retried",
      })
      .addCallback((r) => r.observe(retryQueueSize()));
  };
  return {
    partiesAllocatedCounter,
    partyAllocationFailuresCounter,
    partiesGivenUpCounter,
    partiesResumedCounter,
    stepsSkipped,
    taskFailures,
    parallelism,
    totalPartiesAllocated,
    partyAllocationLatencyMs,
    tapLatencyMs,
    preapprovalLatencyMs,
    preapprovalWaitLatencyMs,
    observeQueues,
  };
}

const maxPartySetupAttempts = 3;

function* partyIndexGenerator(from: number, until: number): Generator<number> {
  for (let index = from; index < until; index++) {
    yield index;
  }
}

async function main() {
  const metrics = setupMetrics();
  logger.info(`Running with config: ${JSON.stringify(redactedConfig(config))}`);
  const synchronizerId = await getSynchronizerId();
  logger.info(`Synchronizer id: ${synchronizerId}`);
  const validatorPartyId = await getValidatorPartyId();
  logger.info(`Validator party id: ${validatorPartyId}`);

  const store = new PartyStore(config.keyDirectory);
  const { nextIndex, pending } = await store.load();
  metrics.totalPartiesAllocated.record(nextIndex);
  logger.info(`Starting at ${nextIndex}`);
  if (pending.length > 0) {
    logger.info(
      `Resuming ${pending.length} unfinished party setups: ${pending.join(", ")}`,
    );
  }

  const tokenSource = await tokenSourceFromConfig(config.auth);
  const client = new LedgerApiClient(config.jsonLedgerApiUrl, tokenSource);

  // This is idempotent so we just always grant it. We don't revoke it at the end as keeping it doesn't do any harm
  await client.grantExecuteAndReadAsAnyPartyRights(config.userId);

  const queue = partyIndexGenerator(nextIndex, config.maxParties);
  // Parties to (re)try before new ones: unfinished ones from before a restart and failed ones.
  const retryQueue: number[] = [...pending];
  const attempts = new Map<number, number>();
  let maxPartyAllocated = nextIndex;

  const concurrency = new AdaptiveConcurrency(
    config.parallelism,
    undefined,
    undefined,
    (limit) => metrics.parallelism.record(limit),
  );
  setRetryFailureListener(({ task, transient, willRetry }) => {
    metrics.taskFailures.add(1, {
      task,
      transient: String(transient),
      retried: String(willRetry),
    });
    if (transient) {
      concurrency.recordFailure();
    }
  });
  let inFlight = 0;
  metrics.observeQueues(
    () => inFlight,
    () => retryQueue.length,
  );

  const setupPartyWithTrace = (partyIndex: number) =>
    withNewTrace(async () => {
      const attempt = (attempts.get(partyIndex) ?? 0) + 1;
      attempts.set(partyIndex, attempt);
      inFlight++;
      try {
        await setupParty(
          metrics,
          client,
          store,
          synchronizerId,
          partyIndex,
          validatorPartyId,
        );
        attempts.delete(partyIndex);
        concurrency.recordSuccess();
        metrics.partiesAllocatedCounter.add(1);
        maxPartyAllocated = Math.max(maxPartyAllocated, partyIndex);
        metrics.totalPartiesAllocated.record(maxPartyAllocated);
      } catch (e: unknown) {
        const errorMessage = e instanceof Error ? e.message : JSON.stringify(e);
        metrics.partyAllocationFailuresCounter.add(1);
        concurrency.recordFailure();
        if (attempt < maxPartySetupAttempts) {
          logger.warn(
            `Setup for party ${partyIndex} failed (attempt ${attempt}/${maxPartySetupAttempts}), will retry: ${errorMessage}`,
          );
          retryQueue.push(partyIndex);
        } else {
          attempts.delete(partyIndex);
          metrics.partiesGivenUpCounter.add(1);
          logger.error(
            `Setup for party ${partyIndex} failed after ${attempt} attempts, skipping it until the next restart: ${errorMessage}`,
          );
        }
      } finally {
        inFlight--;
      }
    });

  const nextParty = (): number | undefined => {
    const retried = retryQueue.shift();
    if (retried !== undefined) {
      return retried;
    }
    const next = queue.next();
    return next.done ? undefined : next.value;
  };

  await dispatch(nextParty, setupPartyWithTrace, () => concurrency.limit);
  logger.info(`Party allocator, completed. Sleeping`);
  // sleep forever so k8s doesn't restart it over and over.
  await sleepForever();
}

// A pending promise alone does not keep node running so we also need a timer.
function sleepForever(): Promise<never> {
  return new Promise(() => setInterval(() => {}, 60 * 60 * 1000));
}

await main();

export {};

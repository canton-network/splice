// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  DefaultApi as LedgerJsonApi,
  JsTransaction,
} from "@lfdecentralizedtrust/canton-json-api-v2-openapi";
import {
  TokenStandardEvent,
  Transaction,
  Holding as HoldingResult,
  Label,
} from "./types";
import { Event as LedgerApiEvent } from "@lfdecentralizedtrust/canton-json-api-v2-openapi/dist/models/Event";
import {
  EventLogInterface,
  HoldingInterfaceV2,
  ReasonMetaKey,
} from "../constants";
import { EventLog_HoldingsChange } from "@daml.js/splice-api-token-transfer-events-v2-1.0.0/lib/Splice/Api/Token/TransferEventsV2/module";
import { Holding } from "@daml.js/splice-api-token-holding-v2-1.0.0/lib/Splice/Api/Token/HoldingV2";
import { getEventsOfContract, getMetaKeyValue } from "../apis/ledger-api-utils";
import { computeSummary, holdingChangesNonEmpty } from "./summary";

export class V2TransactionParser {
  private readonly ledgerClient: LedgerJsonApi;
  private readonly partyId: string;
  private readonly transaction: JsTransaction;

  constructor(
    transaction: JsTransaction,
    ledgerClient: LedgerJsonApi,
    partyId: string,
  ) {
    this.ledgerClient = ledgerClient;
    this.partyId = partyId;
    this.transaction = transaction;
  }

  async parseTransaction(): Promise<Transaction> {
    const tx = this.transaction;
    const events = await this.parseEvents(tx.events);
    return {
      updateId: tx.updateId,
      offset: tx.offset,
      recordTime: tx.recordTime,
      synchronizerId: tx.synchronizerId,
      events,
    };
  }

  async parseEvents(events: LedgerApiEvent[]): Promise<TokenStandardEvent[]> {
    const creates = events
      .map((event) => this.extractHoldingCreate(event.CreatedEvent))
      .filter((item) => item !== null);
    const cachedHoldings = new Map(
      creates.map((holding) => [holding.holding.contractId, holding.holding]),
    );
    const archives = (
      await Promise.all(
        events.map((event) =>
          this.extractHoldingsArchive(
            event.ExercisedEvent || event.ArchivedEvent,
            cachedHoldings,
          ),
        ),
      )
    ).filter((item) => item !== null);

    const holdingChanges = await Promise.all(
      events
        .map(this.extractChoiceArgumentEventLog_HoldingsChange)
        .filter((item) => item !== null)
        .map((holdingsChange) =>
          this.parseHoldingsChange(holdingsChange, cachedHoldings),
        ),
    );
    const accountedForHoldings = new Set(
      holdingChanges
        .flatMap((holdingsChange) =>
          holdingsChange.lockedHoldingsChange.archives
            .concat(holdingsChange.lockedHoldingsChange.creates)
            .concat(holdingsChange.unlockedHoldingsChange.archives)
            .concat(holdingsChange.unlockedHoldingsChange.creates),
        )
        .map((holding) => holding.contractId),
    );

    const unaccountedCreates: TokenStandardEvent[] = creates
      .filter(
        (rawCreate) =>
          rawCreate.holding.owner === this.partyId &&
          !accountedForHoldings.has(rawCreate.holding.contractId),
      )
      .map((rawCreate) => this.buildRawCreate(rawCreate.holding));
    const unaccountedArchives = archives
      .filter(
        (rawArchive) =>
          rawArchive.holding.owner === this.partyId &&
          !accountedForHoldings.has(rawArchive.holding.contractId),
      )
      .map((rawCreate) => this.buildRawArchive(rawCreate.holding));

    const result = holdingChanges
      .filter((change) => holdingChangesNonEmpty(change))
      .concat(unaccountedArchives)
      .concat(unaccountedCreates);

    return result;
  }

  extractHoldingCreate(
    createdEvent: LedgerApiEvent["CreatedEvent"],
  ): ExtractedHolding | null {
    if (!createdEvent || !this.createdEventInvolvesUser(createdEvent)) {
      return null;
    }
    const { interfaceViews } = createdEvent;
    const holdingView = interfaceViews?.find((view) =>
      HoldingInterfaceV2.matches(view.interfaceId),
    );
    if (!holdingView) {
      return null;
    }

    const result = holdingViewToResult(
      createdEvent.contractId,
      Holding.decoder.runWithException(holdingView.viewValue),
    );

    return { holding: result, nodeId: createdEvent.nodeId };
  }

  async extractHoldingsArchive(
    archiveEvent:
      | LedgerApiEvent["ExercisedEvent"]
      | LedgerApiEvent["ArchivedEvent"]
      | undefined,
    cachedHoldings: Map<string, HoldingResult>,
  ): Promise<ExtractedHolding | null> {
    if (
      !archiveEvent ||
      !archiveEvent.implementedInterfaces?.some((interfaceId) =>
        HoldingInterfaceV2.matches(interfaceId),
      )
    ) {
      return null;
    }
    const result = await this.resolveHolding(
      archiveEvent.contractId,
      cachedHoldings,
    );
    return result && { holding: result, nodeId: archiveEvent.nodeId };
  }

  extractChoiceArgumentEventLog_HoldingsChange(
    event: LedgerApiEvent,
  ): EventLog_HoldingsChange | null {
    const exercisedEvent = event.ExercisedEvent;
    if (!exercisedEvent) {
      return null;
    }
    const { interfaceId, choice, choiceArgument } = exercisedEvent;

    if (
      interfaceId &&
      EventLogInterface.matches(interfaceId) &&
      choice === "EventLog_HoldingsChange"
    ) {
      return choiceArgument;
    } else {
      return null;
    }
  }

  async parseHoldingsChange(
    holdingsChange: EventLog_HoldingsChange,
    cachedHoldings: Map<string, HoldingResult>,
  ): Promise<TokenStandardEvent> {
    // exclude holdings that are both in input and output
    const inputHoldingCids = holdingsChange.inputHoldingCids.filter(
      (cid) => holdingsChange.outputHoldingCids.indexOf(cid) === -1,
    );
    const outputHoldingCids = holdingsChange.outputHoldingCids.filter(
      (cid) => holdingsChange.inputHoldingCids.indexOf(cid) === -1,
    );

    const resolvedInputHoldings = (
      await Promise.all(
        inputHoldingCids.map((cid) => this.resolveHolding(cid, cachedHoldings)),
      )
    ).filter((h) => h !== null);
    const resolvedOutputHoldings = (
      await Promise.all(
        outputHoldingCids.map((cid) =>
          this.resolveHolding(cid, cachedHoldings),
        ),
      )
    ).filter((h) => h !== null);

    const unlockedInputHoldings = resolvedInputHoldings.filter((h) => !h.lock);
    const lockedInputHoldings = resolvedInputHoldings.filter((h) => !!h.lock);
    const unlockedOutputHoldings = resolvedOutputHoldings.filter(
      (h) => !h.lock,
    );
    const lockedOutputHoldings = resolvedOutputHoldings.filter((h) => !!h.lock);

    // only this.partyId's holdings should be included in the response
    const unlockedHoldingsChange = {
      creates: unlockedOutputHoldings.filter((h) => h.owner === this.partyId),
      archives: unlockedInputHoldings.filter((h) => h.owner === this.partyId),
    };
    const lockedHoldingsChange = {
      creates: lockedOutputHoldings.filter((h) => h.owner === this.partyId),
      archives: lockedInputHoldings.filter((h) => h.owner === this.partyId),
    };

    return {
      label: this.getLabel(holdingsChange),
      unlockedHoldingsChange,
      unlockedHoldingsChangeSummary: computeSummary(
        unlockedHoldingsChange,
        this.partyId,
      ),
      lockedHoldingsChange,
      lockedHoldingsChangeSummary: computeSummary(
        lockedHoldingsChange,
        this.partyId,
      ),
      transferInstruction: null,
    };
  }

  getLabel(holdingsChange: EventLog_HoldingsChange): Label {
    const reason = getMetaKeyValue(
      ReasonMetaKey,
      holdingsChange.extraArgs.meta,
    );
    return {
      type: "V2",
      transferLegSides: holdingsChange.transferLegSides,
      reason,
      meta: holdingsChange.extraArgs.meta,
    };
  }

  async resolveHolding(
    cid: string,
    cachedHoldings: Map<string, HoldingResult>,
  ): Promise<HoldingResult | null> {
    const cached = cachedHoldings.get(cid);
    if (cached) {
      return cached;
    }

    const fromEvent = await getEventsOfContract(
      this.ledgerClient,
      cid,
      this.partyId,
      [HoldingInterfaceV2],
    );
    if (
      !fromEvent ||
      !fromEvent.created ||
      !this.createdEventInvolvesUser(fromEvent.created.createdEvent)
    ) {
      return null;
    }

    const holding = this.extractHoldingCreate(fromEvent.created.createdEvent);
    if (!holding) {
      throw new Error(
        `Contract ${cid} should be a Holding but it's not: ${JSON.stringify(fromEvent)}`,
      );
    }
    cachedHoldings.set(cid, holding.holding);
    return holding.holding;
  }

  createdEventInvolvesUser(
    createdEvent: LedgerApiEvent["CreatedEvent"],
  ): boolean {
    return createdEvent.witnessParties
      .concat(createdEvent.signatories)
      .concat(createdEvent.observers || [])
      .some((party) => this.partyId === party);
  }

  buildRawCreate(holding: HoldingResult): TokenStandardEvent {
    const lockedHoldingsChange = {
      archives: [],
      creates: holding.lock ? [holding] : [],
    };
    const unlockedHoldingsChange = {
      archives: [],
      creates: !holding.lock ? [holding] : [],
    };
    return {
      label: {
        type: "Create",
        contractId: holding.contractId,
        meta: holding.meta,
        payload: holding,
        templateId: "TODO",
        packageName: "TODO",
        offset: 123,
        parentChoice: "TODO",
      },
      lockedHoldingsChange,
      unlockedHoldingsChange,
      lockedHoldingsChangeSummary: computeSummary(
        lockedHoldingsChange,
        this.partyId,
      ),
      unlockedHoldingsChangeSummary: computeSummary(
        unlockedHoldingsChange,
        this.partyId,
      ),
      transferInstruction: null,
    };
  }

  buildRawArchive(holding: HoldingResult): TokenStandardEvent {
    const lockedHoldingsChange = {
      creates: [],
      archives: holding.lock ? [holding] : [],
    };
    const unlockedHoldingsChange = {
      creates: [],
      archives: !holding.lock ? [holding] : [],
    };
    return {
      label: {
        type: "Archive",
        contractId: holding.contractId,
        meta: holding.meta,
        payload: holding,
        // TODO: needs original & parents
        actingParties: [],
        templateId: "TODO",
        packageName: "TODO",
        offset: 123,
        parentChoice: "TODO",
      },
      lockedHoldingsChange,
      unlockedHoldingsChange,
      lockedHoldingsChangeSummary: computeSummary(
        lockedHoldingsChange,
        this.partyId,
      ),
      unlockedHoldingsChangeSummary: computeSummary(
        unlockedHoldingsChange,
        this.partyId,
      ),
      transferInstruction: null,
    };
  }
}

interface ExtractedHolding {
  holding: HoldingResult;
  nodeId: number;
}

function holdingViewToResult(cid: string, holding: Holding): HoldingResult {
  return {
    contractId: cid,
    amount: holding.amount,
    owner: holding.account.owner ?? "<missing>",
    instrumentId: holding.instrumentId,
    lock: holding.lock
      ? {
          holders: holding.lock.holders,
          context: holding.lock.context || null,
          expiresAfter: holding.lock.expiresAfter?.microseconds || null,
          expiresAt: holding.lock.expiresAt || null,
        }
      : null,
    meta: holding.meta,
  };
}

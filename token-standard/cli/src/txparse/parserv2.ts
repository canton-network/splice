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
import { computeSummary } from "./summary";

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
    const createdHoldingsMap = new Map(
      events
        .map((event) => this.extractHoldingCreate(event.CreatedEvent))
        .filter((item) => item !== null),
    );
    return Promise.all(
      events
        .map(this.extractChoiceArgumentEventLog_HoldingsChange)
        .filter((item) => item !== null)
        .map((holdingsChange) =>
          this.parseHoldingsChange(holdingsChange, createdHoldingsMap),
        ),
    );
  }

  extractHoldingCreate(
    createdEvent: LedgerApiEvent["CreatedEvent"],
  ): [string, Holding] | null {
    if (!createdEvent) {
      return null;
    }
    const { interfaceViews } = createdEvent;
    const holdingView = interfaceViews?.find((view) =>
      HoldingInterfaceV2.matches(view.interfaceId),
    );
    if (!holdingView) {
      return null;
    }

    return [
      createdEvent.contractId,
      Holding.decoder.runWithException(holdingView.viewValue),
    ];
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
    cachedHoldings: Map<string, Holding>,
  ): Promise<TokenStandardEvent> {
    // TODO: exclude holdings that are both in input and output
    const resolvedInputHoldings = (
      await Promise.all(
        holdingsChange.inputHoldingCids.map((cid) =>
          this.resolveHolding(cid, cachedHoldings),
        ),
      )
    ).filter((h) => h !== null);
    const unlockedInputHoldings = resolvedInputHoldings.filter((h) => !h.lock);
    const lockedInputHoldings = resolvedInputHoldings.filter((h) => !!h.lock);
    const resolvedOutputHoldings = (
      await Promise.all(
        holdingsChange.outputHoldingCids.map((cid) =>
          this.resolveHolding(cid, cachedHoldings),
        ),
      )
    ).filter((h) => h !== null);
    const unlockedOutputHoldings = resolvedOutputHoldings.filter(
      (h) => !h.lock,
    );
    const lockedOutputHoldings = resolvedOutputHoldings.filter((h) => !!h.lock);

    const unlockedHoldingsChange = {
      archives: unlockedOutputHoldings,
      creates: unlockedInputHoldings,
    };
    const lockedHoldingsChange = {
      archives: lockedOutputHoldings,
      creates: lockedInputHoldings,
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
    cachedHoldings: Map<string, Holding>,
  ): Promise<HoldingResult | null> {
    let result = cachedHoldings.get(cid);
    if (!result) {
      const fromEvent = await getEventsOfContract(
        this.ledgerClient,
        cid,
        this.partyId,
        [HoldingInterfaceV2],
      );
      if (!fromEvent || !fromEvent.created) {
        // User is likely not an observer or events of contract were pruned
        // TODO: added for debugging
        return {
          contractId: cid,
          amount: "unknown",
          instrumentId: { id: "unknown", admin: "unknown" },
          lock: null,
          meta: {},
          owner: "unknown",
        };
      }

      const holding = this.extractHoldingCreate(fromEvent.created.createdEvent);
      if (!holding) {
        throw new Error(
          `Contract ${cid} should be a Holding but it's not: ${JSON.stringify(fromEvent)}`,
        );
      }
      cachedHoldings.set(cid, holding[1]);
      result = holding[1];
    }
    return {
      contractId: cid,
      amount: result.amount,
      owner: result.account.owner ?? "<missing>",
      instrumentId: result.instrumentId,
      lock: result.lock
        ? {
            holders: result.lock.holders,
            context: result.lock.context || undefined,
            expiresAfter: result.lock.expiresAfter?.microseconds || undefined,
            expiresAt: result.lock.expiresAt || undefined,
          }
        : null,
      meta: result.meta,
    };
  }
}

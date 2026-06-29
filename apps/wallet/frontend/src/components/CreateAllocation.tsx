// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React, { useState } from 'react';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { useMutation } from '@tanstack/react-query';
import {
  AllocateAmuletV2Request,
  AllocateAmuletRequestSettlementSettlementRef,
  TransferLegSide,
} from '@canton-network/wallet-openapi';
import {
  Alert,
  Button,
  Card,
  CardContent,
  Checkbox,
  Divider,
  FormControlLabel,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import BigNumber from 'bignumber.js';
import { DisableConditionally } from '@canton-network/splice-common-frontend';
import BftAnsField from './BftAnsField';
import AmountInput from './AmountInput';
import { Add, Remove } from '@mui/icons-material';
import {
  DAML_TIMESTAMP_FORMAT,
  damlTimestampToOpenApiTimestamp,
  isValidDamlTimestamp,
} from '../utils/timestampConversion';
import { usePrimaryParty } from '../hooks';
import { TextMap, TextMapEditor } from './TextMap';

const CreateAllocation: React.FC = () => {
  const { createAllocationV2 } = useWalletClient();
  const userParty = usePrimaryParty();
  const [error, setError] = useState<object | null>(null);
  const [allocation, setAllocation] = useState<PartialAllocateAmuletV2Request>(emptyForm());
  const validated = validatedForm(userParty!, allocation);
  const createAllocationMutation = useMutation({
    mutationFn: async () => {
      return validated && (await createAllocationV2(validated));
    },
    onSuccess: () => {
      setError(null);
      setAllocation(emptyForm());
    },
    onError: error => {
      console.error('Failed to submit allocation', error);
      setError(error);
    },
  });

  const updateExecutor = (idx: number, party: string) => {
    const newExecutors = [...allocation.settlement.executors];
    newExecutors[idx] = party;
    setAllocation({
      ...allocation,
      settlement: { ...allocation.settlement, executors: newExecutors },
    });
  };

  const addExecutor = () => {
    setAllocation({
      ...allocation,
      settlement: {
        ...allocation.settlement,
        executors: [...allocation.settlement.executors, ''],
      },
    });
  };

  const removeExecutor = (idx: number) => {
    const newExecutors = allocation.settlement.executors.filter((_, i) => i !== idx);
    setAllocation({
      ...allocation,
      settlement: {
        ...allocation.settlement,
        executors: newExecutors.length > 0 ? newExecutors : [''],
      },
    });
  };

  const updateLeg = (idx: number, updated: Partial<PartialTransferLeg>) => {
    const newLegs = [...allocation.transfer_legs];
    newLegs[idx] = { ...newLegs[idx], ...updated };
    setAllocation({ ...allocation, transfer_legs: newLegs });
  };

  const addLeg = () => {
    setAllocation({
      ...allocation,
      transfer_legs: [...allocation.transfer_legs, emptyTransferLeg()],
    });
  };

  const removeLeg = (idx: number) => {
    const newLegs = allocation.transfer_legs.filter((_, i) => i !== idx);
    setAllocation({
      ...allocation,
      transfer_legs: newLegs.length > 0 ? newLegs : [emptyTransferLeg()],
    });
  };

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={6} variant="h4">
        Create Allocation
      </Typography>
      <Card variant="outlined">
        <CardContent sx={{ paddingX: '64px' }}>
          <Stack spacing={2}>
            {error ? (
              <Alert severity="error">Failed to submit: {JSON.stringify(error)}</Alert>
            ) : null}

            <Typography variant="h5">Settlement</Typography>
            <Typography variant="h6">Settlement Ref</Typography>
            <Stack direction="row" alignItems="center" spacing={2}>
              <Typography variant="h6">ID</Typography>
              <TextField
                id="create-allocation-settlement-ref-id"
                value={allocation.settlement.settlement_ref?.id || ''}
                error={!allocation.settlement.settlement_ref?.id}
                onChange={event =>
                  setAllocation({
                    ...allocation,
                    settlement: {
                      ...allocation.settlement,
                      settlement_ref: {
                        id: event.target.value,
                        cid: allocation.settlement.settlement_ref?.cid,
                      },
                    },
                  })
                }
              />
              <Typography variant="h6">Contract ID (optional)</Typography>
              <TextField
                id="create-allocation-settlement-ref-cid"
                value={allocation.settlement.settlement_ref?.cid || ''}
                onChange={event =>
                  setAllocation({
                    ...allocation,
                    settlement: {
                      ...allocation.settlement,
                      settlement_ref: {
                        id: allocation.settlement.settlement_ref?.id || '',
                        cid: event.target.value || undefined,
                      },
                    },
                  })
                }
              />
            </Stack>
            <Typography variant="h6">Executors</Typography>
            {allocation.settlement.executors.map((_executor, idx) => (
              <Stack key={idx} direction="row" alignItems="center" spacing={1}>
                <BftAnsField
                  name={`Executor ${idx}`}
                  label={`Executor ${idx + 1}`}
                  aria-label={`Executor ${idx}`}
                  id={`create-allocation-settlement-executor-${idx}`}
                  onPartyChanged={party => updateExecutor(idx, party)}
                />
                {allocation.settlement.executors.length > 1 && (
                  <Button
                    startIcon={<Remove />}
                    color="error"
                    size="small"
                    onClick={() => removeExecutor(idx)}
                  >
                    Remove
                  </Button>
                )}
              </Stack>
            ))}
            <Button startIcon={<Add />} size="small" onClick={addExecutor}>
              Add Executor
            </Button>
            <Typography variant="h6">
              Settlement deadline (optional, {DAML_TIMESTAMP_FORMAT})
            </Typography>
            <TextField
              id="create-allocation-settlement-deadline"
              placeholder={DAML_TIMESTAMP_FORMAT}
              value={allocation.settlement.settlement_deadline || ''}
              error={
                !!allocation.settlement.settlement_deadline &&
                !isValidDamlTimestamp(allocation.settlement.settlement_deadline)
              }
              onChange={event =>
                setAllocation({
                  ...allocation,
                  settlement: {
                    ...allocation.settlement,
                    settlement_deadline: event.target.value || undefined,
                  },
                })
              }
            />

            <Divider />
            <Typography variant="h5">Amulet Transfer Legs</Typography>
            {allocation.transfer_legs.map((leg, idx) => (
              <Card key={idx} variant="outlined" sx={{ p: 2 }}>
                <Stack spacing={1}>
                  <Stack direction="row" justifyContent="space-between" alignItems="center">
                    <Typography variant="h6">Amulet Transfer Leg {idx + 1}</Typography>
                    {allocation.transfer_legs.length > 1 && (
                      <Button
                        startIcon={<Remove />}
                        color="error"
                        size="small"
                        onClick={() => removeLeg(idx)}
                      >
                        Remove
                      </Button>
                    )}
                  </Stack>
                  <Typography variant="body2">Transfer Leg ID</Typography>
                  <TextField
                    id={`create-allocation-transfer-leg-id-${idx}`}
                    value={leg.transfer_leg_id}
                    error={!leg.transfer_leg_id}
                    onChange={event => updateLeg(idx, { transfer_leg_id: event.target.value })}
                  />
                  <Typography variant="body2">Sender</Typography>
                  <BftAnsField
                    name={`Sender ${idx}`}
                    label="Sender"
                    aria-label={`Sender ${idx}`}
                    id={`create-allocation-transfer-leg-sender-${idx}`}
                    onPartyChanged={party => updateLeg(idx, { sender: party })}
                  />
                  <Typography variant="body2">Receiver</Typography>
                  <BftAnsField
                    name={`Receiver ${idx}`}
                    label="Receiver"
                    aria-label={`Receiver ${idx}`}
                    id={`create-allocation-transfer-leg-receiver-${idx}`}
                    onPartyChanged={party => updateLeg(idx, { receiver: party })}
                  />
                  <AmountInput
                    idPrefix={`create-allocation-${idx}`}
                    ccAmountText={leg.amount}
                    setCcAmountText={amount => updateLeg(idx, { amount })}
                  />
                </Stack>
              </Card>
            ))}
            <Button id="add-transfer-leg" startIcon={<Add />} onClick={addLeg}>
              Add Transfer Leg
            </Button>
            <FormControlLabel
              control={
                <Checkbox
                  id="create-allocation-committed"
                  checked={allocation.committed}
                  onChange={event =>
                    setAllocation({
                      ...allocation,
                      committed: event.target.checked,
                    })
                  }
                />
              }
              label="Committed"
            />
            <Typography variant="body2">Metadata</Typography>
            <TextMapEditor
              meta={allocation.meta}
              setTextMap={meta =>
                setAllocation({
                  ...allocation,
                  meta,
                })
              }
              idPrefix="create-allocation"
            />

            <Divider />

            <Typography variant="h5">Iterated Settlement Options</Typography>
            <FormControlLabel
              control={
                <Checkbox
                  id="create-allocation-allow-iterated-settlement"
                  checked={allocation.allow_iterated_settlement}
                  onChange={event =>
                    setAllocation({
                      ...allocation,
                      allow_iterated_settlement: event.target.checked,
                    })
                  }
                />
              }
              label="Allow iterated settlement"
            />
            {allocation.allow_iterated_settlement && (
              <>
                <Typography variant="body2">Funding amount (Amulet)</Typography>
                <TextField
                  id="create-allocation-next-iteration-funding-amount"
                  value={allocation.next_iteration_funding_amount}
                  error={
                    !isValidNextIterationFundingAmount(allocation.next_iteration_funding_amount)
                  }
                  onChange={event =>
                    setAllocation({
                      ...allocation,
                      next_iteration_funding_amount: event.target.value,
                    })
                  }
                />
              </>
            )}

            <DisableConditionally
              conditions={[
                {
                  disabled: createAllocationMutation.isPending,
                  reason: 'Creating allocation...',
                },
                {
                  disabled: !validated,
                  reason: 'Form is not valid, please check the fields.',
                },
              ]}
            >
              <Button
                id="create-allocation-submit-button"
                variant="pill"
                fullWidth
                size="large"
                onClick={() => createAllocationMutation.mutate()}
              >
                Send
              </Button>
            </DisableConditionally>
          </Stack>
        </CardContent>
      </Card>
    </Stack>
  );
};

export default CreateAllocation;

interface PartialTransferLeg {
  transfer_leg_id: string;
  sender: string;
  receiver: string;
  amount: string;
}

interface PartialAllocateAmuletV2Request {
  settlement: {
    executors: string[];
    settlement_ref: AllocateAmuletRequestSettlementSettlementRef;
    settlement_deadline?: string;
  };
  transfer_legs: PartialTransferLeg[];
  committed: boolean;
  meta: TextMap;
  allow_iterated_settlement: boolean;
  next_iteration_funding_amount: string;
}

function emptyTransferLeg(): PartialTransferLeg {
  return { transfer_leg_id: '', sender: '', receiver: '', amount: '1' };
}

function emptyForm(): PartialAllocateAmuletV2Request {
  return {
    settlement: {
      executors: [''],
      settlement_deadline: undefined,
      settlement_ref: { id: '', cid: undefined },
    },
    transfer_legs: [],
    committed: false,
    meta: {},
    allow_iterated_settlement: false,
    next_iteration_funding_amount: '0',
  };
}

function isValidNextIterationFundingAmount(amount: string): boolean {
  const value = amount.trim();
  if (!value) {
    return false;
  }
  const amountNumber = new BigNumber(value);
  return amountNumber.isFinite() && amountNumber.gte(0);
}

function validatedForm(
  userParty: string,
  partial: PartialAllocateAmuletV2Request
): AllocateAmuletV2Request | null {
  if (
    !partial.settlement.executors.length ||
    partial.settlement.executors.some(e => !e) ||
    !partial.settlement.settlement_ref?.id ||
    (partial.settlement.settlement_deadline &&
      !isValidDamlTimestamp(partial.settlement.settlement_deadline))
  ) {
    return null;
  }
  const validLegSides: TransferLegSide[] = [];
  for (const leg of partial.transfer_legs) {
    if (!leg.transfer_leg_id || !leg.sender || !leg.receiver || !leg.amount) return null;
    let side: 'SENDERSIDE' | 'RECEIVERSIDE';
    let otherside: string;
    if (userParty === leg.sender) {
      side = 'SENDERSIDE';
      otherside = leg.receiver;
    } else if (userParty === leg.receiver) {
      side = 'RECEIVERSIDE';
      otherside = leg.sender;
    } else {
      return null;
    }
    validLegSides.push({
      transfer_leg_id: leg.transfer_leg_id,
      side,
      otherside,
      meta: {},
      amount: leg.amount,
    });
  }
  if (
    partial.allow_iterated_settlement &&
    !isValidNextIterationFundingAmount(partial.next_iteration_funding_amount)
  ) {
    return null;
  }

  // You must specify either transfer legs, or iterated settlement funding.
  if (validLegSides.length === 0 && !partial.allow_iterated_settlement) return null;

  return {
    settlement: {
      executors: partial.settlement.executors,
      settlement_deadline: partial.settlement.settlement_deadline
        ? damlTimestampToOpenApiTimestamp(partial.settlement.settlement_deadline)
        : undefined,
      settlement_ref: {
        id: partial.settlement.settlement_ref.id,
        cid: partial.settlement.settlement_ref.cid,
      },
    },
    transfer_leg_sides: validLegSides,
    committed: partial.committed,
    meta: partial.meta,
    next_iteration_funding: partial.allow_iterated_settlement
      ? { ['Amulet']: partial.next_iteration_funding_amount.trim() }
      : undefined,
  };
}

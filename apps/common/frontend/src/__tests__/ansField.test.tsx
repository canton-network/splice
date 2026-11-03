// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { fireEvent, render, screen } from '@testing-library/react';
import { UseQueryResult } from '@tanstack/react-query';
import { AnsEntry } from '@canton-network/scan-openapi';
import React from 'react';
import { describe, expect, test, vi } from 'vitest';

import { BaseAnsField, UserInput } from '../components/AnsField';

const bobEntry: AnsEntry = {
  name: 'bob.unverified.cns',
  user: 'bob::1220bb',
  url: '',
  description: '',
  contract_id: 'cid',
  expires_at: new Date(0),
};

function queryResult<T>(data: T | undefined): UseQueryResult<T> {
  return { data } as UseQueryResult<T>;
}

const Harness: React.FC<{
  onPartyChanged: (party: string) => void;
  lookup: (name: string) => AnsEntry | null | undefined;
}> = ({ onPartyChanged, lookup }) => {
  const [userInput, setUserInput] = React.useState<UserInput>({ type: 'typed', value: '' });
  const [party, setParty] = React.useState('');
  // react-query keeps the data of an unchanged query referentially stable.
  const ansEntries = React.useMemo(() => queryResult<AnsEntry[]>([bobEntry]), []);
  const ansEntry = React.useMemo(
    () => queryResult(userInput.value === '' ? undefined : lookup(userInput.value)),
    [userInput.value, lookup]
  );
  return (
    <>
      <BaseAnsField
        id="party-field"
        userInput={userInput}
        updateUserInput={setUserInput}
        ansEntries={ansEntries}
        ansEntry={ansEntry}
        onPartyChanged={p => {
          setParty(p);
          onPartyChanged(p);
        }}
      />
      <p data-testid="party">{party}</p>
    </>
  );
};

function typeSynchronously(element: HTMLInputElement, text: string) {
  const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
  const actEnvironment = (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean })
    .IS_REACT_ACT_ENVIRONMENT;
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = false;
  try {
    for (let i = 1; i <= text.length; i++) {
      setValue.call(element, text.slice(0, i));
      element.dispatchEvent(new Event('input', { bubbles: true }));
    }
  } finally {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT =
      actEnvironment;
  }
}

describe('BaseAnsField', () => {
  const input = () => screen.getByRole('combobox') as HTMLInputElement;

  test('resolves a typed party id without a render loop, even for many fast keystrokes', () => {
    const onPartyChanged = vi.fn();
    window.splice_config = { spliceInstanceNames: { nameServiceNameAcronym: 'CNS' } } as never;
    render(<Harness onPartyChanged={onPartyChanged} lookup={() => null} />);

    const partyId = 'bob__wallet__user::1220abcdef0123456789';

    const reportedErrors: unknown[] = [];
    const consoleError = console.error;
    console.error = (...args: unknown[]) => {
      reportedErrors.push(...args.filter(arg => arg instanceof Error));
      consoleError(...args);
    };
    try {
      typeSynchronously(input(), partyId);
    } finally {
      console.error = consoleError;
    }

    expect(reportedErrors).toEqual([]);

    expect(input().getAttribute('data-resolved-party-id')).toBe(partyId);
    expect(screen.getByTestId('party').textContent).toBe(partyId);
    expect(onPartyChanged).toHaveBeenLastCalledWith(partyId);
  });

  test('resolves a typed ANS name to the party of its entry', () => {
    const onPartyChanged = vi.fn();
    window.splice_config = { spliceInstanceNames: { nameServiceNameAcronym: 'CNS' } } as never;
    render(
      <Harness
        onPartyChanged={onPartyChanged}
        lookup={name => (name === bobEntry.name ? bobEntry : null)}
      />
    );

    fireEvent.input(input(), { target: { value: 'bob.unverified' } });
    expect(input().getAttribute('data-resolved-party-id')).toBe('bob.unverified');
    expect(onPartyChanged).toHaveBeenLastCalledWith('bob.unverified');

    fireEvent.input(input(), { target: { value: bobEntry.name } });
    expect(input().getAttribute('data-resolved-party-id')).toBe(bobEntry.user);
    expect(screen.getByTestId('party').textContent).toBe(bobEntry.user);
    expect(onPartyChanged).toHaveBeenLastCalledWith(bobEntry.user);
  });
});

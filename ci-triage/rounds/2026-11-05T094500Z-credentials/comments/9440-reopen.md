Happened again in #11188 (app-upgrade, main, Canton 3.7.1), now with the extra logging from this issue.

Same sequence as here: the sv1 app replaces the participant's sequencer connection, the participant disconnects, and the reconnect hangs after `Ensured ... persistent state`. The teardown's own ReconnectSynchronizer then times out after 108 s.

```
15:01:17.704 Replacing configuration for (Synchronizer 'global', global-domain::1220c8564feb...::37-0) with SynchronizerConnection...
15:01:18.327 Disconnected from Synchronizer 'global'
15:01:19.257 Synchronizer 'global' maps to global-domain::1220c8564feb...::37-0 after handshake
15:01:20.047 Ensured global-domain::1220c8564feb...::37-0 persistent state
15:01:28.650 connect to Synchronizer 'global' has not completed after 10096 milliseconds
```

Nothing else is logged for that trace (5d6bebee9883273a1ae1022d678ef388) afterwards. The successful connect a minute earlier on the same participant logs the next step 67 ms after `Ensured`:

```
15:00:09.062 Ensured global-domain::1220c8564feb...::37-0 persistent state
15:00:09.129 Building sequencer client factory for global-domain::1220c8564feb...::37-0
```

So the hang is in `SynchronizerRegistryHelpers.getSynchronizerHandle` before `Building sequencer client factory`: `copyTopologyStateFromLocalPredecessorIfNeeded`, the trusted-synchronizer initialization, or the topology client and crypto setup.

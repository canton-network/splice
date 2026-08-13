val appSynchronizerId = bootstrap.synchronizer(
  synchronizerName = "app-synchronizer",
  sequencers = Seq(`app-sequencer`),
  mediators = Seq(`app-mediator`),
  synchronizerOwners = Seq(`app-sequencer`),
  synchronizerThreshold = 1,
  staticSynchronizerParameters = StaticSynchronizerParameters.defaultsWithoutKMS(ProtocolVersion.latest),
)

`app-provider`.synchronizers.connect_local(`app-sequencer`, "app-synchronizer")
`app-user`.synchronizers.connect_local(`app-sequencer`, "app-synchronizer")

utils.retry_until_true {
  `app-provider`.synchronizers.active("app-synchronizer") &&
    `app-user`.synchronizers.active("app-synchronizer")
}

// Enable the multi-synchronizer topology feature flag on every synchronizer each
// participant is connected to
val multiSyncParticipants = Seq(`app-provider`, `app-user`)

// Wait until the participants are also connected to the global synchronizer, otherwise
// we would only enable the flag on the app-synchronizer.
utils.retry_until_true {
  multiSyncParticipants.forall(
    _.synchronizers.list_connected().exists(_.synchronizerId != appSynchronizerId.logical)
  )
}

val multiSyncFeatureFlag =
  SynchronizerTrustCertificate.ParticipantTopologyFeatureFlag.EnableMultiSynchronizer
multiSyncParticipants.foreach { participant =>
  participant.synchronizers.list_connected().map(_.synchronizerId).distinct.foreach {
    synchronizerId =>
      val existingFlags = participant.topology.synchronizer_trust_certificates
        .list(
          store = Some(TopologyStoreId.Synchronizer(synchronizerId)),
          filterUid = participant.id.filterString,
        )
        .map(_.item.featureFlags)
        .flatten
        .distinct
      if (!existingFlags.contains(multiSyncFeatureFlag)) {
        participant.topology.synchronizer_trust_certificates
          .propose(
            participant.id,
            synchronizerId,
            featureFlags = existingFlags :+ multiSyncFeatureFlag,
          )
      }
  }
}

// Ensure the flag became effective on all synchronizers before the console exits.
utils.retry_until_true {
  multiSyncParticipants.forall { participant =>
    participant.synchronizers.list_connected().map(_.synchronizerId).distinct.forall {
      synchronizerId =>
        participant.topology.synchronizer_trust_certificates
          .list(
            store = Some(TopologyStoreId.Synchronizer(synchronizerId)),
            filterUid = participant.id.filterString,
          )
          .exists(_.item.featureFlags.contains(multiSyncFeatureFlag))
    }
  }
}

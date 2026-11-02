import com.digitalasset.canton.console.{InstanceReference, SequencerReference}
import com.digitalasset.canton.protocol.DynamicSynchronizerParameters

val sequencers = Seq(sequencer1, sequencer2, sequencer3, sequencer4)
val mediators = Seq(mediator1, mediator2, mediator3, mediator4)
val nodes = sequencers ++ mediators
// We have a threshold of three, so signing the bootstrapping transactions only with the first three sequencers.
val signingSequencers = sequencers.take(3)

def getOrGenerateSigningKey(node: InstanceReference, usage: SigningKeyUsage): SigningPublicKey = {
  val name = node.name + s"-${usage.identifier}"
  node.keys.secret
    .list(filterName = name)
    .filter(_.publicKeyWithName.name.map(_.unwrap).contains(name))
    .map(_.publicKeyWithName.publicKey)
    .collectFirst { case key: SigningPublicKey => key } match {
    case Some(key) =>
      println(s"Key $name already exists, skipping")
      key
    case None =>
      println(s"Generating key $name...")
      node.keys.secret.generate_signing_key(name = name, usage = Set(usage))
  }
}

def initManually(node: InstanceReference): Unit =
  if (node.health.initialized()) {
    println("Node " + node.name + " is already initialized, skipping")
  } else {
    val namespaceKey = getOrGenerateSigningKey(node, SigningKeyUsage.Namespace)
    val sequencerAuthKey = getOrGenerateSigningKey(node, SigningKeyUsage.SequencerAuthentication)
    val signingKey = getOrGenerateSigningKey(node, SigningKeyUsage.Protocol)
    val namespace = Namespace(namespaceKey.id)

    if (node.health.has_identity()) {
      println("Node " + node.name + " already has an identity, skipping")
    } else {
      println("Bootstrapping node " + node.name + "...")
      node.topology.init_id_from_uid(
        UniqueIdentifier.tryCreate(node.name, namespace)
      )
    }

    node.health.wait_for_ready_for_initialization()

    if (
      node.topology.namespace_delegations
        .list(store = TopologyStoreId.Authorized, filterNamespace = namespace.toProtoPrimitive)
        .nonEmpty
    ) {
      println("Namespace delegation for node " + node.name + " already exists, skipping")
    } else {
      println("Proposing namespace delegation for node " + node.name + "...")
      node.topology.namespace_delegations.propose_delegation(
        namespace,
        namespaceKey,
        CanSignAllMappings,
      )
    }

    if (
      node.topology.owner_to_key_mappings
        .list(
          store = Some(TopologyStoreId.Authorized),
          filterKeyOwnerUid = node.id.uid.toProtoPrimitive,
        )
        .nonEmpty
    ) {
      println("Owner to key mapping for node " + node.name + " already exists, skipping")
    } else {
      println("Proposing owner to key mapping for node " + node.name + "...")
      node.topology.owner_to_key_mappings.propose(
        member = node.id.member,
        keys = NonEmpty(Seq, sequencerAuthKey, signingKey),
        signedBy =
          Seq(namespaceKey.fingerprint, sequencerAuthKey.fingerprint, signingKey.fingerprint),
      )
    }
  }

def fingerprint(node: InstanceReference) = node.id.uid.namespace.fingerprint

// Runs a step that requires a proposal from every signing sequencer, skipping it entirely if it
// has already taken effect, and skipping sequencers whose proposal is already pending.
def proposeBySigningSequencers(description: String)(
    isDone: () => Boolean,
    hasProposed: SequencerReference => Boolean,
    propose: SequencerReference => Unit,
): Unit =
  if (isDone()) {
    println(s"$description: already done, skipping")
  } else {
    println(s"$description...")
    signingSequencers.foreach { sequencer =>
      if (hasProposed(sequencer)) println(s"${sequencer.name} has already proposed, skipping")
      else propose(sequencer)
    }
    utils.retry_until_true(isDone())
  }

def hasSequencerThreshold2(
    synchronizerId: SynchronizerId,
    node: InstanceReference,
    proposals: Boolean,
) =
  node.topology.sequencers
    .list(store = Some(TopologyStoreId.Synchronizer(synchronizerId)), proposals = proposals)
    .exists(r =>
      r.item.threshold == PositiveInt.tryCreate(2) &&
        (!proposals || r.context.signedBy.contains(fingerprint(node)))
    )

def hasProposedSynchronizerParameters(
    synchronizerId: SynchronizerId,
    node: InstanceReference,
    predicate: DynamicSynchronizerParameters => Boolean,
) =
  node.topology.synchronizer_parameters
    .list(store = TopologyStoreId.Synchronizer(synchronizerId), proposals = true)
    .exists(r => predicate(r.item) && r.context.signedBy.contains(fingerprint(node)))

nodes.foreach(initManually)

val synchronizerId =
  if (nodes.forall(_.health.initialized())) {
    println("Synchronizer is already bootstrapped, skipping")
    sequencers.head.synchronizer_id
  } else {
    println("Bootstrapping synchronizer...")
    // Only initializes the nodes that are not initialized yet.
    bootstrap
      .synchronizer(
        synchronizerName = "dedicated",
        sequencers = sequencers,
        mediatorsToSequencers = mediators.map {
          _ -> (Seq(ownsequencer), PositiveInt.one, NonNegativeInt.zero)
        }.toMap,
        synchronizerOwners = sequencers,
        synchronizerThreshold = PositiveInt.tryCreate(3),
        staticSynchronizerParameters =
          StaticSynchronizerParameters.defaultsWithoutKMS(ProtocolVersion.latest),
        mediatorRequestAmplification = SubmissionRequestAmplification.NoAmplification,
        mediatorThreshold = PositiveInt.tryCreate(2),
        identityTransactions = None,
      )
      .logical
  }

proposeBySigningSequencers("Setting sequencer threshold to 2")(
  isDone = () => hasSequencerThreshold2(synchronizerId, sequencers.head, proposals = false),
  hasProposed = hasSequencerThreshold2(synchronizerId, _, proposals = true),
  propose = node =>
    node.topology.sequencers.propose(
      synchronizerId,
      threshold = PositiveInt.tryCreate(2),
      active = sequencers.map(_.id),
      signedBy = Some(fingerprint(node)),
      synchronize = None,
    ),
)

proposeBySigningSequencers("Restricting onboarding")(
  isDone = () =>
    sequencers.head.topology.synchronizer_parameters
      .get_dynamic_synchronizer_parameters(synchronizerId)
      .onboardingRestriction == OnboardingRestriction.RestrictedOpen,
  hasProposed = hasProposedSynchronizerParameters(
    synchronizerId,
    _,
    _.onboardingRestriction == OnboardingRestriction.RestrictedOpen,
  ),
  propose = node =>
    node.topology.synchronizer_parameters.propose_update(
      synchronizerId,
      _.update(onboardingRestriction = OnboardingRestriction.RestrictedOpen),
      signedBy = Some(fingerprint(node)),
    ),
)

proposeBySigningSequencers("Turning on traffic management")(
  isDone = () =>
    sequencers.head.topology.synchronizer_parameters
      .get_dynamic_synchronizer_parameters(synchronizerId)
      .trafficControl
      .isDefined,
  hasProposed = hasProposedSynchronizerParameters(synchronizerId, _, _.trafficControl.isDefined),
  propose = node =>
    node.topology.synchronizer_parameters.propose_update(
      synchronizerId,
      _.update(trafficControl =
        Some(
          TrafficControlParameters(
            maxBaseTrafficAmount = NonNegativeLong.zero,
            maxBaseTrafficAccumulationDuration = PositiveFiniteDuration.ofMinutes(
              10
            ), // Doesn't really matter, since the base rate is zero.
            readVsWriteScalingFactor = PositiveInt.tryCreate(200),
            setBalanceRequestSubmissionWindowSize = PositiveFiniteDuration.ofMinutes(5),
            enforceRateLimiting = true,
            baseEventCost = NonNegativeLong.zero,
            freeConfirmationResponses = true,
          )
        )
      ),
      signedBy = Some(fingerprint(node)),
    ),
)

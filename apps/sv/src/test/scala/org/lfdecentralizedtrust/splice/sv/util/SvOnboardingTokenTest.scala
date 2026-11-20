package org.lfdecentralizedtrust.splice.sv.util

import org.lfdecentralizedtrust.splice.util.{Codec, SpliceUtil}
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.topology.SynchronizerId
import org.scalatest.wordspec.AsyncWordSpec

class SvOnboardingTokenTest extends AsyncWordSpec with BaseTest {

  "SV onboarding tokens are built and validated correctly" in {
    // random values for test
    val candidateParty =
      Codec
        .decode(Codec.Party)(
          "svX::122020c99a2f48cd66782404648771eeaa104f108131c0c876a6ed04dd2e4175f27d"
        )
        .value
    val dsoParty =
      Codec
        .decode(Codec.Party)(
          "DSO::122020c99a2f48cd66782404648771eeaa104f108131c0c876a6ed04dd2e4175f27d"
        )
        .value
    val candidateParticipantId =
      Codec
        .decode(Codec.Participant)(
          "PAR::svX::122020c99a2f48cd66782404648771eeaa104f108131c0c876a6ed04dd2e4175f27d"
        )
        .value

    // keys generated using scripts/generate-sv-keys.sh
    val publicKey =
      "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEmeNnFncZa2O0wNLaoq3KNrlF5GpbpF4ZfIXcvqPFxtSMm5rL3sxjf6NY1GnHncrT9MZgfWuU161Y2FM1pEZ1Zg=="
    val privateKey =
      "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgtYbz4yBUZofTNVGjwg+QR6M3Ku1LP7RZJAPfokjDbWWhRANCAASZ42cWdxlrY7TA0tqirco2uUXkalukXhl8hdy+o8XG1IybmsvezGN/o1jUacedytP0xmB9a5TXrVjYUzWkRnVm"

    val token = SvOnboardingToken(
      "SvX",
      publicKey,
      candidateParty,
      candidateParticipantId,
      dsoParty,
    )

    val encodedToken = token.signAndEncode(SvUtil.parsePrivateKey(privateKey).value).value
    val decodedToken = SvOnboardingToken.verifyAndDecode(encodedToken).value

    decodedToken should equal(token)
  }

  "SV onboarding tokens stay within the DsoRules text limit" in {
    // DsoRules_StartSvOnboarding rejects a token longer than config.maxTextLength. The SV name
    // appears in the payload and in both the party and the participant id, so the encoded token
    // grows with it; check that a generously long name still leaves room.
    val fingerprint = "122020c99a2f48cd66782404648771eeaa104f108131c0c876a6ed04dd2e4175f27d"
    val svName = "Some-Quite-Long-Sv-Operator-Name-12345678"
    val candidateParty = Codec.decode(Codec.Party)(s"$svName::$fingerprint").value
    val dsoParty = Codec.decode(Codec.Party)(s"DSO::$fingerprint").value
    val candidateParticipantId =
      Codec.decode(Codec.Participant)(s"PAR::$svName::$fingerprint").value

    // keys generated using scripts/generate-sv-keys.sh
    val publicKey =
      "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEmeNnFncZa2O0wNLaoq3KNrlF5GpbpF4ZfIXcvqPFxtSMm5rL3sxjf6NY1GnHncrT9MZgfWuU161Y2FM1pEZ1Zg=="
    val privateKey =
      "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgtYbz4yBUZofTNVGjwg+QR6M3Ku1LP7RZJAPfokjDbWWhRANCAASZ42cWdxlrY7TA0tqirco2uUXkalukXhl8hdy+o8XG1IybmsvezGN/o1jUacedytP0xmB9a5TXrVjYUzWkRnVm"

    val token = SvOnboardingToken(
      svName,
      publicKey,
      candidateParty,
      candidateParticipantId,
      dsoParty,
    )
    val encodedToken = token.signAndEncode(SvUtil.parsePrivateKey(privateKey).value).value

    val maxTextLength = SpliceUtil
      .defaultDsoRulesConfig(
        numUnclaimedRewardsThreshold = 10,
        numMemberTrafficContractsThreshold = 5,
        maxNumCometBftNodes = 2,
        dummyDomain = SynchronizerId.tryFromString(s"dummy::$fingerprint"),
      )
      .maxTextLength

    encodedToken.length.toLong should be < maxTextLength.longValue
  }
}

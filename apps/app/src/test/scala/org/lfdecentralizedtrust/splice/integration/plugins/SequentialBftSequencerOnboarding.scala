package org.lfdecentralizedtrust.splice.integration.plugins

import com.digitalasset.canton.BaseTest
import org.lfdecentralizedtrust.splice.config.SpliceConfig
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.util.SequentialSvStartUtil

/** Before the apps are started in parallel, starts the SVs one at a time if their BFT sequencers are not all in the
  * ordering topology yet (see [[SequentialSvStartUtil]]). Tests with manual start do this in `startAllSync` and
  * `initDso`.
  */
final class SequentialBftSequencerOnboarding
    extends SpliceEnvironmentSetupPlugin
    with BaseTest
    with SequentialSvStartUtil {

  override def afterEnvironmentCreated(
      config: SpliceConfig,
      env: SpliceTestConsoleEnvironment,
  ): Unit = if (!config.parameters.manualStart) {
    startSvsOneAtATimeIfNeeded(env.fullDsoApps.local)
  }
}

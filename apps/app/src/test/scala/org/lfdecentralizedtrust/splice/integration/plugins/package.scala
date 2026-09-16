package org.lfdecentralizedtrust.splice.integration

import com.digitalasset.canton.integration.BaseEnvironmentSetupPlugin
import org.lfdecentralizedtrust.splice.config.SpliceConfig
import org.lfdecentralizedtrust.splice.environment.SpliceEnvironment

package object plugins {
  type SpliceEnvironmentSetupPlugin = BaseEnvironmentSetupPlugin[SpliceConfig, SpliceEnvironment]
}

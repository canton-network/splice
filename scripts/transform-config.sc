import org.lfdecentralizedtrust.splice.config.{SpliceConfig, ConfigTransforms}
import com.typesafe.config.ConfigValueFactory

import java.nio.file.Paths

object TransformConfig extends App {
  val mode = args(0)
  val inputFileName = Paths.get(args(1))
  val outputFileName = Paths.get(args(2))
  mode match {
    case "useSelfSignedTokensForLedgerApiAuth" =>
      val permissioned = if (args.length > 3) args(3).toBoolean else false
      val inputConfig = SpliceConfig.parseAndLoadOrThrow(Seq(inputFileName.toFile))
      val baseConfig = ConfigTransforms.useSelfSignedTokensForLedgerApiAuth("test")(inputConfig)
      val outputConfig = if (permissioned) {
        val configWithSv1 = baseConfig.withValue(
          "canton.sv-apps.sv1.permissioned-synchronizer",
          ConfigValueFactory.fromAnyRef(true),
        )
        if (configWithSv1.hasPath("canton.sv-apps.sv2")) {
          configWithSv1.withValue(
            "canton.sv-apps.sv2.permissioned-synchronizer",
            ConfigValueFactory.fromAnyRef(true),
          )
        } else {
          configWithSv1
        }
      } else {
        baseConfig
      }
      // Deliberately leaking secrets to file
      SpliceConfig.writeToFile(outputConfig, outputFileName, confidential = false)
    case "integrationTestDefaults" =>
      val testId = args(3)
      val inputConfig = SpliceConfig.parseAndLoadOrThrow(Seq(inputFileName.toFile))
      val outputConfig =
        ConfigTransforms.defaults(Some(testId)).foldLeft(inputConfig)((c, t) => t(c))
      SpliceConfig.writeToFile(outputConfig, outputFileName, confidential = false)
    case _ =>
      println(s"Unknown mode '$mode'")
      sys.exit(-1)
  }
}

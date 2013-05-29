import sbt._
import Keys._
import play.Project._

import de.johoop.jacoco4sbt._
import JacocoPlugin._

object ApplicationBuild extends Build {

  val appName         = "genesis"
  val appVersion      = "1.0-SNAPSHOT"

  lazy val jacoco_settings = Defaults.defaultSettings ++ Seq(jacoco.settings: _*)
  
  val appDependencies = Seq(
    // Add your project dependencies here,
    jdbc,
    anorm,
    "postgresql" % "postgresql" % "9.1-901.jdbc4",
    "org.seleniumhq.selenium" % "selenium-java" % "2.32.0"
  )

  val main = play.Project(appName, appVersion, appDependencies, settings = jacoco_settings).settings(
    // Add your own project settings here
    parallelExecution     in jacoco.Config := false,
    jacoco.reportFormats  in jacoco.Config := Seq(XMLReport("utf-8"), HTMLReport("utf-8")),
    jacoco.excludes 		  in jacoco.Config := Seq("controllers**","views**","scalation**","Routes*")
    )

}

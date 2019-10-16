import java.io.File

import sbt.plugins.JvmPlugin
import sbt.{AutoPlugin, Def, Plugins, inTask, taskKey}
import sbtdocker.DockerPlugin
import sbtdocker.DockerPlugin.autoImport._

object crdexExtensionDockerPlugin extends AutoPlugin {

  object autoImport extends crdexExtensionDockerKeys
  import autoImport._

  override def requires: Plugins = JvmPlugin && DockerPlugin

  override def projectSettings: Seq[Def.Setting[_]] =
    inTask(docker)(
      Seq(
        additionalFiles := Seq.empty,
        exposedPorts := Set.empty,
        baseImage := "com.crdexplatform/node-it:latest",
        dockerfile := {
          new Dockerfile {
            from(baseImage.value)
            add(additionalFiles.value, "/opt/crdex/")
            expose(exposedPorts.value.toSeq: _*)
          }
        },
        buildOptions := BuildOptions(removeIntermediateContainers = BuildOptions.Remove.OnSuccess)
      ))
}

trait crdexExtensionDockerKeys {
  val additionalFiles    = taskKey[Seq[File]]("Additional files to copy to /opt/crdex")
  val exposedPorts       = taskKey[Set[Int]]("Exposed ports")
  val buildNodeContainer = taskKey[Unit]("Builds a NODE container")
  val baseImage          = taskKey[String]("A base image for this container")
}

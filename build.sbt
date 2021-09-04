ThisBuild / version      := "0.0.0"
ThisBuild / scalaVersion := "2.13.6"
ThisBuild / organization := "zone.slice"

lazy val scoop = (project in file("."))
  .enablePlugins(NativeImagePlugin)
  .settings(
    name := "scoop",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core"           % "2.6.1",
      "org.typelevel" %% "cats-effect"         % "3.2.5",
      "org.http4s"    %% "http4s-blaze-client" % "0.23.3",
      "co.fs2"        %% "fs2-core"            % "3.1.1",
      "co.fs2"        %% "fs2-io"              % "3.1.1",
      "com.beachape"  %% "enumeratum"          % "1.7.0",
      "com.monovore"  %% "decline"             % "2.1.0",
      "com.monovore"  %% "decline-effect"      % "2.1.0",
      "com.monovore"  %% "decline-enumeratum"  % "2.0.0",
      "org.slf4j"      % "slf4j-nop"           % "1.7.32",
    ),
    Compile / mainClass := Some("zone.slice.scoop.ScoopCLI"),
  )

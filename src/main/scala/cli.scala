package zone.slice.scoop

import cats.syntax.all._
import cats.effect.{Async, IO, ExitCode, Resource}
import cats.effect.std.Console

import fs2.io.file.Files

import org.http4s.blaze.client.BlazeClientBuilder

import com.monovore.decline._
import com.monovore.decline.effect._
import com.monovore.decline.enumeratum._

object ScoopCLI
    extends CommandIOApp(
      name = "scoop",
      header = "discord frontend client downloader",
    ) {
  def main: Opts[IO[ExitCode]] =
    (branch, maxQueueSize, maxDownloaders).mapN {
      case (branch, maxQueueSize, maxDownloaders) =>
        import scala.concurrent.ExecutionContext.{global => globalEC}

        (for {
          client <- BlazeClientBuilder[IO](globalEC).resource
          scooper <- Resource.eval(
            Scooper[IO](
              maxQueueSize = maxQueueSize,
              maxDownloaders = maxDownloaders,
            )(
              Async[IO],
              Console[IO],
              client,
              Files[IO],
            ),
          )
          _ <- Resource.eval(scooper.scoop(branch))
        } yield ()).use_.as(ExitCode.Success)
    }

  val branch =
    Opts
      .option[Branch](
        "branch",
        short = "b",
        metavar = "discord_branch",
        help = "The Discord branch to download the client from.",
      )
      .withDefault(Branch.Stable)
  val maxQueueSize =
    Opts
      .option[Int]("queue-size", help = "The size of the asset queue.")
      .withDefault(128)
  val maxDownloaders =
    Opts
      .option[Int](
        "downloaders",
        help = "The number of downloaders to run concurrently.",
      )
      .withDefault(32)
}

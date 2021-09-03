import $ivy.`org.typelevel::cats-core:2.6.1`, cats._, cats.syntax.all._
import $ivy.`org.typelevel::cats-effect:3.2.5`, cats.effect._, std._
import $ivy.`org.http4s::http4s-blaze-client:0.23.3`
import $ivy.`co.fs2::fs2-core:3.1.1`, fs2._, concurrent._
import $ivy.`co.fs2::fs2-io:3.1.1`, fs2.io.file.{Files, Flag, Flags}
import $ivy.`org.slf4j:slf4j-nop:1.7.32`
import fs2.io.file.Path
import org.http4s.Uri
import org.http4s.Request
import org.http4s.client.Client
import org.http4s.blaze.client.BlazeClientBuilder
import scala.util.matching._

def basePageUrl(branch: String): Option[Uri] =
  (branch match {
    case "stable"         => "".some
    case "canary" | "ptb" => (branch + ".").some
    case _                => none
  }).map { subdomain =>
    Uri.unsafeFromString(s"https://${subdomain}discord.com/channels/@me")
  }

def assetUri(name: String): Uri =
  Uri.unsafeFromString(s"https://discord.com/assets/$name")

object R {
  val assetHash =
    """([.a-fA-F0-9]{20,32})(?:\.worker)?\.(\w{2,4})""".r.unanchored
  val chunkHash = """(\d{1,}):"([a-fA-F0-9]+)"""".r.unanchored
}

case class Status(
    discovered: Int = 0,
    fetched: Int = 0,
    skipped: Int = 0,
    bytesDownloaded: Long = 0
)

object Status {
  implicit val showStatus: Show[Status] = Show.show { s =>
    val percentage: Double =
      ((s.fetched.toDouble + s.skipped.toDouble) / s.discovered.toDouble) * 100
    val percentageS = "%.2f".format(percentage)
    val processed = s.fetched + s.skipped
    val dled = "%.4f".format(s.bytesDownloaded.toDouble / 1000000d)
    s"""|[$percentageS%] ${s.fetched} fetched +
        | ${s.skipped} skipped = ${processed}/${s.discovered}
        | (${dled} MB dl'ed)""".stripMargin
      .replaceAll("\n", "")
  }
}

class Scooper[F[_]](assetQ: Queue[F, Option[String]], status: Ref[F, Status])(
    maxDownloaders: Int
)(implicit
    F: Async[F],
    C: Console[F],
    H: Client[F],
    FI: Files[F]
) {
  def addToCounter(
      discovered: Int = 0,
      fetched: Int = 0,
      skipped: Int = 0,
      bytesDownloaded: Long = 0
  ): F[Unit] =
    status.update { st =>
      st.copy(
        discovered = st.discovered + discovered,
        fetched = st.fetched + fetched,
        skipped = st.skipped + skipped,
        bytesDownloaded = st.bytesDownloaded + bytesDownloaded
      )
    }

  def showStatus: F[Unit] =
    status.get.flatMap(s => C.print(s.show + '\r'))

  def publishAssetNames(matches: Vector[String]): F[Unit] =
    addToCounter(discovered = matches.length) *> matches
      .map(n => assetQ.offer(n.some))
      .sequence_

  def findAllAssetNames(text: String): Vector[Regex.Match] =
    R.assetHash
      .findAllMatchIn(text)
      .toVector

  def scoop(branch: String): F[Unit] =
    for {
      basePageUrl <- basePageUrl(branch).liftTo[F](
        new RuntimeException("invalid branch. values: stable, canary, ptb")
      )
      _ <- C.println(s"downloading entire client from $basePageUrl...")
      outputPath = Path("./scoop-output/")
      _ <- FI
        .isDirectory(outputPath)
        .ifM(F.unit, FI.createDirectory(outputPath))
      weDoTheDumping =
        Stream
          .fromQueueNoneTerminated(assetQ)
          .evalTap(_ => showStatus)
          .evalFilter { name =>
            FI.exists(outputPath / name)
              .flatTap(addToCounter(skipped = 1).whenA(_))
              .map(!_)
          }
          .evalTap(_ => addToCounter(fetched = 1))
          // .debug(name => s"fetch: $name")
          .parEvalMapUnordered(maxDownloaders) { name =>
            H.run(Request[F](uri = assetUri(name))).use { resp =>
              resp.body
                .through[F, Long] { byteStream =>
                  val writeCursor = FI.writeCursor(
                    outputPath / name,
                    Flags(Flag.Create, Flag.Write)
                  )
                  Stream
                    .resource(writeCursor)
                    .flatMap(
                      _.writeAll(byteStream)
                        .flatMap(cursor => Pull.output1(cursor.offset))
                        .void
                        .stream
                    )
                }
                .evalTap(offset => addToCounter(bytesDownloaded = offset))
                .compile
                .drain
            }
          }
      weDoTheScooping = for {
        basePageHtml <- H.expect[String](basePageUrl)

        hashes = findAllAssetNames(basePageHtml)
        // download all base page assets (we'll just refetch O_O)
        _ <- publishAssetNames(hashes.map(_.matched))

        // specialized scooping behavior for scripts
        // in order: (0) chunk loader, (1) classes, (2) vendor, (3) entrypoint
        scripts = hashes.map(_.matched).filter(_.endsWith("js"))
        // make sure our invariants hold
        expectedScriptTags = 4
        _ <- F
          .raiseError(
            new RuntimeException(
              """|base page does not have the expected amount of <script>s,
                 | scoop will have to be updated to account for this!""".stripMargin
            )
          )
          .whenA(scripts.length != 4)

        Vector(chunkLoaderName, classesName, vendorName, entrypointName) =
          scripts

        // specialized: download chunks from chunk loader
        chunkLoaderText <- H.expect[String](assetUri(chunkLoaderName))
        // _ <- C.println("*** specialized: finding chunks")
        chunkMatches = R.chunkHash.findAllMatchIn(chunkLoaderText).toVector
        _ <- publishAssetNames(chunkMatches.map(_.group(2) + ".js"))

        // specialiezd: find assets from entrypoint (the bulk of 'em)
        entrypointText <- H.expect[String](assetUri(entrypointName))
        assetNames = findAllAssetNames(entrypointText)
        _ <- publishAssetNames(assetNames.map(_.matched))

        // close the queue, terminating the dumping stream and ending the
        // program
        _ <- assetQ.offer(none)
      } yield ()
      _ <- Stream.exec(weDoTheScooping).merge(weDoTheDumping).compile.drain
      _ <- showStatus
      _ <- C.println("")
    } yield ()
}

object Scooper {
  def apply[F[_]: Async: Console: Client: Files](
      maxQueueSize: Int,
      maxDownloaders: Int
  ): F[Scooper[F]] = for {
    assetT <- Queue.bounded[F, Option[String]](maxQueueSize)
    statusRef <- Ref.of(Status())
  } yield new Scooper(assetT, statusRef)(
    maxDownloaders = maxDownloaders
  )
}

import scala.concurrent.ExecutionContext.{global => globalEC}
import cats.effect.unsafe.implicits.global
@main
def main(
    branch: String,
    maxQueueSize: Int = 128,
    maxDownloaders: Int = 32
): Unit = (for {
  client <- BlazeClientBuilder[IO](globalEC).resource
  scooper <- Resource.eval(
    Scooper[IO](maxQueueSize = maxQueueSize, maxDownloaders = maxDownloaders)(
      Async[IO],
      Console[IO],
      client,
      Files[IO]
    )
  )
  _ <- Resource.eval(scooper.scoop(branch))
} yield ()).use_.unsafeRunSync()

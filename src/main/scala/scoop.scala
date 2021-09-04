package zone.slice.scoop

import cats._
import cats.syntax.all._
import cats.effect._
import cats.effect.std._

import fs2._
import fs2.concurrent._
import fs2.io.file._

import org.http4s.{Uri, Request}
import org.http4s.client.Client
import org.http4s.blaze.client.BlazeClientBuilder

import scala.util.matching._

object R {
  val assetHash =
    """([.a-fA-F0-9]{20,32})(?:\.worker)?\.(\w{2,4})""".r.unanchored
  val chunkHash = """(\d{1,}):"([a-fA-F0-9]+)"""".r.unanchored
}

case class Status(
    discovered: Int = 0,
    fetched: Int = 0,
    skipped: Int = 0,
    bytesDownloaded: Long = 0,
)

object Status {
  implicit val showStatus: Show[Status] = Show.show { s =>
    val percentage: Double =
      ((s.fetched.toDouble + s.skipped.toDouble) / s.discovered.toDouble) * 100
    val percentageS = "%.2f".format(percentage)
    val processed   = s.fetched + s.skipped
    val dled        = "%.4f".format(s.bytesDownloaded.toDouble / 1000000d)
    s"""|[$percentageS%] ${s.fetched} fetched +
        | ${s.skipped} skipped = ${processed}/${s.discovered}
        | (${dled} MB dl'ed)""".stripMargin
      .replaceAll("\n", "")
  }
}

class Scooper[F[_]](assetQ: Queue[F, Option[Asset]], status: Ref[F, Status])(
    maxDownloaders: Int,
)(implicit
    F: Async[F],
    C: Console[F],
    H: Client[F],
    FI: Files[F],
) {
  def addToCounter(
      discovered: Int = 0,
      fetched: Int = 0,
      skipped: Int = 0,
      bytesDownloaded: Long = 0,
  ): F[Unit] =
    status.update { st =>
      st.copy(
        discovered = st.discovered + discovered,
        fetched = st.fetched + fetched,
        skipped = st.skipped + skipped,
        bytesDownloaded = st.bytesDownloaded + bytesDownloaded,
      )
    }

  def showStatus: F[Unit] =
    for {
      status <- status.get
      _ <- C.print(status.show + '\r')
      _ <- F.delay(System.out.flush())
    } yield ()

  def publishAssets(assets: Vector[Asset]): F[Unit] =
    addToCounter(discovered = assets.length) *> assets
      .map(asset => assetQ.offer(asset.some))
      .sequence_

  def findAllAssets(text: String): Vector[Asset] =
    R.assetHash
      .findAllMatchIn(text)
      .map(name => Asset(name = name.matched))
      .toVector

  def scoop(branch: Branch): F[Unit] =
    for {
      _ <- C.println(s"downloading entire client from ${branch.basePageUri}...")
      outputPath = Path("./scoop-output/")
      _ <- FI
        .isDirectory(outputPath)
        .ifM(F.unit, FI.createDirectory(outputPath))
      weDoTheDumping =
        Stream
          .fromQueueNoneTerminated(assetQ)
          .evalTap(_ => showStatus)
          .evalFilter { asset =>
            FI.exists(outputPath / asset.name)
              .flatTap(addToCounter(skipped = 1).whenA(_))
              .map(!_)
          }
          .evalTap(_ => addToCounter(fetched = 1))
          // .debug(name => s"fetch: $name")
          .parEvalMapUnordered(maxDownloaders) { asset =>
            H.run(Request[F](uri = asset.uri)).use { resp =>
              resp.body
                .through[F, Long] { byteStream =>
                  val writeCursor = FI.writeCursor(
                    outputPath / asset.name,
                    Flags(Flag.Create, Flag.Write),
                  )
                  Stream
                    .resource(writeCursor)
                    .flatMap(
                      _.writeAll(byteStream)
                        .flatMap(cursor => Pull.output1(cursor.offset))
                        .void
                        .stream,
                    )
                }
                .evalTap(offset => addToCounter(bytesDownloaded = offset))
                .compile
                .drain
            }
          }
      weDoTheScooping = for {
        basePageHtml <- H.expect[String](branch.basePageUri)

        assets = findAllAssets(basePageHtml)
        // download all base page assets (we'll just refetch O_O)
        _ <- publishAssets(assets)

        // specialized scooping behavior for scripts
        // in order: (0) chunk loader, (1) classes, (2) vendor, (3) entrypoint
        scripts = assets.filter(_.name.endsWith(".js"))
        // make sure our invariants hold
        expectedScriptTags = 4
        _ <- F
          .raiseError(
            new RuntimeException(
              """|base page does not have the expected amount of <script>s,
                 | scoop will have to be updated to account for this!""".stripMargin,
            ),
          )
          .whenA(scripts.length != 4)

        Vector(chunkLoaderJS, classesJS, vendorJS, entrypointJS) =
          scripts

        // specialized: download chunks from chunk loader
        chunkLoaderText <- H.expect[String](chunkLoaderJS.uri)
        // _ <- C.println("*** specialized: finding chunks")
        chunkMatches = R.chunkHash.findAllMatchIn(chunkLoaderText).toVector
        _ <- publishAssets(chunkMatches.map(_.group(2) + ".js").map(Asset(_)))

        // specialiezd: find assets from entrypoint (the bulk of 'em)
        entrypointText <- H.expect[String](entrypointJS.uri)
        _ <- publishAssets(findAllAssets(entrypointText))

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
      maxDownloaders: Int,
  ): F[Scooper[F]] = for {
    assetQ    <- Queue.bounded[F, Option[Asset]](maxQueueSize)
    statusRef <- Ref.of(Status())
  } yield new Scooper(assetQ, statusRef)(
    maxDownloaders = maxDownloaders,
  )
}

package zone.slice.scoop

import org.http4s.Uri

import enumeratum._

sealed trait Branch extends EnumEntry with EnumEntry.Lowercase {
  import Branch._

  def subdomain: Option[String] = this match {
    case Stable => None
    case Ptb => Some("ptb")
    case Canary => Some("canary")
  }

  def basePageUri: Uri =
    Uri.unsafeFromString(
      s"https://${subdomain.map(_ + ".").getOrElse("")}discord.com/channels/@me"
    )
}

object Branch extends Enum[Branch] {
  val values = findValues

  case object Stable extends Branch
  case object Ptb extends Branch
  case object Canary extends Branch
}

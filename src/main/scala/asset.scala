package zone.slice.scoop

import org.http4s.Uri

/** A Discord asset. */
case class Asset(name: String) {
  def uri: Uri = Uri.unsafeFromString(s"https://discord.com/assets/$name")
}

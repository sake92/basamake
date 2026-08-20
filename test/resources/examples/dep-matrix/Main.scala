package demo

import cats.Monad                      // type import (Monad lives in cats-core)
import cats.data.EitherT               // type import, generic class
import cats.implicits._                // object wildcard import
import cats.syntax.all._               // syntax wildcard (extension methods)
import cats.effect.{IO, IOApp}         // class + companion + trait
import cats.effect.IO.{pure => ioPure} // rename import of companion member
import cats.effect.kernel.Ref          // trait in cats-effect-kernel (cross-artifact)
import sttp.client3.basicRequest       // val-in-trait via package object
import org.apache.commons.net.ftp.FTPClient // Java class from commons-net jar

object Main {
  val m: Monad[Option] = Monad[Option]            // (a) type usage, (b) companion apply
  val et: EitherT[Option, String, Int] = EitherT.leftT("x") // (c) type, (d) companion
  val io: IO[Unit] = IO.unit                       // (e) type, (f) companion member
  io.flatMap(_ => io)                              // (g) member call on dep object
  def go[F[_]: Monad](fa: F[Int]): F[Int] = fa.map(_ + 1) // (h) context-bound type, (i) extension method
  val req = basicRequest.get(uri"https://example.com")    // (j) package-object val
  val ftp: FTPClient = new FTPClient()             // (k) Java dep type, (l) ctor
  val r: Ref[IO, Int] = Ref.unsafe[IO, Int](0)     // (m) trait type, (n) companion
}

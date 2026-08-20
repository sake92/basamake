package demo

import cats.{Monad, Alternative}
import cats.effect.{IO, IOApp, ExitCode}
import cats.effect.kernel.{Async, Sync}

trait Combined[F[_]] extends Monad[F] with Alternative[F]   // (a) first parent, (b) second parent
class Runner extends IOApp with Combined[IO]                 // (c) trait parent, (d) mixin parent
object Runner {                                              // (e) parent of an OBJECT
  object Inner extends IOApp { def run(args: List[String]): IO[ExitCode] = IO.pure(ExitCode.Success) } // (f) nested object parent
}
enum Status extends java.lang.Enum[Status]:                 // (g) enum extends (JDK parent)
  case On, Off
final class FullQualified extends cats.effect.IOApp { def run(args: List[String]): IO[ExitCode] = IO.pure(ExitCode.Success) } // (h) fully-qualified parent
given monadList: Monad[List] with                           // (i) given with extends clause
  def pure[A](x: A): List[A] = List(x)
  def flatMap[A, B](fa: List[A])(f: A => List[B]): List[B] = fa.flatMap(f)
  def tailRecM[A, B](a: A)(f: A => List[Either[A, B]]): List[B] = ???

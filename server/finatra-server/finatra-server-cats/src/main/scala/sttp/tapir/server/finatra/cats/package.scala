package sttp.tapir.server.finatra

import _root_.cats.effect.Effect
import com.twitter.inject.Logging
import sttp.monad.MonadError
import sttp.tapir.Endpoint
import sttp.tapir.server.ServerEndpoint

import scala.reflect.ClassTag

package object cats {
  implicit class RichFinatraCatsEndpoint[I, E, O](e: Endpoint[I, E, O, Any]) extends Logging {
    @deprecated("Use FinatraCatsServerInterpreter.toRoute", since = "0.17.1")
    def toRoute[F[_]](logic: I => F[Either[E, O]])(implicit serverOptions: FinatraServerOptions, eff: Effect[F]): FinatraRoute =
      FinatraCatsServerInterpreter.toRoute(e)(logic)

    @deprecated("Use FinatraCatsServerInterpreter.toRouteRecoverErrors", since = "0.17.1")
    def toRouteRecoverErrors[F[_]](logic: I => F[O])(implicit
        eIsThrowable: E <:< Throwable,
        eClassTag: ClassTag[E],
        eff: Effect[F]
    ): FinatraRoute = FinatraCatsServerInterpreter.toRouteRecoverErrors(e)(logic)
  }

  implicit class RichFinatraCatsServerEndpoint[I, E, O, F[_]](e: ServerEndpoint[I, E, O, Any, F]) extends Logging {
    @deprecated("Use FinatraServerInterpreter.toRoute", since = "0.17.1")
    def toRoute(implicit serverOptions: FinatraServerOptions, eff: Effect[F]): FinatraRoute =
      FinatraCatsServerInterpreter.toRoute(e)

    private class CatsMonadError(implicit F: Effect[F]) extends MonadError[F] {
      override def unit[T](t: T): F[T] = F.pure(t)
      override def map[T, T2](fa: F[T])(f: T => T2): F[T2] = F.map(fa)(f)
      override def flatMap[T, T2](fa: F[T])(f: T => F[T2]): F[T2] = F.flatMap(fa)(f)
      override def error[T](t: Throwable): F[T] = F.raiseError(t)
      override protected def handleWrappedError[T](rt: F[T])(h: PartialFunction[Throwable, F[T]]): F[T] = F.recoverWith(rt)(h)
      override def eval[T](t: => T): F[T] = F.delay(t)
      override def suspend[T](t: => F[T]): F[T] = F.suspend(t)
      override def flatten[T](ffa: F[F[T]]): F[T] = F.flatten(ffa)
      override def ensure[T](f: F[T], e: => F[Unit]): F[T] = F.guarantee(f)(e)
    }
  }
}

package sttp.tapir.server.vertx.streams

import _root_.fs2.{Chunk, Stream}
import cats.effect.kernel.Async
import cats.effect.kernel.Resource.ExitCase._
import cats.effect.{Deferred, Ref}
import cats.syntax.all._
import io.vertx.core.Handler
import io.vertx.core.buffer.Buffer
import io.vertx.core.streams.ReadStream
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.vertx.VertxCatsServerOptions
import sttp.tapir.server.vertx.streams.ReadStreamState._

import scala.collection.immutable.{Queue => SQueue}

object fs2 {

  implicit class DeferredOps[F[_]: Async, A](dfd: Deferred[F, A]) extends DeferredLike[F, A] {
    override def complete(a: A): F[Unit] =
      dfd.complete(a).void

    override def get: F[A] =
      dfd.get
  }

  implicit def fs2ReadStreamCompatible[F[_]](implicit opts: VertxCatsServerOptions[F], F: Async[F]): ReadStreamCompatible[Fs2Streams[F]] = {
    new ReadStreamCompatible[Fs2Streams[F]] {
      override val streams: Fs2Streams[F] = Fs2Streams[F]

      override def asReadStream(stream: Stream[F, Byte]): ReadStream[Buffer] = {
        opts.dispatcher.unsafeRunSync {
          for {
            promise <- Deferred[F, Unit]
            state <- Ref.of(StreamState.empty[F](promise))
            _ <- F.start(
              stream.chunks
                .evalMap({ chunk =>
                  val buffer = Buffer.buffer(chunk.toArray)
                  state.get.flatMap {
                    case StreamState(None, handler, _, _) =>
                      F.delay(handler.handle(buffer))
                    case StreamState(Some(promise), _, _, _) =>
                      for {
                        _ <- promise.get
                        // Handler in state may be updated since the moment when we wait
                        // promise so let's get more recent version.
                        updatedState <- state.get
                      } yield updatedState.handler.handle(buffer)
                  }
                })
                .onFinalizeCase({
                  case Succeeded =>
                    state.get.flatMap { state =>
                      F.delay(state.endHandler.handle(null))
                    }
                  case Canceled =>
                    state.get.flatMap { state =>
                      F.delay(state.errorHandler.handle(new Exception("Cancelled!")))
                    }
                  case Errored(cause) =>
                    state.get.flatMap { state =>
                      F.delay(state.errorHandler.handle(cause))
                    }
                })
                .compile
                .drain
            )
          } yield new ReadStream[Buffer] {
            self =>
            override def handler(handler: Handler[Buffer]): ReadStream[Buffer] =
              opts.dispatcher.unsafeRunSync(state.update(_.copy(handler = handler)).as(self))

            override def endHandler(handler: Handler[Void]): ReadStream[Buffer] =
              opts.dispatcher.unsafeRunSync(state.update(_.copy(endHandler = handler)).as(self))

            override def exceptionHandler(handler: Handler[Throwable]): ReadStream[Buffer] =
              opts.dispatcher.unsafeRunSync(state.update(_.copy(errorHandler = handler)).as(self))

            override def pause(): ReadStream[Buffer] =
              opts.dispatcher.unsafeRunSync(for {
                deferred <- Deferred[F, Unit]
                _ <- state.update {
                  case cur @ StreamState(Some(_), _, _, _) =>
                    cur
                  case cur @ StreamState(None, _, _, _) =>
                    cur.copy(paused = Some(deferred))
                }
              } yield self)

            override def resume(): ReadStream[Buffer] =
              opts.dispatcher.unsafeRunSync(for {
                oldState <- state.getAndUpdate(_.copy(paused = None))
                _ <- oldState.paused.fold(Async[F].unit)(_.complete(()))
              } yield self)

            override def fetch(n: Long): ReadStream[Buffer] =
              self
          }
        }
      }

      override def fromReadStream(readStream: ReadStream[Buffer]): Stream[F, Byte] =
        opts.dispatcher.unsafeRunSync {
          for {
            stateRef <- Ref.of(ReadStreamState[F, Chunk[Byte]](Queued(SQueue.empty), Queued(SQueue.empty)))
            stream = Stream.unfoldChunkEval[F, Unit, Byte](()) { _ =>
              for {
                dfd <- Deferred[F, WrappedBuffer[Chunk[Byte]]]
                tuple <- stateRef.modify(_.dequeueBuffer(dfd))
                (mbBuffer, mbAction) = tuple
                _ <- mbAction.traverse(identity)
                wrappedBuffer <- mbBuffer match {
                  case Left(deferred) =>
                    deferred.get
                  case Right(buffer) =>
                    buffer.pure[F]
                }
                result <- wrappedBuffer match {
                  case Right(buffer)     => Some((buffer, ())).pure[F]
                  case Left(None)        => None.pure[F]
                  case Left(Some(cause)) => Async[F].raiseError(cause)
                }
              } yield result
            }

            _ <- F.start(
              Stream
                .unfoldEval[F, Unit, ActivationEvent](())({ _ =>
                  for {
                    dfd <- Deferred[F, WrappedEvent]
                    mbEvent <- stateRef.modify(_.dequeueActivationEvent(dfd))
                    result <- mbEvent match {
                      case Left(deferred) =>
                        deferred.get
                      case Right(event) =>
                        event.pure[F]
                    }
                  } yield result.map((_, ()))
                })
                .evalMap({
                  case Pause  => F.delay(readStream.pause())
                  case Resume => F.delay(readStream.resume())
                })
                .compile
                .drain
            )
          } yield {
            readStream.endHandler { _ =>
              opts.dispatcher.unsafeRunSync(stateRef.modify(_.halt(None)).flatMap(_.sequence_))
            }
            readStream.exceptionHandler { cause =>
              opts.dispatcher.unsafeRunSync(stateRef.modify(_.halt(Some(cause))).flatMap(_.sequence_))
            }
            readStream.handler { buffer =>
              val chunk = Chunk.array(buffer.getBytes)
              val maxSize = opts.maxQueueSizeForReadStream
              opts.dispatcher.unsafeRunSync(stateRef.modify(_.enqueue(chunk, maxSize)).flatMap(_.sequence_))
            }

            stream
          }
        }
    }
  }
}

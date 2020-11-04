package cromwell.engine.io.gcs

import akka.actor.ActorRef
import com.google.api.client.googleapis.json.GoogleJsonError
import com.google.api.client.http.HttpHeaders
import common.assertion.CromwellTimeoutSpec
import cromwell.filesystems.gcs.batch._
import org.scalatest.BeforeAndAfter
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{Failure, Success}

class GcsBatchCommandContextSpec
  extends AnyFlatSpec with CromwellTimeoutSpec with Matchers with Eventually with BeforeAndAfter {
  behavior of "GcsBatchCommandContext"

  it should "handle exceptions in success handlers" in {
    val commandContext = GcsBatchCommandContext[Unit, Void](
      request = ExceptionSpewingGcsBatchIoCommand,
      replyTo = ActorRef.noSender
    )

    commandContext.promise.isCompleted should be(false)

    // Simulate a success response from an underlying IO operation:
    commandContext.callback.onSuccess(null, new HttpHeaders())

    eventually {
      commandContext.promise.isCompleted should be(true)
    }

    commandContext.promise.future.value.get match {
      case Success(oops) => fail(s"Should not have produced a success: $oops")
      case Failure(error) => error.getMessage should be("Error processing IO response in onSuccessCallback: Mapping that value is impossible")
    }
  }

  it should "handle exceptions in failure handlers" in {
    val commandContext = GcsBatchCommandContext[Unit, Void](
      request = ExceptionSpewingGcsBatchIoCommand,
      replyTo = ActorRef.noSender
    )

    commandContext.promise.isCompleted should be(false)

    // Simulate a success response from an underlying IO operation:
    commandContext.callback.onFailure(new GoogleJsonError(), new HttpHeaders())

    eventually {
      commandContext.promise.isCompleted should be(true)
    }

    commandContext.promise.future.value.get match {
      case Success(oops) => fail(s"Should not have produced a success: $oops")
      case Failure(error) => error.getMessage should be("Error processing IO response in onFailureCallback: exception in failure handler...")
    }
  }
}

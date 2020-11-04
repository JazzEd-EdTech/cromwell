package cromwell.filesystems.gcs.batch

import com.google.api.client.googleapis.json.GoogleJsonError
import com.google.api.client.http.HttpHeaders
import com.google.api.services.storage.StorageRequest
import com.google.api.services.storage.model.{Objects, RewriteResponse, StorageObject}
import com.google.cloud.storage.BlobId
import common.util.StringUtil._
import cromwell.core.io._
import cromwell.filesystems.gcs._
import mouse.all._

import scala.collection.JavaConverters._
import scala.util.Try

/**
  * NOTE: the setUserProject commented out code disables uses of requester pays bucket
  * To re-enable, uncomment the code (and possibly adjust if necessary)
  *
  * Io commands with GCS paths and some logic enabling batching of request.
  * @tparam T Return type of the IoCommand
  * @tparam U Return type of the Google response
  */
sealed trait GcsBatchIoCommand[T, U] extends IoCommand[T] {
  /**
    * StorageRequest operation to be executed by this command
    */
  def operation: StorageRequest[U]

  /**
    * Maps the google response of type U to the Cromwell Io response of type T
    */
  protected def mapGoogleResponse(response: U): T

  /**
    * Method called in the success callback of a batched request to decide what to do next.
    * Returns an `Either[T, GcsBatchIoCommand[T, U]]`
    *   Left(value) means the command is complete, and the result can be sent back to the sender.
    *   Right(newCommand) means the command is not complete and needs another request to be executed.
    * Most commands will reply with Left(value).
    */
  def onSuccess(response: U, httpHeaders: HttpHeaders): Either[T, GcsBatchIoCommand[T, U]] = {
    Left(mapGoogleResponse(response))
  }

  /**
    * Override to handle a failure differently and potentially return a successful response.
    */
  def onFailure(googleJsonError: GoogleJsonError, httpHeaders: HttpHeaders): Option[Either[T, GcsBatchIoCommand[T, U]]] = None

  /**
    * Use to signal that the request has failed because the user project was not set and that it can be retried with it.
    */
  def withUserProject: GcsBatchIoCommand[T, U]
}

sealed trait SingleFileGcsBatchIoCommand[T, U] extends GcsBatchIoCommand[T, U] with SingleFileIoCommand[T] {
  override def file: GcsPath
  //noinspection MutatorLikeMethodIsParameterless
  def setUserProject: Boolean
  def userProject: String = setUserProject.option(file.projectId).orNull
}

case class GcsBatchCopyCommand(
                                override val source: GcsPath,
                                sourceBlob: BlobId,
                                override val destination: GcsPath,
                                destinationBlob: BlobId,
                                rewriteToken: Option[String] = None,
                                setUserProject: Boolean = false
                              ) extends IoCopyCommand(source, destination) with GcsBatchIoCommand[Unit, RewriteResponse] {
  override def operation: StorageRequest[RewriteResponse] = {
    val rewriteOperation = source.apiStorage.objects()
      .rewrite(sourceBlob.getBucket, sourceBlob.getName, destinationBlob.getBucket, destinationBlob.getName, null)
      .setUserProject(setUserProject.option(source.projectId).orNull)

    // Set the rewrite token if present
    rewriteToken foreach rewriteOperation.setRewriteToken
    rewriteOperation
  }

  /**
    * Clone this command with the give rewrite token
    */
  def withRewriteToken(rewriteToken: String): GcsBatchCopyCommand = copy(rewriteToken = Option(rewriteToken))

  override def onSuccess(response: RewriteResponse, httpHeaders: HttpHeaders): Either[Unit, GcsBatchCopyCommand] = {
    if (response.getDone) {
      Left(mapGoogleResponse(response))
    } else {
      Right(withRewriteToken(response.getRewriteToken))
    }
  }

  override def mapGoogleResponse(response: RewriteResponse): Unit = ()

  override def withUserProject: GcsBatchCopyCommand = this.copy(setUserProject = true)
}

object GcsBatchCopyCommand {
  def forPaths(source: GcsPath, destination: GcsPath): Try[GcsBatchCopyCommand] = {
    for {
      sourceBlob <- source.objectBlobId
      destinationBlob <- destination.objectBlobId
    } yield GcsBatchCopyCommand(source, sourceBlob, destination, destinationBlob)
  }
}

case class GcsBatchDeleteCommand(
                                  override val file: GcsPath,
                                  blob: BlobId,
                                  override val swallowIOExceptions: Boolean,
                                  setUserProject: Boolean = false
                                ) extends IoDeleteCommand(file, swallowIOExceptions) with SingleFileGcsBatchIoCommand[Unit, Void] {
  def operation: StorageRequest[Void] = {
    file.apiStorage.objects().delete(blob.getBucket, blob.getName).setUserProject(userProject)
  }

  override def mapGoogleResponse(response: Void): Unit = ()

  override def onFailure(googleJsonError: GoogleJsonError,
                         httpHeaders: HttpHeaders,
                        ): Option[Either[Unit, GcsBatchDeleteCommand]] = {
    if (swallowIOExceptions) Option(Left(())) else None
  }

  override def withUserProject: GcsBatchDeleteCommand = this.copy(setUserProject = true)
}

object GcsBatchDeleteCommand {
  def forPath(file: GcsPath, swallowIOExceptions: Boolean): Try[GcsBatchDeleteCommand] = {
    file.objectBlobId.map(GcsBatchDeleteCommand(file, _, swallowIOExceptions))
  }
}

/**
  * Base trait for commands that use the objects.get() operation. (e.g: size, crc32, ...)
  */
sealed trait GcsBatchGetCommand[T] extends SingleFileGcsBatchIoCommand[T, StorageObject] {
  def file: GcsPath
  def blob: BlobId
  override def operation: StorageRequest[StorageObject] = {
    file.apiStorage.objects().get(blob.getBucket, blob.getName).setUserProject(userProject)
  }
}

case class GcsBatchSizeCommand(override val file: GcsPath,
                               override val blob: BlobId,
                               setUserProject: Boolean = false,
                              ) extends IoSizeCommand(file) with GcsBatchGetCommand[Long] {
  override def mapGoogleResponse(response: StorageObject): Long = response.getSize.longValue()

  override def withUserProject: GcsBatchSizeCommand = this.copy(setUserProject = true)
}

object GcsBatchSizeCommand {
  def forPath(file: GcsPath): Try[GcsBatchSizeCommand] = {
    file.objectBlobId.map(GcsBatchSizeCommand(file, _))
  }
}

case class GcsBatchCrc32Command(override val file: GcsPath,
                                override val blob: BlobId,
                                setUserProject: Boolean = false,
                               ) extends IoHashCommand(file) with GcsBatchGetCommand[String] {
  override def mapGoogleResponse(response: StorageObject): String = response.getCrc32c

  override def withUserProject: GcsBatchCrc32Command = this.copy(setUserProject = true)
}

object GcsBatchCrc32Command {
  def forPath(file: GcsPath): Try[GcsBatchCrc32Command] = {
    file.objectBlobId.map(GcsBatchCrc32Command(file, _))
  }
}

case class GcsBatchTouchCommand(override val file: GcsPath,
                                override val blob: BlobId,
                                setUserProject: Boolean = false,
                               ) extends IoTouchCommand(file) with GcsBatchGetCommand[Unit] {
  override def mapGoogleResponse(response: StorageObject): Unit = ()

  override def withUserProject: GcsBatchTouchCommand = this.copy(setUserProject = true)
}

object GcsBatchTouchCommand {
  def forPath(file: GcsPath): Try[GcsBatchTouchCommand] = {
    file.objectBlobId.map(GcsBatchTouchCommand(file, _))
  }
}

/*
 * The only reliable way to know if a path represents a GCS "directory" is to list objects inside of it.
 * Specifically, list objects that have this path as a prefix. Since we don't really care about what's inside here,
 * set max results to 1 to avoid unnecessary payload.
 */
case class GcsBatchIsDirectoryCommand(override val file: GcsPath,
                                      blob: BlobId,
                                      setUserProject: Boolean = false,
                                     )
  extends IoIsDirectoryCommand(file) with SingleFileGcsBatchIoCommand[Boolean, Objects] {
  override def operation: StorageRequest[Objects] = {
    file.apiStorage.objects().list(blob.getBucket).setPrefix(blob.getName.ensureSlashed).setMaxResults(1L).setUserProject(userProject)
  }

  override def mapGoogleResponse(response: Objects): Boolean = {
    // Wrap in an Option because getItems can (always ?) return null if there are no objects
    Option(response.getItems).map(_.asScala).exists(_.nonEmpty)
  }

  override def withUserProject: GcsBatchIsDirectoryCommand = this.copy(setUserProject = true)
}

object GcsBatchIsDirectoryCommand {
  def forPath(file: GcsPath): Try[GcsBatchIsDirectoryCommand] = {
    file.bucketOrObjectBlobId.map(GcsBatchIsDirectoryCommand(file, _))
  }
}

case class GcsBatchExistsCommand(override val file: GcsPath,
                                 override val blob: BlobId,
                                 setUserProject: Boolean = false,
                                ) extends IoExistsCommand(file) with GcsBatchGetCommand[Boolean] {
  override def mapGoogleResponse(response: StorageObject): Boolean = true

  override def onFailure(googleJsonError: GoogleJsonError, httpHeaders: HttpHeaders): Option[Either[Boolean, GcsBatchIoCommand[Boolean, StorageObject]]] = {
    // If the object can't be found, don't fail the request but just return false as we were testing for existence
    if (googleJsonError.getCode == 404) Option(Left(false)) else None
  }

  override def withUserProject: GcsBatchExistsCommand = this.copy(setUserProject = true)
}

object GcsBatchExistsCommand {
  def forPath(file: GcsPath): Try[GcsBatchExistsCommand] = {
    file.objectBlobId.map(GcsBatchExistsCommand(file, _))
  }
}

/** A GcsBatchIoCommand for use in tests. */
case object ExceptionSpewingGcsBatchIoCommand extends GcsBatchIoCommand[Unit, Void] {
  override lazy val name: String = "exception"

  override def operation: Nothing = throw new UnsupportedOperationException("operation is not supported")

  override def withUserProject: Nothing = throw new UnsupportedOperationException("withUserProject is not supported")

  override def mapGoogleResponse(response: Void): Nothing =
    sys.error("Mapping that value is impossible")

  override def onFailure(googleJsonError: GoogleJsonError, httpHeaders: HttpHeaders): Nothing =
    sys.error("exception in failure handler...")
}

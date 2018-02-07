package centaur.cwl
import com.typesafe.config.Config
import common.validation.Parse.Parse
import cwl.preprocessor.CwlPreProcessor
import io.circe.optics.JsonPath
import io.circe.optics.JsonPath._
import io.circe.yaml.Printer.StringStyle
import io.circe.{Json, yaml}
import net.ceedubs.ficus.Ficus._

/**
  * Tools to pre-process the CWL workflows and inputs before feeding them to Cromwell so they can be executed on PAPI.
  */
class PAPIPreprocessor(config: Config) {
  val cwlPreProcessor = new CwlPreProcessor()
  
  // GCS directory where inputs for conformance tests are stored
  private val gcsPrefix = {
    val rawPrefix = config.as[String]("papi.default-input-gcs-prefix")
    if (rawPrefix.endsWith("/")) rawPrefix else rawPrefix + "/"
  }

  // Default docker pull image
  val DefaultDockerPull = "dockerPull" -> Json.fromString("ubuntu:latest")
  
  // Default docker image to be injected in a pre-existing requirements array
  private val DefaultDockerRequirement: Json = {
    Json.obj(
      "class" -> Json.fromString("DockerRequirement"),
      DefaultDockerPull
    )
  }

  // Requirements array with default docker requirement
  private val DefaultDockerRequirementList: Json = {
    Json.obj(
      "requirements" -> Json.arr(DefaultDockerRequirement)
    )
  }

  // Parse value, apply f to it, and print it back to String using the printer
  private def process(value: String, f: Json => Json, printer: Json => String) = {
    yaml.parser.parse(value) match {
      case Left(error) => throw new Exception(error.getMessage)
      case Right(json) => printer(f(json))
    }
  }

  // Process and print back as YAML
  private def processYaml(value: String)(f: Json => Json) =
    process(value, f, yaml.Printer.spaces2.copy(stringStyle = StringStyle.DoubleQuoted).pretty)

  // Prefix the string at "key" with the gcs prefix
  private def prefixLocationWithGcs(value: String): String = gcsPrefix + value

  // Function to check if the given json has the provided key / value pair
  private def hasKeyValue(key: String, value: String): Json => Boolean = {
    root.selectDynamic(key).string.exist(_.equalsIgnoreCase(value))
  }

  /**
    * Pre-process input file by prefixing all files and directories with the gcs prefix
    */
  def preProcessInput(input: String): Parse[String] = cwlPreProcessor.preProcessInputFiles(input, prefixLocationWithGcs)

  // Check if the given path (as an array or object) has a DockerRequirement element
  def hasDocker(jsonPath: JsonPath)(json: Json): Boolean = {
    val hasDockerInArray: Json => Boolean = jsonPath.arr.exist(_.exists(hasKeyValue("class", "DockerRequirement")))
    val hasDockerInObject: Json => Boolean = jsonPath.obj.exist(_.kleisli("DockerRequirement").nonEmpty)

    hasDockerInArray(json) || hasDockerInObject(json)
  }

  // Check if the given Json has a docker image in hints or requirements
  def hasDocker(json: Json): Boolean = hasDocker(root.hints)(json) || hasDocker(root.requirements)(json)

  // Add a default docker requirement to the workflow if it doesn't have one
  private def addDefaultDocker(workflow: Json) = if (!hasDocker(workflow)) {
    /*
      * deepMerge does not combine objects together but replaces keys which would overwrite existing requirements
      * so first check if there are requirements already and if so add our docker one.
      * Also turns out that the requirements section can be either an array or an object.
      * When it gets saladed the object is transformed to an array but because we deal with unsaladed cwl here
      * we have to handle both cases.
     */
    val requirementsAsArray = root.requirements.arr.modifyOption(_ :+ DefaultDockerRequirement)(workflow)
    val requirementsAsObject = root.requirements.obj.modifyOption(_.add("DockerRequirement", Json.obj(DefaultDockerPull)))(workflow)

    requirementsAsArray
      .orElse(requirementsAsObject)
      .getOrElse(workflow.deepMerge(DefaultDockerRequirementList))
  } else workflow

  /**
    * Pre-process the workflow by adding a default docker hint iff it doesn't have one
    */
  def preProcessWorkflow(workflow: String) = processYaml(workflow)(addDefaultDocker)
}

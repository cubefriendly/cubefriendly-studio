package org.cubefriendly.rest

import java.io.File

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.Multipart.FormData
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives._
import akka.stream.FlowMaterializer
import com.typesafe.config.Config
import org.cubefriendly.processors.CsvProcessor
import scaldi.Injectable
import spray.json.DefaultJsonProtocol

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContextExecutor}

/**
 * Cubefriendly
 * Created by david on 24.05.15.
 */

trait Protocols extends DefaultJsonProtocol {
  //Put here case class transformation
  implicit val messageFormat = jsonFormat1(MessageResult)
}

case class MessageResult(message:String)

trait CubefriendlyService extends Protocols with Injectable {

  implicit val system: ActorSystem
  implicit def executor: ExecutionContextExecutor
  implicit val materializer: FlowMaterializer

  def config: Config
  val logger: LoggingAdapter

  private def cubeDirectory:String = config.getString("services.cubefriendly.cubes")
  private def cubeFileName(name:String) = cubeDirectory + "/" + name + ".cube"

  private def cubeFile(name:String) : Option[File] = {
    val directory = new File(cubeDirectory)
    if(directory.exists()) {
      val cube = new File(cubeFileName(name))
      if(cube.exists() && cube.isFile) {
        Some(cube)
      }else {
        None
      }
    }else {
      directory.mkdirs()
      None
    }
  }

  val routes = {
    logRequestResult("cubefriendly-microservice") {
      pathPrefix("admin") {
        pathPrefix("source") {
          path("list") {
            complete {
              new File(cubeDirectory).listFiles().map(_.getName)
            }
          } ~
          path("upload") {
            post {
              entity(as[FormData]) { formData => {
                Await.result(
                  formData.parts.runForeach(bodyPart => {
                    // read a upload file, but not execute this block
                    val filename = bodyPart.filename.getOrElse("upload")
                    val dest = cubeFile(filename) match {
                      case Some(file) =>
                        file.delete()
                        file
                      case None => new File(cubeFileName(filename))
                    }

                    bodyPart.entity.dataBytes.runFold(new CsvProcessor(dest))({ (processor, byteString) =>
                      processor.process(byteString.decodeString("UTF-8").toCharArray)
                    }).map(_.complete().close())
                  }).map(u => complete{MessageResult("upload successful!")}), Duration.Inf)
              }}
            }
          }
        }
      }
    }
  }

  val optionsSupport = {
    options {complete("")}
  }

  val corsHeaders = List(RawHeader("Access-Control-Allow-Origin", "*"),
    RawHeader("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS, DELETE"),
    RawHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, Authorization, Cache-Control") )
  val corsRoutes = {
    respondWithHeaders(corsHeaders) {routes ~ optionsSupport}
  }
}

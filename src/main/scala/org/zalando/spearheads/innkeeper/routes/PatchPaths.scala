package org.zalando.spearheads.innkeeper.routes

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.google.inject.Inject
import org.slf4j.LoggerFactory
import org.zalando.spearheads.innkeeper.Rejections.{DuplicatePathUriRejection, PathOwnedByTeamAuthorizationRejection, UnmarshallRejection}
import org.zalando.spearheads.innkeeper.api.JsonProtocols._
import org.zalando.spearheads.innkeeper.api.PathPatch
import org.zalando.spearheads.innkeeper.metrics.RouteMetrics
import org.zalando.spearheads.innkeeper.oauth.OAuthDirectives._
import org.zalando.spearheads.innkeeper.oauth.{AuthenticatedUser, Scopes}
import org.zalando.spearheads.innkeeper.services.ServiceResult.DuplicatePathUri
import org.zalando.spearheads.innkeeper.services.team.TeamService
import org.zalando.spearheads.innkeeper.services.{PathsService, ServiceResult}

import scala.concurrent.ExecutionContext
import scala.util.Success

class PatchPaths @Inject() (
    pathsService: PathsService,
    metrics: RouteMetrics,
    scopes: Scopes,
    implicit val teamService: TeamService,
    implicit val executionContext: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(authenticatedUser: AuthenticatedUser, token: String, id: Long): Route = {
    patch {
      val reqDesc = "patch /paths"
      logger.info(s"try to $reqDesc")

      entity(as[PathPatch]) { pathPatch =>
        logger.info(s"We try to $reqDesc unmarshalled pathPatch $pathPatch")

        team(authenticatedUser, token, reqDesc) { team =>
          logger.debug(s"patch /paths team $team")

          hasOneOfTheScopes(authenticatedUser, reqDesc, scopes.WRITE) {
            logger.debug(s"patch /paths non-admin team $team")

            if (pathPatch.ownedByTeam.isDefined) {
              reject(PathOwnedByTeamAuthorizationRejection(reqDesc))
            } else {
              patchPathRoute(id, pathPatch, reqDesc)
            }
          } ~ hasAdminAuthorization(authenticatedUser, team, reqDesc, scopes)(teamService) {
            logger.debug(s"patch /paths admin team $team")

            patchPathRoute(id, pathPatch, reqDesc)
          }
        }
      } ~ {
        reject(UnmarshallRejection(reqDesc))
      }
    }
  }

  private def patchPathRoute(id: Long, pathPatch: PathPatch, reqDesc: String): Route = {
    metrics.postPaths.time {
      logger.debug(s"$reqDesc patchPath")
      onComplete(pathsService.patch(id, pathPatch)) {
        case Success(ServiceResult.Success(pathOut))             => complete(pathOut)
        case Success(ServiceResult.Failure(DuplicatePathUri(_))) => reject(DuplicatePathUriRejection(reqDesc))
        case _                                                   => reject
      }
    }
  }
}

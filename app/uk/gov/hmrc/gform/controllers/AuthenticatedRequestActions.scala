/*
 * Copyright 2017 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.gform.controllers

import play.api.mvc
import play.api.mvc.{ Action, AnyContent, Request }
import uk.gov.hmrc.play.frontend.auth
import uk.gov.hmrc.play.frontend.auth.AuthContext

import scala.concurrent.Future

class AuthenticatedRequestActions(authenticatedBy: auth.Actions#AuthenticatedBy) {

  def async(f: AuthenticatedRequest => Future[mvc.Result]): Action[AnyContent] = authenticatedBy.async { authContext => request =>
    val sessionContext = AuthenticatedRequest(authContext, request)
    f(sessionContext)
  }

  def apply(f: AuthenticatedRequest => mvc.Result): Action[AnyContent] = authenticatedBy.apply { authContext => request =>
    val sessionContext = AuthenticatedRequest(authContext, request)
    f(sessionContext)
  }

}

case class AuthenticatedRequest(
  authContext: AuthContext,
  request: Request[_]
)

object AuthenticatedRequest {
  implicit def request(implicit c: AuthenticatedRequest): Request[_] = c.request
  implicit def authContext(implicit c: AuthenticatedRequest): AuthContext = c.authContext
}
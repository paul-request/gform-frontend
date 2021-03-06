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

package uk.gov.hmrc.bforms.controllers

import javax.inject.{Inject, Singleton}

import cats.data.Validated
import cats.data.Validated.{Invalid, Valid}
import cats.instances.either._
import cats.instances.list._
import cats.syntax.traverse._
import cats.syntax.either._
import play.api.i18n.{I18nSupport, MessagesApi}
import play.api.libs.json.Json
import play.api.mvc.Result
import uk.gov.hmrc.bforms.controllers.helpers.FormHelpers._
import uk.gov.hmrc.bforms.models._
import uk.gov.hmrc.bforms.models.components._
import uk.gov.hmrc.bforms.models.form._
import uk.gov.hmrc.play.frontend.controller.FrontendController

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.bforms.service.SaveService
import uk.gov.hmrc.play.http.HeaderCarrier

@Singleton
class FormGen @Inject()(val messagesApi: MessagesApi, val sec: SecuredActions)(implicit ec: ExecutionContext)
  extends FrontendController with I18nSupport {

  def form(formTypeId: FormTypeId, version: String) =
    sec.SecureWithTemplateAsync(formTypeId, version) { implicit authContext =>
      implicit request =>

        val formTemplate = request.formTemplate

        Page(0, formTemplate).renderPage(Map.empty[FieldId, Seq[String]], None, None)

    }

  def formById(formTypeId: FormTypeId, version: String, formId: FormId) = formByIdPage(formTypeId, version, formId, 0)

  def formByIdPage(formTypeId: FormTypeId, version: String, formId: FormId, currPage : Int) = sec.SecureWithTemplateAsync(formTypeId, version) {
    implicit authContext =>
    implicit request =>

      SaveService.getFormById(formTypeId, version, formId).flatMap { formData =>

        val lookup: Map[FieldId, Seq[String]] = formData.fields.map(fd => fd.id -> List(fd.value)).toMap

        val formTemplate = request.formTemplate

        Page(currPage, formTemplate).renderPage(lookup, Some(formId), None)

      }
  }


  private def alwaysOk(fieldValue: FieldValue)(xs: Seq[String]): FormFieldValidationResult = {
    xs match {
      case Nil => FieldOk(fieldValue, "")
      case value :: rest => FieldOk(fieldValue, value) // we don't support multiple values yet
    }
  }

  private def validateRequired(fieldValue: FieldValue)(xs: Seq[String]): FormFieldValidationResult = {
    xs.filterNot(_.isEmpty()) match {
      case Nil => RequiredField(fieldValue)
      case value :: Nil => FieldOk(fieldValue, value)
      case value :: rest => FieldOk(fieldValue, value) // we don't support multiple values yet
    }
  }


  def validateFieldValue(fieldValue: FieldValue, formValue: ComponentData): FormFieldValidationResult = {
    formValue match {
      case AddressComponentData(street1, street2, street3, town, county, postcode, country) =>
        val validateRF = validateRequired(fieldValue) _
        ComponentField(
          fieldValue,
          Map(
            "street1" -> validateRF(street1),
            "street2" -> alwaysOk(fieldValue)(street2),
            "street3" -> alwaysOk(fieldValue)(street3),
            "town" -> validateRF(town),
            "county" -> alwaysOk(fieldValue)(county),
            "postcode" -> validateRF(postcode),
            "country" -> alwaysOk(fieldValue)(country)
          )
        )
      case DateComponentData(day, month, year) =>
        val validateRF = validateRequired(fieldValue) _
        ComponentField(
          fieldValue,
          Map(
            "day" -> validateRF(day),
            "month" -> alwaysOk(fieldValue)(month),
            "year" -> alwaysOk(fieldValue)(year)
          )
        )
      case TextData(formValue) => fieldValue.mandatory match {
        case true => validateRequired(fieldValue)(formValue)
        case false => alwaysOk(fieldValue)(formValue)
      }

      case ChoiceComponentData(selected) =>
        (fieldValue.mandatory, selected) match {
          case (true, Nil) =>
            RequiredField(fieldValue)
          case _ =>
            val data = selected.map(selectedIndex => fieldValue.id.value + selectedIndex -> FieldOk(fieldValue, selectedIndex)).toMap
            ComponentField(fieldValue, data)
        }
    }
  }

  val FormIdExtractor = "bforms/forms/.*/.*/([\\w\\d-]+)$".r.unanchored

  /**
    *
    * Validation Process
    */
  case class DateData(data: Map[FieldValue, DateComponentData]) {

    def parse(fieldValue: FieldValue): Validated[DateError, FormFieldValidationResult] = { // try List[DateError]

      data.get(fieldValue).map(_.day) match{
        case dayViolation => Invalid(DayViolation(fieldValue.id, "error message for day"))
        case properDatec=> Valid(FieldOk(fieldValue, properDatec.toString))
      }
    }

  }

  def save(formTypeId: FormTypeId, version: String, currentPage: Int) = sec.SecureWithTemplateAsync(formTypeId, version) {
    implicit authContext =>
    implicit request =>
      processResponseDataFromBody(request) { data =>
          val formTemplate = request.formTemplate

          val page = Page(currentPage, formTemplate)
          val nextPage = Page(page.next, formTemplate)

        val formIdOpt: Option[FormId] = anyFormId(data)

          val actionE: Either[String, FormAction] = FormAction.fromAction(getActions(data, FieldId("save")), page)

          val dataGetter: FieldValue => String => Seq[String] = fv => suffix => data.get(fv.id.withSuffix(suffix)).toList.flatten

          val validate: Map[FieldValue, ComponentData] = page.section.fields.map { fv =>
            fv.`type` match {
              case Address =>
                val getData = dataGetter(fv)
                val acd = AddressComponentData(
                  getData("street1"),
                  getData("street2"),
                  getData("street3"),
                  getData("town"),
                  getData("county"),
                  getData("postcode"),
                  getData("country")
                )
                fv -> acd
              case Date(_, _, _) =>
                val getData = dataGetter(fv)

                val acd = DateComponentData(
                  getData("day"),
                  getData("month"),
                  getData("year")
                )
                fv -> acd
              case Text(_) => fv -> TextData(data.get(fv.id).toList.flatten)
              case Choice(_, _, _, _) => fv -> ChoiceComponentData(data.get(fv.id).toList.flatten)
            }
          }.toMap

          val validationResults: Map[FieldValue, FormFieldValidationResult] = validate.map {
            case (fieldValue, values) =>
              fieldValue -> validateFieldValue(fieldValue, values)
          }

          def saveAndProcessResponse(continuation: SaveResult => Future[Result])(implicit hc: HeaderCarrier): Future[Result] = {
            val canSave: Either[Unit, List[FormField]] = validationResults.map {
              case (_, validationResult) => validationResult.toFormField
            }.toList.sequenceU.map(_.flatten)

            canSave match {
              case Right(formFields) =>
                val formData = FormData(formTypeId, version, "UTF-8", formFields)
                submitOrUpdate(formIdOpt, formData, false).flatMap {
                  case SaveResult(_, Some(error)) => Future.successful(BadRequest(error))
                  case result => continuation(result)
                }
              case Left(_) =>
                page.renderPage(data, formIdOpt, Some(validationResults.get))
            }
          }

          actionE match {
            case Right(action) =>
              action match {
                case SaveAndContinue =>
                  saveAndProcessResponse { saveResult =>
                    getFormId(formIdOpt, saveResult) match {
                      case Right(formId) => nextPage.renderPage(data, Some(formId), None)
                      case Left(error) => Future.successful(BadRequest(error))
                    }
                  }
                case SaveAndExit =>
                  val formFields: List[FormField] = validationResults.values.flatMap(_.toFormFieldTolerant).toList

                  val formData = FormData(formTypeId, version, "UTF-8", formFields)
                  submitOrUpdate(formIdOpt, formData, true).map(response => Ok(Json.toJson(response)))

              case SaveAndSummary =>
                saveAndProcessResponse { saveResult =>

                  getFormId(formIdOpt, saveResult) match {

                    case Right(formId) =>
                      Future.successful(Redirect(routes.SummaryGen.summaryById(formTypeId, version, formId)))
                    case Left(error) =>
                      Future.successful(BadRequest(error))
                  }
                }
            }

            case Left(error) =>
              Future.successful(BadRequest(error))
          }
      }
  }

  private def submitOrUpdate(formIdOpt: Option[FormId], formData: FormData, tolerant: Boolean)(implicit hc: HeaderCarrier): Future[SaveResult] = {
    formIdOpt match {
      case Some(formId) =>
        SaveService.updateFormData(formId, formData, tolerant)
      case None =>
        SaveService.saveFormData(formData, tolerant)
    }
  }

  private def getFormId(formIdOpt: Option[FormId], saveResult: SaveResult): Either[String, FormId] = {
    formIdOpt match {
      case Some(formId) => Right(formId)
      case None => saveResult.success match {
        case Some(FormIdExtractor(formId)) => Right(FormId(formId))
        case Some(otherwise) => Left(s"Cannot determine formId from $otherwise")
        case None => Left(s"Cannot determine formId from ${Json.toJson(saveResult)}")
      }
    }
  }
}

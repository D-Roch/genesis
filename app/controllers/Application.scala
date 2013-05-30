package controllers

import play.api._
import play.api.mvc._
import play.api.data._
import play.api.data.Forms._
import models.Rungekuttatest
import play.api.libs.json._

import models._
import views._
import factories._

/** The Application object handles everything related to authentication. */
object Application extends Controller {

  val rkt = Rungekuttatest()

  /** Form used for authenticating a user. */
  val loginForm = Form(
    tuple(
      "email" -> text,
      "password" -> text
    ) verifying ("Wrong email or password", result => result match {
      case (email, password) => User.authenticate(email, password).isDefined
    })
  )

  /** Login page. */
  def login = Action { implicit request =>
    println(ProteinJSONFactory.proteinAndParamsJSON("A",0))
    Ok(html.login(loginForm))
  }
  
  /** Logout page */
  def logout = Action {
    Redirect(routes.Application.login).withNewSession.flashing(
      "success" -> "You've been logged out"
    )
  }

  /** Handle login form submission. */
  def authenticate = Action { implicit request =>
    loginForm.bindFromRequest.fold(
      formWithErrors => BadRequest(html.login(formWithErrors)),
      user => Redirect(routes.Home.home).withSession("inlog" -> user._1)
    )
  }

  def javascriptRoutes = Action { implicit request =>
    import routes.javascript._
    Ok(
      Routes.javascriptRouter("jsRoutes")(
        routes.javascript.Application.jsontest,
        routes.javascript.Application.getlibrary,
        routes.javascript.Application.getalllibraries,
        routes.javascript.Application.simulate
      )
    ).as("text/javascript")
  }

  def simulate = Action { implicit request =>
    val data = request.body.asJson
    Ok("Hier moeten de resultaten in JSON komen")
  }
  
  def getLibraryList = Action { implicit request =>
    request.session.get("email") match{
      case Some(email) => {
        User.findByEmail(email) match{
          case Some(u) => {
            val userID = u.id
            Ok(ProteinJSONFactory.libraryListJSON(userID))
          }
          case _ => Redirect(routes.Application.login).withNewSession
        }
      }
      case _ => Redirect(routes.Application.login).withNewSession
    }
  }

  def getlibrary = Action { implicit request =>
    val libraryName = request.body.toString()
    request.session.get("email") match{
      case Some(email) => {
        User.findByEmail(email) match{
          case Some(u) => {
            val userID = u.id
            val libraryID = FileParser.getLibraryID(userID,libraryName)
		    val jsonObject = Json.obj("and"->ProteinJSONFactory.proteinAllAndParamsJSON(libraryID),
		    					"not"->ProteinJSONFactory.proteinNotParamsJSON(libraryID),
		    					"cds"->ProteinJSONFactory.proteinCDSParamsJSON(libraryID))    					
		    Ok(jsonObject)
          }
          case _ => Redirect(routes.Application.login).withNewSession
        }	    
      }
      case _ => Redirect(routes.Application.login).withNewSession
    }
  }

  def getalllibraries = Action { implicit request =>
    val libraries = "placeholder"
    Ok(libraries).as("plain/text")
  }
  
  def rk = Action {
    Ok(views.html.rungekutte("good ol' runge kutta test",rkt))
  }

  def jsontest = Action {
    Ok(Rungekuttatest().genJson)
  }

  def canvastest = Action {
    Ok(views.html.canvastest("just a quick test with a canvas"))
  }

  def morefun = Action {
    Ok(views.html.mofu("another test with the lastest and greatest model"))
  }

  def plumbtest = Action {
    Ok(views.html.plumbtest("Testing jsPlumb"))
  }
  
    def dndtest = Action {
    Ok(views.html.dndtest("Testing jsPlumb"))
  }

  def rungekutta = Action {
    Ok(views.html.rungekutte("Testing the plot and rungeKutta", rkt))
  }
}

/** Provide security features */
trait Secured {
  
  /** Retrieve the connected user. */
  private def username(request: RequestHeader) = request.session.get("inlog")

  private def onUnauthorized(request: RequestHeader) = Results.Redirect(routes.Application.login)
  
  /** Action for authenticated users. */
  def IsAuthenticated(f: => String => Request[AnyContent] => Result) = Security.Authenticated(username, onUnauthorized) { user =>
    Action(request => f(user)(request))
  }
}

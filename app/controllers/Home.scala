package controllers

import play.api.mvc.Controller
import play.api.mvc.Action
import play.api.data.Forms._
import play.api.templates.Html
import models._
import views._
import factories._

object Home extends Controller with Secured{
  
  val homeText = "Some text that'll make you feel right at home"
    
  def home = IsAuthenticated { email => _ =>
      Application.setSessionHelper(email)
      User.findByEmail(email).map { user =>
        Ok(html.home("Home")(Html.apply(homeText)))
      }.getOrElse(Forbidden)
  }
}
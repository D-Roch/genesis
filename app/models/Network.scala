package models
import scalation.{VectorD, RungeKutta}
import factories.ODEFactory._
import play.api.db.DB
import play.api.Play.current
import anorm._
import anorm.SqlParser._
import scala.collection.mutable.Map
import play.api.libs.json._

/**
 *  Class to represent a network of coding sequences and transcription factors.
 *  It has functionality to simulate how the concentrations of the components of
 *  the network change over time, and outputs the corresponding concentrations if
 *  desired.
 */
class Network(val inputs: List[CodingSeq], val user: String, val networkname: String) {

    /**
     *  The amount of time between steps; this also determines how many steps the
     *  simulation will take (given a fixed ending time)
     *  Alexey (in meeting on 4/26): may need to decide this dynamically based on the end time
     */
    private val stepSize = 0.01
    /**
     *  The current time; needed by the RungeKutta integrator
     */
    private var currentTime = 0.0

    private var steps = 0

    /**
     *  Perform the simulation that will show how the concentrations of the checmicals
     *  in the system will evolve and return these concentrations as follows:
     *  Each time step a list of pairs is generated, with mRNA and protein concentration,
     *  and these time steps form a list as well.
     *  @param finish The finish time; simulation will run from 0.0 to this time.
     */
    def simulate(finish: Double): List[List[(String,Double,Double)]] = {
        // function to get all the concentrations out of the network as a list of pairs
    	def getConcs(l: List[CodingSeq] = inputs): Set[(String,Double,Double)] = l.flatMap(seq => seq match {
            case CodingSeq(name,_,_) => Set((name, seq.concentration._1, seq.concentration._2)) ++ (seq.linksTo match {
                case Some(NotGate(_,next)) => getConcs(List(next))
                case Some(AndGate(_,next)) => getConcs(List(next))
                case _ => Nil
            })
        }).toSet

        currentTime = 0.0
        val times = currentTime to finish by stepSize
        // do the required steps and save the concentrations each round
        times.foldRight(List(getConcs().toList))((time,li)=>{
            step() // this is very poor actually: functional method fold has side effects now
            getConcs().toList :: li
        }).reverse
    }

    /**
     *  Function that performs the simulation just like simulate(), except this returns
     *  a JSON value that can be used to plot a graph
     */
    def simJson(finish: Double) = {
        val results = simulate(finish)
        val flipped = new scala.collection.mutable.ListMap[String, scala.collection.mutable.ListBuffer[(String, Double, Double)]]()
        results(0).foreach( triple => flipped += triple._1 -> new scala.collection.mutable.ListBuffer[(String,Double,Double)]())
        results.foreach( li => {
            li.foreach( triple => flipped(triple._1) += triple)
        })
        Json.toJson(flipped.values.flatMap( dataset => {
            var x = 0.0-stepSize; var y = 0.0-stepSize
            List(Json.obj( "name" -> Json.toJson("mRNA_"+dataset(0)._1), "data" ->
                dataset.map(_._2).map(conc => {x+=stepSize; Json.obj("x" -> x*10000, "y" -> conc)})),
                Json.obj("name" -> Json.toJson("protein_"+dataset(0)._1), "data" ->
                dataset.map(_._3).map(conc => {y+=stepSize; Json.obj("x" -> y*10000, "y" -> conc)}))
                )}))
    }

    /**
     *  Do a step in the simulation. That is, calculate the new concentrations
     *  of all the proteins in the system, using a level-based approach to solving
     *  the ODEs. A level is defined as all the CodingSeqs and the first gates they
     *  link to, with the condition that any gate that is linked to by any CodingSeq
     *  in the current list, must have both of its inputs in the list. If it doesn't,
     *  the gate belongs to a different, deeper level and will be handled later,
     *  when both of its inputs have been updated.
     */
    def step() {
        currentTime += stepSize

        // resets all the ready flags
        def reset_readies(cs: CodingSeq) {
            cs.ready=false
            cs.linksTo match {
                case Some(NotGate(_,y)) => reset_readies(y)
                case Some(AndGate((_,_),y)) => reset_readies(y)
                case _ => return
            }
        }

        // update the first CSs in the network because do_the_math won't touch them
        inputs.foreach( x => {
            reset_readies(x)
            val newConcs = solve(mkODEs(List(x)))(0)
            x.concentration=(newConcs(0),newConcs(1))
            x.ready=true
        })
        do_the_math(inputs)

        // the function that will do the actual work
        def do_the_math(css: List[CodingSeq]) {
            // generate the appropriate ODEPairs and update the concentrations
            val parts = css.flatMap( x => x.linksTo match {
                case Some(y) => y match {
                    case NotGate(_,out) => List(y)
                    case AndGate((in1,in2),_) if((in1.ready && in2==x) || (in1==x && in2.ready)) => List(y)
                    case _ => Nil
                }
                case None => Nil
            })
            if(parts.length == 0)
                return
            val odePairs = mkODEs(parts)
            val results = solve(odePairs)
            results.zip(parts).foreach(_ match {
                case (a,b:NotGate) => b.output.ready=true; b.output.concentration=(a(1),a(2))
                case (a,b:AndGate) => b.output.ready=true; b.output.concentration=(a(2),a(3))
                })
            // finally, recursively update the rest of the network
            do_the_math(parts.collect( {
                case x:NotGate => x.output
                case x:AndGate => x.output }))
        }

        // the function that calls the solver; the solver expects each element of the
        // result vector to be generated by a separate function, hence the awkward Array.
        // the different cases are needed because the integrator expects that there are
        // as many functions as there are elements in the vector of initial concentrations
        def solve(odePairs: List[ODEPair]): List[VectorD] = {
        		odePairs.map( {case (ode, concs) if concs.length == 3 => RungeKutta.integrateVV(Array((d, v)=>0.0, (d, v)=>ode(d, v)(0), (d, v)=>ode(d, v)(1)), concs, currentTime, 0.0, stepSize)
                           case (ode, concs) if concs.length == 4 => RungeKutta.integrateVV(Array((d, v)=>0.0, (d, v) => 0.0, (d, v)=>ode(d, v)(0), (d, v)=>ode(d, v)(1)), concs, currentTime, 0.0, stepSize)
                           case (ode, concs) => RungeKutta.integrateVV(Array((d, v)=>ode(d, v)(0), (d, v)=>ode(d, v)(1)), concs, currentTime, 0.0, stepSize)} )
        }
    }
    
    def save = {
	    DB.withConnection { implicit connection =>
	      SQL(
	        """
	         merge into ownedby(userid,networkname) key(userid,networkname) values({user},{networkname})
	        """
	      ).on(
	        'user -> user,
	        'networkname -> networkname
	      ).executeUpdate()
	      
	      val idResult = SQL(
	          """
	          select id from ownedby
	          where username={user} AND networkname={networkname}
	          """
	          ).on(
		        'user -> user,
		        'networkname -> networkname
		      ).apply().head
		  val id = idResult[Int]("id")
		  for(cs <- inputs){
			  SQL(
		        """
		         merge into start(id,name) key(id,name) values({id},{name})
		        """
		      ).on(
		        'id -> id,
		        'name -> cs.name
		      ).executeUpdate()
		  }    
	      
		  for(cs:CodingSeq <- inputs) {
		    cs.save(id)
		  }
	    }
	    
	  }
}

object Network {
    import play.api.libs.json._

    /**
     *  Function designed to format the simulation data into JSON that can then
     *  be used by the Rickshaw library to make a pretty graph. More concretely,
     *  the function generates the value for the series property of the graph object.
     *  Data needs to be transposed because it contains concentrations for each time
     *  step, while we need time steps for each concentration (i.e. chemical)
     *  @param data A list of simulation data as generated by Network.simulate
     */
    def simToJson(data: List[List[(Double,Double)]]): JsArray = Json.arr(data.transpose.map( frame => {
        Json.obj( "name" -> "to be filled in", "data" -> Json.arr(
            frame.map(pair => Json.obj("x" -> pair._1, "y" -> pair._2))
        )
    )}))
    
    /**
     * Return the Network object with name 'networkname' that belongs to 'user'
     */
    def load(user: String, networkname: String): Network = {
      DB.withConnection{ implicit connection =>
      	val idResult = SQL(
	          """
	          select id from ownedby
	          where userid={user} AND networkname={networkname}
	          """
	          ).on(
		        'user -> user,
		        'networkname -> networkname
		      ).apply().head
		  val id = idResult[Int]("id")
      	  val tempconcs = SQL(
			      """
			      select * from concentrations
	    		  where id = {id}
			      """
			      ).on('id -> id)
			      .as {
	      	  		get[String]("name")~get[Int]("time")~get[Double]("conc1")~get[Double]("conc2") map{
	      	  		  case name~time~conc1~conc2 => (name,time,conc1,conc2)
	      	  		} *
	      	}
      	  var tempconcs2:Map[String,List[(Double,Double,Double)]] = Map()
      	  for(c <- tempconcs){
      	    if(tempconcs2 contains c._1){
      	      tempconcs2(c._1) ::= (c._2,c._3,c._4)
      	    }
      	    else{
      	      tempconcs2 += (c._1 -> List((c._2,c._3,c._4)))
      	    }
      	  }
      	  var concentrations:Map[String,List[(Double,Double)]] = Map()
      	  for(c <- tempconcs2.keys){
      	    val list = tempconcs2(c).sortBy(_._1).map(x => (x._2,x._3))
      	    concentrations += c -> list
      	  }      	  
      	  
		  var inputs1:Map[String,String] = Map()
	      var inputs2:Map[String,String] = Map()
	      var seqs:Map[String,CodingSeq] = Map()
	      val allCDS = SQL(
			      """
			      select * from cds
	    		  where id = {id}
			      """
			      ).on('id -> id)
			      .as {
	      	  		get[String]("name")~get[String]("next") map{
	      	  		  case name~next => (name,next)
	      	  		} *
	      	}
      	  for(cs <- allCDS){
      	    if(inputs1 contains cs._2) inputs2 += (cs._2 -> cs._1)
      	    else if(cs._2!="NONE") inputs1 += (cs._2 -> cs._1)
      	    seqs += (cs._1 -> new CodingSeq(cs._1,concentrations(cs._1)))
      	  }
      	  
      	  val startCDS = SQL(
			      """
			      select * from start
	    		  where id = {id}
			      """
			      ).on('id -> id)
			      .as {
	      	  		get[String]("name") map{
	      	  		  case name => seqs(name)
	      	  		} *
	      	}
	      
	      for(str: String <- inputs1.keys){
	        if(inputs2 contains str){
	        	val g = new AndGate((seqs(inputs1(str)),seqs(inputs2(str))),seqs(str))
	        }
	        else{
	        	val g = new NotGate(seqs(inputs1(str)),seqs(str))
	        }
	      }
      	new Network(startCDS,user,networkname)
      }
    }
}
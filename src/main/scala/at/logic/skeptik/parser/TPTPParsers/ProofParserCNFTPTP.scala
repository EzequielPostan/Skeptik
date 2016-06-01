package at.logic.skeptik.parser.TPTPParsers

import at.logic.skeptik.expression.Var
import at.logic.skeptik.judgment.immutable.{SeqSequent => Sequent}
import at.logic.skeptik.parser.{BaseParserTPTP, ProofParser}
import at.logic.skeptik.proof.Proof
import at.logic.skeptik.proof.sequent.{SequentProofNode => Node}
import at.logic.skeptik.proof.sequent.resolution.UnifyingResolution
import at.logic.skeptik.proof.sequent.lk.{Axiom, R, UncheckedInference}

import at.logic.skeptik.parser.TPTPParsers.TPTPAST._
import at.logic.skeptik.parser.UnexpectedEmptyTPTPFileException

import collection.mutable.{HashMap => MMap, HashSet => MSet, Set}


/**
  * Created by eze on 2016.05.25..
  */
object ProofParserCNFTPTP extends ProofParser[Node] with ProofParserCNFTPTP

/**
  * The ProofParserCNFTPTP trait implements a parser for the CNF fragment of TPTP syntax.
  * It recognizes only CNF cormulas, with contraction and resolution rules.
  *
  * TODO: Add more rules (if needed)
  *
  */
trait ProofParserCNFTPTP
extends BaseParserTPTP {


  private var nodeMap = new MMap[String,Node]


  def reset() { nodeMap.clear() }

  //Obtain the actual proof
  def proof: Parser[Proof[Node]] = TPTP_file ^^ generateProof

  ////////////////////////////////////////////////////////////////////
  // Proof generation
  ////////////////////////////////////////////////////////////////////
  private def generateProof(directives : List[TPTPDirective]) : Proof[Node] = {
    val expandedDirectives : List[TPTPDirective] = expandIncludes(directives,TPTP_file)
    if (expandedDirectives.isEmpty) throw new UnexpectedEmptyTPTPFileException
    else Proof(expandedDirectives.map(prossesDirective).last)
  }

  private def prossesDirective(directive : TPTPDirective) : Node = {
    val annotatedFormula : AnnotatedFormula = directive.asInstanceOf[AnnotatedFormula]
    require(annotatedFormula.language == lexical.CNF.chars)
    annotatedFormula.annotations match {
      case None             => annotatedFormulaToAxiom(annotatedFormula)
      case Some((source,_)) => annotatedFormulaToNode(annotatedFormula,source)
    }
  }

  private def annotatedFormulaToAxiom(annotatedFormula : AnnotatedFormula) : Node = annotatedFormula.formula match {
    case SimpleSequent(ant,suc) => {
      val axiom = Axiom(Sequent(ant:_*)(suc:_*))
      nodeMap += (annotatedFormula.name -> axiom)
      axiom
    }
    case SimpleFormula(formula) => throw new Exception("Not sequent formula detected: " + annotatedFormula.name)
  }

  def annotatedFormulaToResolution(name : String, formula: Option[RepresentedFormula] , parents : List[String]) : Node = {
    def unify(left : Node, right: Node, conclussion : Sequent, vars : Set[Var]) = {
      try {
        UnifyingResolution(left,right,conclussion)(vars)
      } catch {
        case e: Exception => {
          UnifyingResolution(right, left, conclussion)(vars)
        }
      }
    }

    def unify2(left : Node, right: Node, vars : Set[Var]) = {
      try {
        UnifyingResolution(left,right)(vars)
      } catch {
        case e: Exception => {
          UnifyingResolution(right, left)(vars)
        }
      }
    }

    require(parents.length == 2)
    if(formula.isEmpty) {
      val resolution = unify2(nodeMap(parents(0)), nodeMap(parents(1)), getSeenVars)
      nodeMap += (name -> resolution)
      resolution
    } else {
      val sequent = formula match {
        case Some(SimpleSequent(ant, suc)) => Sequent(ant: _*)(suc: _*)
        case _ => throw new Exception("Unexpected formula found, a sequent was spected")
      }
      val resolution = unify(nodeMap(parents(0)), nodeMap(parents(1)), sequent, getSeenVars)
      nodeMap += (name -> resolution)
      resolution
    }
  }

  private def annotatedFormulaToNode(annotatedFormula : AnnotatedFormula, source: Source) : Node = {
    val sourceInfo      = source.term
    val inferenceRecord = sourceInfo.filter(getData(_).nonEmpty)
    if(isAnAxion(inferenceRecord)) annotatedFormulaToAxiom(annotatedFormula)
    else {
      require(inferenceRecord.length == 1)
      createNode(annotatedFormula.name,Some(annotatedFormula.formula),getData(inferenceRecord.head))
    }
  }

  def createNode(name : String,formula: Option[RepresentedFormula],recordData: List[GeneralTerm]): Node = {
    val List(rule,_,parentList) = recordData
    val inferenceRule = extractRuleName(rule)
    val parents       = extractParents(formula,parentList)
    inferenceRule match {
      case "sr" => annotatedFormulaToResolution(name,formula,parents)
      case _    => throw new Exception("Inference Rule not supported: "+ inferenceRule)
    }
    // TODO: Complete this. The only thing to do is to compare the inferenceRule with the accepted ones
    //       and call a corresponding ProofNode constructor
  }

  // annotatedFormulaToNode auxiliary functions
  private def getData(term : Either[GeneralData,List[GeneralTerm]]) : List[GeneralTerm] = term match {
    case Left(GFunc("inference",list)) => list
    case _                             => Nil
  }
  private def isAnAxion(records : List[Either[GeneralData,List[GeneralTerm]]]) : Boolean = records.isEmpty
  private def extractRuleName(term : GeneralTerm) : String = term match {
    case GeneralTerm(List(Left(GWord(name)))) => name
    case _                                    => throw new Exception("Unexpercted format for inference rule.\n Found: "+ term.toString)
  }
  private var newNameCoubter = 0
  private def extractParents(formula : Option[RepresentedFormula] ,term : GeneralTerm) : List[String] = {
    def formarParent(parent : GeneralTerm) : String = parent match {
      case GeneralTerm(List(Left(GWord(p1)))) => p1
      case GeneralTerm(List(Left(GNumber(p1)))) => p1
      case GeneralTerm(List(Left(GFunc("inference",list)))) => {
        //This is the case where an inference record is nested inside another
        val newName = "NewNode"+ newNameCoubter
        newNameCoubter += 1
        createNode(newName.toString,None,list)
        newName
      }
      case _              => throw new Exception("Unexpected parent format!\nOnly names are allowd.\nFound: "+ parent.toString)
    }
    term match {
      case GeneralTerm(List(Right(parentList))) => parentList map formarParent
      case _                                        => throw new Exception("Unexpercted format for parents. Only parents name are accepted\n Found: "+ term.toString)
    }
  }


  def read(fileName: String) : Proof[Node] = {
    val p : Proof[Node] = parse(fileName,proof) match {
      case Success(p2,_)      => p2
      case Error(message,_)   => throw new Exception("Error: " + message)
      case Failure(message,_) => throw new Exception("Failure: " + message)
    }
    reset()
    p
  }

}